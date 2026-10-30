package com.hearthappy.loggerx.catcher;

final class NativeCrashBridge {
    private static volatile boolean loaded;

    private NativeCrashBridge() {
    }

    static boolean install(String directory, String processName) {
        if (!ensureLoaded()) return false;
        return nativeInstall(directory, processName);
    }

    static void uninstall() {
        if (!loaded) return;
        nativeUninstall();
    }

    private static boolean ensureLoaded() {
        if (loaded) return true;
        try {
            System.loadLibrary("loggerx_crash");
            loaded = true;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static native boolean nativeInstall(String directory, String processName);

    private static native void nativeUninstall();
}
