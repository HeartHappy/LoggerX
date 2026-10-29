# LoggerX API 文档

## 一、集成

```
dependencies {
    implementation("io.github.hearthappy:loggerx:1.1.0")
}
```

## 二、简介

### 1. 组件概述

LoggerX 是一个极简且功能强大的 Android
日志框架，支持多作用域（Scope）管理、拦截器、数据库存储、高级查询、自动清理以及日志导出分享等功能。它提供了极简的调用入口，方便开发者在不同场景下快速记录和管理日志。

### 2. 安装配置

在使用 LoggerX 之前，需要在 Application 中进行全局初始化。

#### 全局初始化

```kotlin
 LoggerX.init(this)
```

### 3. 核心 API

#### 3.1 创建作用域

- 动态创建一个日志作用域代理。

```
  val CUSTOM_SCOPE = LoggerX.createScope("CustomScope")
```

#### 3.2 注册作用域

注册自定义的日志作用域和拦截器。

```
 LoggerX.registerScope(LogInterceptorAdapter(), CUSTOM_SCOPE)
```

#### 3.3 删除指定作用域日志

```
 LoggerX.COMMON.delete()
```

#### 3.4 清空所有日志

清空数据库中的所有日志记录。
```
 LoggerX.clear()
```

#### 3.5 导出并分享日志 `exportAndShareAll`

导出所有作用域的日志并触发分享。

- **方法名称**: `exportAndShareAll`
- **功能描述**: 将日志导出为指定格式的文件并分享。
- **参数列表**:
  | 参数名称 | 类型 | 是否必填 | 默认值 | 参数说明 |
  | :--- | :--- | :--- | :--- | :--- |
  | `exportAll` | `Boolean` | 否 | `true` | 是否导出所有记录 |
  | `limit` | `Int` | 否 | `1000` | 每个作用域导出的条数限制（仅在 `exportAll` 为 `false` 时生效） |
  | `format` | `ExportFormat` | 否 | `CSV` | 导出格式（CSV/TXT） |
  | `onProgress` | `(Int) -> Unit` | 否 | `null` | 总体导出进度回调 (0..100) |
- **返回值**: `Unit`
- **示例**:
```
  LoggerX.exportAndShareAll()
```

#### 3.6 开启自动清理（按天数） `enableAutoClean`

设置日志保留天数，过期自动清理。
- **方法名称**: `enableAutoClean`
- **功能描述**: 开启基于时间的自动清理任务。
- **参数列表**:
  | 参数名称 | 类型 | 是否必填 | 默认值 | 参数说明 |
  | :--- | :--- | :--- | :--- | :--- |
  | `retentionDays` | `Int` | 是 | - | 保留天数 |
- **返回值**: `Unit`
- **示例**:
```
 LoggerX.enableAutoClean(7)
```

#### 3.7 开启自动清理（按大小） `enableAutoClean`

设置数据库最大容量，超出后自动清理旧数据。

- **方法名称**: `enableAutoClean`
- **功能描述**: 开启基于文件大小的自动清理任务。
- **参数列表**:
  | 参数名称 | 类型 | 是否必填 | 默认值 | 参数说明 |
  | :--- | :--- | :--- | :--- | :--- |
  | `maxSizeMb` | `Double` | 是 | - | 数据库文件最大允许大小 (MB) |
  | `cleanSizeMb` | `Double` | 是 | - | 每次清理尝试减少的大小 (MB) |
- **返回值**: `Unit`
- **示例**:
```
LoggerX.enableAutoClean(maxSizeMb = 100.0, cleanSizeMb = 10.0)
```

#### 3.8 开启内存不足时清理（按大小）

设置内存不足时自动清理数据库。

- **方法名称**: `enableAutoCleanByLowMemory`
- **功能描述**: 当内存不足时，触发自动清理任务。
- **参数列表**:
  | 参数名称 | 类型 | 是否必填 | 默认值 | 参数说明 |
  | :--- | :--- | :--- | :--- | :--- |
  | `minFreeSpaceMb` | `Double` | 是 | - | 当内存不足minFreeSpaceMb大小时，自动清理最早一天的数据 (单位:MB) |
