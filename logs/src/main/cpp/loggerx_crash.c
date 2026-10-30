#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ucontext.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

#define LOGGERX_FRAME_LIMIT 48
#define LOGGERX_ALT_STACK_SIZE (32 * 1024)

static char g_directory[400];
static char g_process[128];
static char g_alt_stack[LOGGERX_ALT_STACK_SIZE];
static struct sigaction g_previous[32];
static char g_installed[32];
static int g_stack_ready = 0;
static volatile sig_atomic_t g_handling = 0;

static const int k_signals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP};

static void crash_handler(int sig, siginfo_t *info, void *context);

struct BacktraceState {
    uintptr_t frames[LOGGERX_FRAME_LIMIT];
    int count;
};

static int copy_text(char *dest, size_t dest_len, const char *src) {
    size_t index = 0;
    if (dest_len == 0) return 0;
    if (src == NULL) {
        dest[0] = '\0';
        return 0;
    }
    while (src[index] != '\0' && index + 1 < dest_len) {
        dest[index] = src[index];
        index++;
    }
    dest[index] = '\0';
    return index > 0;
}

static void write_all(int fd, const char *data, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        ssize_t written = write(fd, data + offset, length - offset);
        if (written <= 0) return;
        offset += (size_t) written;
    }
}

static void write_text(int fd, const char *text) {
    if (text == NULL) return;
    write_all(fd, text, strlen(text));
}

static const char *signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGFPE: return "SIGFPE";
        case SIGILL: return "SIGILL";
        case SIGTRAP: return "SIGTRAP";
        default: return "SIGNAL";
    }
}

static uintptr_t code_address(uintptr_t pc) {
#if defined(__arm__) || defined(__thumb__)
    return pc & ~(uintptr_t) 1;
#else
    return pc;
#endif
}

static uintptr_t fault_pc(void *context) {
    if (context == NULL) return 0;
#if defined(__arm__)
    return code_address(((ucontext_t *) context)->uc_mcontext.arm_pc);
#elif defined(__aarch64__)
    return ((ucontext_t *) context)->uc_mcontext.pc;
#elif defined(__i386__)
    return (uintptr_t) ((ucontext_t *) context)->uc_mcontext.gregs[REG_EIP];
#elif defined(__x86_64__)
    return (uintptr_t) ((ucontext_t *) context)->uc_mcontext.gregs[REG_RIP];
#else
    (void) context;
    return 0;
#endif
}

static uintptr_t fault_lr(void *context) {
    if (context == NULL) return 0;
#if defined(__arm__)
    return code_address(((ucontext_t *) context)->uc_mcontext.arm_lr);
#elif defined(__aarch64__)
    return ((ucontext_t *) context)->uc_mcontext.regs[30];
#else
    (void) context;
    return 0;
#endif
}

static void read_thread_name(char *out, size_t out_len) {
    char path[64];
    ssize_t count;
    int fd;
    if (out_len == 0) return;
    out[0] = '\0';
    if (gettid() == getpid()) {
        copy_text(out, out_len, "main");
        return;
    }
    snprintf(path, sizeof(path), "/proc/self/task/%d/comm", gettid());
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    count = read(fd, out, out_len - 1);
    close(fd);
    if (count <= 0) {
        out[0] = '\0';
        return;
    }
    if (out[count - 1] == '\n') count--;
    out[count] = '\0';
}

static _Unwind_Reason_Code unwind_callback(struct _Unwind_Context *context, void *argument) {
    struct BacktraceState *state = (struct BacktraceState *) argument;
    uintptr_t pc;
    if (state->count >= LOGGERX_FRAME_LIMIT) return _URC_END_OF_STACK;
    pc = _Unwind_GetIP(context);
    if (pc != 0) state->frames[state->count++] = pc;
    return _URC_NO_REASON;
}

static int is_handler_frame(uintptr_t pc) {
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (!dladdr((void *) pc, &info) || info.dli_fname == NULL) return 0;
    return strstr(info.dli_fname, "libloggerx_crash.so") != NULL;
}

