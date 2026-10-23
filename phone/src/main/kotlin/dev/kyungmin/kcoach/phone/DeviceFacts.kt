package dev.kyungmin.kcoach.phone

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import org.json.JSONObject

/**
 * Device and memory facts that separate a memory kill, a platform/preview-build problem and an app bug.
 * Nothing here identifies the user or a workout; every read is guarded so diagnostics can never crash the app.
 */
internal object DeviceFacts {
    private const val MB = 1024L * 1024L

    /** Written once per launch: which OS build, whether it is a preview, and how the system treats this app. */
    fun snapshot(context: Context): JSONObject {
        val facts = JSONObject()
        fun put(name: String, value: () -> Any?) { runCatching { value() }.getOrNull()?.let { facts.put(name, it) } }
        put("fingerprint") { Build.FINGERPRINT.take(160) }
        put("display") { Build.DISPLAY.take(96) }
        put("incremental") { Build.VERSION.INCREMENTAL.take(64) }
        put("codename") { Build.VERSION.CODENAME }
        put("previewSdk") { Build.VERSION.PREVIEW_SDK_INT }
        put("securityPatch") { Build.VERSION.SECURITY_PATCH }
        put("buildType") { Build.TYPE }
        put("debuggableApp") { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
        val am = context.getSystemService(ActivityManager::class.java)
        put("memoryClassMb") { am.memoryClass }
        put("largeMemoryClassMb") { am.largeMemoryClass }
        put("lowRamDevice") { am.isLowRamDevice }
        put("backgroundRestricted") { am.isBackgroundRestricted }
        put("standbyBucket") {
            context.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket
        }
        val power = context.getSystemService(PowerManager::class.java)
        put("powerSave") { power.isPowerSaveMode }
        put("ignoringBatteryOptimizations") { power.isIgnoringBatteryOptimizations(context.packageName) }
        put("memory") { memory(context) }
        return facts
    }

    /** Taken at crash time and on memory-pressure callbacks. */
    fun memory(context: Context?): JSONObject {
        val memory = JSONObject()
        fun put(name: String, value: () -> Any?) { runCatching { value() }.getOrNull()?.let { memory.put(name, it) } }
        val runtime = Runtime.getRuntime()
        put("heapUsedMb") { (runtime.totalMemory() - runtime.freeMemory()) / MB }
        put("heapMaxMb") { runtime.maxMemory() / MB }
        put("nativeHeapMb") { Debug.getNativeHeapAllocatedSize() / MB }
        if (context != null) {
            put("system") {
                val info = ActivityManager.MemoryInfo()
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
                JSONObject().put("availMb", info.availMem / MB).put("totalMb", info.totalMem / MB)
                    .put("thresholdMb", info.threshold / MB).put("low", info.lowMemory)
            }
            put("thermal") {
                if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java).currentThermalStatus else null
            }
        }
        return memory
    }
}