- **返回值**: `Unit`
- **示例**:
```
 LoggerX.enableAutoCleanByLowMemory(minFreeSpaceMb = 100.0)
```


### 4. 使用示例

#### 4.1 使用内置作用域

LoggerX 预定义了五个常用的作用域：`COMMON`, `IMPORTANT`, `KERNEL`, `ERROR`, `CATCH`。

```kotlin
LoggerX.COMMON.d("这是一条普通调试日志")
LoggerX.IMPORTANT.e("这是一条重要错误日志", Throwable("异常信息"))
LoggerX.KERNEL.i("核心业务逻辑日志")
LoggerX.ERROR.w("错误域警告日志")
LoggerX.CATCH.queryLogs(limit = 100)
```

#### 4.2 记录文件日志

```kotlin
val file = File(path)
LoggerX.COMMON.image(file, message = "上传图片日志")
```

#### 4.3 开启崩溃捕获并接入 Bugly

LoggerX 不依赖 Bugly SDK。先初始化 LoggerX，再初始化 Bugly，最后开启捕获，以确保 LoggerX 的 JVM Handler
能把异常继续转交给 Bugly。

```kotlin
 LoggerX.init(this, catchConfig = CatchConfig(enabled = true))

val strategy = CrashReport.UserStrategy(application)
strategy.crashHandleCallback = object: CrashReport.CrashHandleCallback() {
    override fun onCrashHandleStart(crashType: Int, errorType: String?, errorMessage: String?, errorStack: String?): Map<String?, String?> {
        val source = when (crashType) {
            0 -> CrashSource.BUGLY_JAVA
            1 -> CrashSource.BUGLY_CAUGHT
            2 -> CrashSource.BUGLY_NATIVE
            else -> CrashSource.EXTERNAL
        }
        LoggerX.reportExternalCrash(source, crashType, errorType, errorMessage, errorStack)
        return emptyMap()
    }
}
CrashReport.initCrashReport(application, appId, false, strategy)
```

Native 调用建议通过 `traceNativeCall` 包裹，以便在 SO 崩溃后保留 Java/Kotlin 入口调用链：

```kotlin
LoggerX.traceNativeCall {
    nativeDecode(frame)
}
```

崩溃现场只写入内部应急文件；下次 `LoggerX.init()` 会合并 JVM、Bugly、JNI 调用上下文及 Android 11+ 的
`ApplicationExitInfo`，去重写入 `CATCH_log`。完整报告可在 `PreviewLogActivity` 的 `CATCH` 页签中点击附件图标查看。

#### 4.4 统一监听

`setCrashListener` 用于处理自动恢复或界面手动请求上传的 CATCH 报告；返回 `true` 后记录会标记为已上传。
`setLoggerXListener` 在任意作用域日志成功写入数据库后异步回调，可不设置，默认值为 `null`。

```kotlin
LoggerX.setListener(setCrashListener = { request ->
    uploadCrashReport(request.reportFile, request.eventId)
}, setLoggerXListener = { entry ->
    Log.d("LoggerX", "inserted: scope=${entry.scope}, id=${entry.id}")
})
```

仅监听崩溃上传时可省略日志插入回调：

```kotlin
LoggerX.setListener(setCrashListener = { request -> uploadCrashReport(request.reportFile, request.eventId) })
```

## 5. 注意事项

- **初始化**: 必须在使用任何日志功能前调用 `LoggerX.init()`。
- **线程安全**: LoggerX 内部处理了数据库操作的同步，可以在多线程环境下安全使用。
- **自动清理**: 自动清理任务每 24 小时执行一次，建议在 `init` 后立即开启。
- **文件日志**: 记录文件日志时，LoggerX 会将源文件持久化到内部存储目录中。
- **系统 tombstone**: Android 11+ 会读取系统退出 trace；若系统返回 protobuf tombstone，LoggerX
  会提取可读字段并与 Bugly Native 栈合并。Android 10 及以下无法读取系统 `/data/tombstones`，依赖 Bugly
  回调和 `traceNativeCall` 应急信息。
