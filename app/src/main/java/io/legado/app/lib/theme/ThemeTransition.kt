package io.legado.app.lib.theme

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Interpolator
import io.legado.app.utils.DevicePerformanceUtils
import splitties.init.appCtx
import java.util.WeakHashMap

/**
 * 日间/夜间主题切换的过渡规范与驱动。
 *
 * 为什么需要它：主界面背景与底栏的刷新时机天然不同——底栏色值读自 [ThemeStore]，同步可得；
 * 背景则由 [io.legado.app.base.BaseActivity.upBackgroundImage] 落地，可能来自异步解码回调。
 * 两者若各自硬切换，底栏瞬间变色而背景晚一步才跟上，观感上就是「割裂」。这里把二者
 * 收敛到同一条过渡规范上：
 *
 * - 共用 [durationMs] 给出的时长与同一个插值器（各表面不得自行另设时长，否则节奏立刻不一致）；
 * - 落地表现共用 [crossFade]，背景与底栏逐帧同步推进，不依赖各自的刷新时机巧合对齐；
 * - 起点色跨 Activity 重建保留。主题切换必然伴随重建，重建后控件是全新的、背景为空，
 *   「上一轮显示的是什么颜色」只能在进程级记录（见 [contentTransitionStartColor]）。
 *
 * 降级：系统关闭动画（动画时长缩放为 0）时直接跳变；低内存设备改用更短时长
 * （见 [DURATION_LOW_RAM_MS]），动效开销虽小，也没必要在弱机上维持一整段持续重绘。
 *
 * 维护约束：后续新增需要跟随日夜切换过渡的表面，必须复用本对象，不要自行写动画，
 * 否则又会回到节奏不一致的老问题上。
 */
object ThemeTransition {

    /** 主题切换过渡时长（毫秒）。主界面背景与底栏共用，是「节奏一致」的唯一来源。 */
    const val DURATION_MS = 300L

    /** 低内存设备的过渡时长：缩短持续时间，而非取消过渡（取消会让弱机上的观感明显退化） */
    private const val DURATION_LOW_RAM_MS = 160L

    private val interpolator: Interpolator = AccelerateDecelerateInterpolator()

    /**
     * 主题切换代数。每次真实的日夜翻转自增；各表面记录自己已消费的代数，
     * 用于区分「这次落地属于主题切换」与「配置微调（如拖动底栏不透明度滑条）」——
     * 后者不该播放过渡，否则连续拖动会让动画互相打断。
     */
    @Volatile
    var generation: Int = 0
        private set

    /** 本轮过渡起点：切换前主界面背景的签名。非空表示当时是背景图，起点层从进程级缓存取该图 */
    @Volatile
    var contentTransitionStartSignature: String? = null
        private set

    /** 本轮过渡起点：切换前主界面背景的纯色，仅当当时是纯色底时非空 */
    @Volatile
    var contentTransitionStartColor: Int? = null
        private set

    /** 本轮过渡的起点色：切换前底栏实际显示的颜色 */
    @Volatile
    var bottomBarTransitionStartColor: Int? = null
        private set

    /** 上一轮实际落地的背景，供下一次切换作为过渡起点 */
    @Volatile
    private var lastContentSignature: String? = null

    @Volatile
    private var lastContentColor: Int? = null

    @Volatile
    private var lastBottomBarColor: Int? = null

    /** 视图 → 正在推进的过渡动画。同一视图上重复过渡时取消上一条，避免两条动画抢同一个背景 */
    private val runningAnimators = WeakHashMap<View, Animator>()

    /**
     * 标记一次真实的日夜翻转。
     *
     * 调用方必须保证「同一次翻转只调用一次」：这里会同时把当前落地色快照成过渡起点，
     * 而快照一旦晚于表面的重新落地，取到的就是新颜色，起点与终点相同会导致过渡名存实亡
     * （[io.legado.app.App] 用昼夜位比对过滤掉了分批送达的重复回调）。
     */
    fun notifyThemeChanged() {
        contentTransitionStartSignature = lastContentSignature
        contentTransitionStartColor = lastContentColor
        bottomBarTransitionStartColor = lastBottomBarColor
        generation++
    }

    /**
     * 记录一次实际落地的主界面背景，供下一次切换作为过渡起点。
     * [signature] 非空表示落地的是背景图（此时 [color] 必然为 null）；两者都为 null 表示从未落地过。
     */
    fun rememberContentBackground(signature: String?, color: Int?) {
        lastContentSignature = signature
        lastContentColor = color
    }

