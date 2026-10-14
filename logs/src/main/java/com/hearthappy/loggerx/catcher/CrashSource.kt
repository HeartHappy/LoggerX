package com.hearthappy.loggerx.catcher

enum class CrashSource {
    JVM,
    BUGLY_JAVA,
    BUGLY_NATIVE,
    BUGLY_ANR,
    BUGLY_CAUGHT,
    SYSTEM_NATIVE,
    SYSTEM_ANR,
    SYSTEM_EXIT,
    NATIVE_CALL_CONTEXT,
    EXTERNAL
}

data class NativeCallToken internal constructor(internal val recordFileName: String)
