package io.legado.app.utils

import android.app.ActivityManager
import android.os.Build
import splitties.init.appCtx

/**
 * 设备性能检测工具，用于判断当前设备是否适合实时玻璃模糊效果。
 *
 * 实时玻璃/磨砂效果需要持续采样整页内容并做高斯模糊，在旧系统或低内存设备上
 * 会造成严重的渲染卡顿和电量消耗。本工具综合以下因素判断设备性能等级：
 * - Android 版本（API 33/Android 13 以下视为低性能优先）
 * - 低 RAM 标志（[ActivityManager.isLowRamDevice]）
 * - 可用内存占总量比例
 * - CPU 核心数
 */
object DevicePerformanceUtils {

    private val activityManager: ActivityManager
        get() = appCtx.getSystemService(ActivityManager::class.java)

    /**
     * 实时玻璃效果最低 API 级别。
     *
     * 必须与 LiquidGlass 库的实际能力对齐：其折射着色器基于 AGSL RuntimeShader，
     * 库内部在 API < 33（Tiramisu）时不创建渲染实现、视图不绘制任何内容。
     * 若门槛低于 33，Android 12 设备会误入"实时玻璃"路径——采样视图空转，
     * 仅剩一层极淡的静态薄纱，纯色背景下底栏几乎没有玻璃/磨砂观感；
     * 对齐到 33 后这些设备会改走完整的多层静态玻璃材质兜底。
     */
    private const val MIN_API_FOR_REALTIME_GLASS = Build.VERSION_CODES.TIRAMISU // API 33 / Android 13

    /** 最低推荐 CPU 核心数 */
    private const val MIN_CPU_CORES = 4

    /** 最低可用内存比例（可用 / 总量） */
    private const val MIN_AVAILABLE_MEMORY_RATIO = 0.15f

    /** 最低总内存（MB），低于此值视为低内存设备 */
    private const val MIN_TOTAL_MEMORY_MB = 2048L

    /**
     * 是否支持实时玻璃模糊效果。
     *
     * 判断条件（全部满足才返回 true）：
     * 1. API >= 33（Android 13+，LiquidGlass 库的 AGSL 折射着色器仅在 33+ 生效）
     * 2. 非低 RAM 设备
     * 3. CPU 核心数 >= 4
     * 4. 总内存 >= 2GB
     * 5. 当前可用内存占比 >= 15%
     *
     * 结果在进程生命周期内缓存。
     */
    @Volatile
    private var cachedSupportsRealtimeGlass: Boolean? = null

    val supportsRealtimeGlass: Boolean
        get() {
            cachedSupportsRealtimeGlass?.let { return it }
            val result = evaluateRealtimeGlassSupport()
            cachedSupportsRealtimeGlass = result
            return result
        }

    /**
     * 是否为低内存设备。
     *
     * 供主题切换过渡等「纯装饰性」动效降级使用：这类动效不是功能必需，
     * 在弱机上不必维持与高端机等长的持续重绘（见 [io.legado.app.lib.theme.ThemeTransition]）。
     */
    val isLowRamDevice: Boolean
        get() = activityManager.isLowRamDevice

    private fun evaluateRealtimeGlassSupport(): Boolean {
        // 1. API 级别检查
        if (Build.VERSION.SDK_INT < MIN_API_FOR_REALTIME_GLASS) {
            return false
        }

        // 2. 低 RAM 设备检查
        if (activityManager.isLowRamDevice) {
            return false
        }

        // 3. CPU 核心数
        if (Runtime.getRuntime().availableProcessors() < MIN_CPU_CORES) {
            return false
        }

        // 4. 内存检查
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val totalMemoryMb = info.totalMem / (1024 * 1024)
        if (totalMemoryMb < MIN_TOTAL_MEMORY_MB) {
            return false
        }

        // 5. 当前可用内存比例
        val availableRatio = if (info.totalMem > 0) {
            info.availMem.toFloat() / info.totalMem.toFloat()
        } else {
            1f
        }
        return availableRatio >= MIN_AVAILABLE_MEMORY_RATIO
    }

    /**
     * 重新评估当前可用内存是否足够支持实时玻璃效果。
     *
     * 与 [supportsRealtimeGlass] 不同，此方法不使用缓存，
     * 每次调用都会读取最新的内存信息，适用于在运行时动态降级的场景。
     */
    fun hasEnoughMemoryForRealtimeGlass(): Boolean {
        if (activityManager.isLowRamDevice) return false
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val availableRatio = if (info.totalMem > 0) {
            info.availMem.toFloat() / info.totalMem.toFloat()
        } else {
            1f
        }
        return availableRatio >= MIN_AVAILABLE_MEMORY_RATIO
    }
}