static void write_pc_line(int fd, int index, uintptr_t pc) {
    char line[768];
    Dl_info info;
    int length;
    memset(&info, 0, sizeof(info));
    if (dladdr((void *) pc, &info) && info.dli_fbase != NULL) {
        uintptr_t offset = pc - (uintptr_t) info.dli_fbase;
        const char *library = info.dli_fname != NULL ? info.dli_fname : "??";
        const char *symbol = info.dli_sname != NULL ? info.dli_sname : "";
        length = snprintf(
                line,
                sizeof(line),
                "#%02d pc 0x%" PRIxPTR " %s (%s+0x%" PRIxPTR ")\n",
                index,
                pc,
                library,
                symbol,
                offset);
    } else {
        length = snprintf(line, sizeof(line), "#%02d pc 0x%" PRIxPTR "\n", index, pc);
    }
    if (length > 0) write_all(fd, line, (size_t) length);
}

static void write_frames(int fd, const struct BacktraceState *state, uintptr_t pc, uintptr_t lr) {
    uintptr_t emitted[LOGGERX_FRAME_LIMIT + 2];
    int emitted_count = 0;
    int index;
    write_text(fd, "backtrace:\n");
    if (pc != 0 && !is_handler_frame(pc)) {
        write_pc_line(fd, emitted_count, pc);
        emitted[emitted_count++] = pc;
    }
    if (lr != 0 && lr != pc && !is_handler_frame(lr)) {
        write_pc_line(fd, emitted_count, lr);
        emitted[emitted_count++] = lr;
    }
    for (index = 0; index < state->count && emitted_count < LOGGERX_FRAME_LIMIT; index++) {
        uintptr_t frame = code_address(state->frames[index]);
        int seen;
        int cursor;
        if (frame == 0 || is_handler_frame(frame)) continue;
        seen = 0;
        for (cursor = 0; cursor < emitted_count; cursor++) {
            if (emitted[cursor] == frame) {
                seen = 1;
                break;
            }
        }
        if (seen) continue;
        write_pc_line(fd, emitted_count, frame);
        emitted[emitted_count++] = frame;
    }
}

static void forward_signal(int sig, siginfo_t *info, void *context) {
    struct sigaction previous;
    struct sigaction fallback;
    if (sig < 0 || sig >= (int) (sizeof(g_previous) / sizeof(g_previous[0]))) {
        _exit(128);
    }
    previous = g_previous[sig];
    sigaction(sig, &previous, NULL);
    if ((previous.sa_flags & SA_SIGINFO) && previous.sa_sigaction != NULL && previous.sa_sigaction != crash_handler) {
        previous.sa_sigaction(sig, info, context);
        return;
    }
    if (!(previous.sa_flags & SA_SIGINFO) &&
        previous.sa_handler != SIG_DFL &&
        previous.sa_handler != SIG_IGN &&
        previous.sa_handler != NULL) {
        previous.sa_handler(sig);
        return;
    }
    memset(&fallback, 0, sizeof(fallback));
    fallback.sa_handler = SIG_DFL;
    sigemptyset(&fallback.sa_mask);
    sigaction(sig, &fallback, NULL);
    raise(sig);
}