    /** 记录一次实际落地的底栏颜色，供下一次切换作为过渡起点 */
    fun rememberBottomBarColor(color: Int) {
        lastBottomBarColor = color
    }

    /**
     * 过渡可用时长。系统关闭动画（开发者选项「动画程序时长缩放」为 0）时返回 0，
     * 调用方应按「直接跳变」处理，不能把 0 当成瞬时动画硬跑一遍。
     */
    fun durationMs(): Long {
        val scale = try {
            Settings.Global.getFloat(
                appCtx.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        } catch (_: Exception) {
            1f
        }
        if (scale <= 0f) return 0L
        return if (DevicePerformanceUtils.isLowRamDevice) DURATION_LOW_RAM_MS else DURATION_MS
    }

    /**
     * 把 [view] 的背景从 [from] 交叉淡化到 [to]，动画结束后换成 [to] 本身，
     * 避免过渡用的包装 Drawable 长期持有旧 Drawable。返回实际设置的背景。
     *
     * [durationMs] 不可用时返回 [to]，由调用方直接落地。
     */
    fun crossFade(
        view: View,
        from: Drawable,
        to: Drawable,
        durationMs: Long = durationMs(),
    ): Drawable {
        runningAnimators.remove(view)?.cancel()
        if (durationMs <= 0L) return to
        val drawable = CrossFadeDrawable(from, to)
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = durationMs
        animator.interpolator = interpolator
        animator.addUpdateListener { drawable.progress = it.animatedFraction }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (runningAnimators[view] === animator) {
                    runningAnimators.remove(view)
                }
                if (view.background === drawable) {
                    view.background = to
                }
            }
        })
        runningAnimators[view] = animator
        // 先以起点色落地，避免动画首帧之前先闪一下新颜色
        view.background = drawable
        animator.start()
        return drawable
    }

    /**
     * 同一轮过渡内该表面再次落地时，只把进行中的交叉淡化换上新的终点层，不重建起点、不重启动画。
     *
     * 必要性：一次主题切换里同一个表面常被落地多次——例如底栏在 `onCreate` 整包应用后，
     * 首个 insets 回调（内边距由 0 变为真实值）会再整包应用一次。若第二次直接换上新背景，
     * 刚起步的过渡会被当场打断，底栏又回到"瞬变"。
     *
     * @return true 表示已接管（调用方应保留当前背景，不要再赋值）
     */
    fun updateCrossFadeTarget(view: View, to: Drawable): Boolean {
        if (runningAnimators[view]?.isRunning != true) return false
        val drawable = view.background as? CrossFadeDrawable ?: return false
        drawable.to = to
        return true
    }
}

/**
 * 由外部进度驱动的两层交叉淡化 Drawable。
 *
 * 不用 [android.graphics.drawable.TransitionDrawable]：它的进度只能由自身帧率相关的计时器
 * 推进，无法与其它表面共用同一条时间轴；而主题过渡要求背景与底栏逐帧同步，
 * 各自的计时器一旦启动时刻不同就会看出错位。
 */
class CrossFadeDrawable(
    private val from: Drawable,
    to: Drawable,
) : Drawable() {

    /** 终点层。同一轮过渡内该表面再次落地时会被替换（如底栏内边距变化），起点与进度保持不变 */
    var to: Drawable = to

    /** 0 = 完全显示 [from]，1 = 完全显示 [to] */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val current = progress
        if (current < 1f) {
            from.alpha = ((1f - current) * 255).toInt()
            from.setBounds(bounds)
            from.draw(canvas)
        }
        if (current > 0f) {
            to.alpha = (current * 255).toInt()
            to.setBounds(bounds)
            to.draw(canvas)
        }
    }

    override fun setAlpha(alpha: Int) {
        from.alpha = alpha
        to.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        from.colorFilter = colorFilter
        to.colorFilter = colorFilter
    }

    // getOpacity 在 API 30 起被标记为废弃，但 Drawable 的绘制仍依赖它给出透明度，
    // 这里必须覆写（两层叠加必然是半透明），因此显式压制该诊断
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = to.intrinsicWidth

    override fun getIntrinsicHeight(): Int = to.intrinsicHeight

    override fun getPadding(padding: Rect): Boolean = to.getPadding(padding)
}
