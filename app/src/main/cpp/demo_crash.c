#include <jni.h>

JNIEXPORT void JNICALL
Java_com_hearthappy_loggerx_DemoNativeCrash_crash(JNIEnv *env, jclass clazz) {
    volatile int *pointer = 0;
    (void) env;
    (void) clazz;
    *pointer = 1;
}