static void crash_handler(int sig, siginfo_t *info, void *context) {
    struct BacktraceState state;
    char path[512];
    char header[512];
    char thread_name[64];
    struct timespec now;
    int fd;
    int header_length;
    if (g_handling) {
        forward_signal(sig, info, context);
        return;
    }
    g_handling = 1;
    memset(&state, 0, sizeof(state));
    _Unwind_Backtrace(unwind_callback, &state);
    clock_gettime(CLOCK_REALTIME, &now);
    read_thread_name(thread_name, sizeof(thread_name));
    snprintf(path, sizeof(path), "%s/n_%d_%d.txt", g_directory, getpid(), gettid());
    fd = open(path, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0600);
    if (fd >= 0) {
        header_length = snprintf(
                header,
                sizeof(header),
                "signal=%d\n"
                "signal_name=%s\n"
                "code=%d\n"
                "fault=0x%" PRIxPTR "\n"
                "pid=%d\n"
                "tid=%d\n"
                "process=%s\n"
                "thread=%s\n"
                "time_ms=%lld\n",
                sig,
                signal_name(sig),
                info != NULL ? info->si_code : 0,
                (uintptr_t) (info != NULL ? info->si_addr : NULL),
                getpid(),
                gettid(),
                g_process,
                thread_name,
                (long long) now.tv_sec * 1000LL + now.tv_nsec / 1000000LL);
        if (header_length > 0) write_all(fd, header, (size_t) header_length);
        write_frames(fd, &state, fault_pc(context), fault_lr(context));
        fsync(fd);
        close(fd);
    }
    forward_signal(sig, info, context);
}

static int install_handlers(void) {
    stack_t stack;
    struct sigaction action;
    int index;
    if (!g_stack_ready) {
        memset(&stack, 0, sizeof(stack));
        stack.ss_sp = g_alt_stack;
        stack.ss_size = sizeof(g_alt_stack);
        if (sigaltstack(&stack, NULL) != 0) return 0;
        g_stack_ready = 1;
    }
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = crash_handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESETHAND;
    sigemptyset(&action.sa_mask);
    for (index = 0; index < (int) (sizeof(k_signals) / sizeof(k_signals[0])); index++) {
        sigaddset(&action.sa_mask, k_signals[index]);
    }
    for (index = 0; index < (int) (sizeof(k_signals) / sizeof(k_signals[0])); index++) {
        struct sigaction previous;
        int sig = k_signals[index];
        if (sig < 0 || sig >= (int) (sizeof(g_previous) / sizeof(g_previous[0]))) continue;
        if (sigaction(sig, &action, &previous) != 0) continue;
        if (previous.sa_sigaction == crash_handler) {
            sigaction(sig, &previous, NULL);
            continue;
        }
        g_previous[sig] = previous;
        g_installed[sig] = 1;
    }
    return 1;
}

static void uninstall_handlers(void) {
    int index;
    for (index = 0; index < (int) (sizeof(k_signals) / sizeof(k_signals[0])); index++) {
        struct sigaction current;
        int sig = k_signals[index];
        if (sig < 0 || sig >= (int) (sizeof(g_installed) / sizeof(g_installed[0])) || !g_installed[sig]) continue;
        memset(&current, 0, sizeof(current));
        if (sigaction(sig, NULL, &current) == 0 && current.sa_sigaction == crash_handler) {
            sigaction(sig, &g_previous[sig], NULL);
        }
        g_installed[sig] = 0;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_hearthappy_loggerx_catcher_NativeCrashBridge_nativeInstall(
        JNIEnv *env,
        jclass clazz,
        jstring directory,
        jstring process_name) {
    const char *dir_chars;
    const char *process_chars = NULL;
    int copied;
    (void) clazz;
    if (directory == NULL) return JNI_FALSE;
    dir_chars = (*env)->GetStringUTFChars(env, directory, NULL);
    if (process_name != NULL) process_chars = (*env)->GetStringUTFChars(env, process_name, NULL);
    copied = dir_chars != NULL && copy_text(g_directory, sizeof(g_directory), dir_chars);
    if (copied) copy_text(g_process, sizeof(g_process), process_chars != NULL ? process_chars : "");
    if (dir_chars != NULL) (*env)->ReleaseStringUTFChars(env, directory, dir_chars);
    if (process_chars != NULL) (*env)->ReleaseStringUTFChars(env, process_name, process_chars);
    if (!copied) return JNI_FALSE;
    return install_handlers() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hearthappy_loggerx_catcher_NativeCrashBridge_nativeUninstall(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    uninstall_handlers();
}
