package com.hearthappy.loggerx;

public final class DemoNativeCrash {
    static {
        System.loadLibrary("loggerx_demo");
    }

    private DemoNativeCrash() {
    }

    public static native void crash();
}
