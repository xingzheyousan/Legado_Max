package io.legado.app.model

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.qmdeve.liquidglass.widget.LiquidGlassView
import io.legado.app.R
import io.legado.app.data.entities.LayoutMode
import io.legado.app.data.entities.MaterialMode
import io.legado.app.data.entities.NavigationBarConfig
import io.legado.app.databinding.ActivityMainBinding
import io.legado.app.utils.dpToPx

/**
 * 底栏液态玻璃效果应用器
 *
 * 采用动态叠加方案：
 * - FIXED 模式：不修改底栏任何属性，保持原始默认样子
 * - FLOATING 模式：底栏背景设透明 + 添加 margin + 叠加效果层
 *
 * 叠加层 z-order（从下到上）：
 * 1. LiquidGlassView — 折射/模糊渲染
 * 2. overlay View — 色调 + 边框（盖在玻璃效果上）
 * 3. LinearLayout — 底栏图标（最上层可点击）
 *
 * 材质模式差异：
 * - GLASS：有折射 + 中等模糊 + 轻色调 + 色散
 * - FROSTED：无折射 + 强模糊 + 重白色调 + 无色散
 */
object NavigationBarEffectApplier {

    private const val TAG_OVERLAY = "navigation_bar_overlay"
    private const val TAG_GLASS_VIEW = "navigation_bar_glass_view"

    /** 保存底栏原始背景（深拷贝） */
    private var originalBackground: android.graphics.drawable.Drawable? = null
    /** 保存底栏原始 margin（值拷贝，非引用） */
    private var originalNavMargins: IntArray? = null

    fun applyEffect(config: NavigationBarConfig, binding: ActivityMainBinding) {
        if (config.materialMode == MaterialMode.SOLID) {
            removeOverlay(binding)
            return
        }

        // FIXED 模式：不修改底栏，保持原始默认样子
        if (config.layoutMode == LayoutMode.FIXED) {
            removeOverlay(binding)
            return
        }

        // FLOATING 模式：应用玻璃效果
        val useFallback = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        if (useFallback) {
            addOverlayOnly(binding, config)
        } else {
            addOverlay(binding, config)
        }
    }

    private fun addOverlay(
        binding: ActivityMainBinding,
        config: NavigationBarConfig
    ) {
        val rootView = binding.root as? ViewGroup ?: return

        val existingGlass = rootView.findViewWithTag<LiquidGlassView>(TAG_GLASS_VIEW)
        val existingOverlay = rootView.findViewWithTag<View>(TAG_OVERLAY)

        if (existingGlass != null && existingOverlay != null) {
            updateOverlay(existingGlass, existingOverlay, binding, config)
            return
        }

        existingGlass?.let { rootView.removeView(it) }
        existingOverlay?.let { rootView.removeView(it) }

        val navView = binding.bottomNavigationView

        // 保存原始状态（值拷贝，非引用）
        saveOriginalState(navView)

        // 底栏背景设为透明，让玻璃效果透出
        navView.background = null

        // 悬浮模式：给底栏加 margin
        val margin = 16.dpToPx()
        val lp = navView.layoutParams as? LinearLayout.LayoutParams
        if (lp != null) {
            lp.setMargins(margin, 0, margin, margin)
            navView.layoutParams = lp
        }

        // z-order: index 0 = 最底层
        // 第一层：LiquidGlassView（折射/模糊渲染）
        val glassView = createGlassView(navView, binding, config)
        rootView.addView(glassView, 0)

        // 第二层：overlay View（色调 + 边框，盖在玻璃效果上）
        val overlay = createOverlayView(navView, config)
        rootView.addView(overlay, 1)

        // 悬浮模式的 margin
        applyMargins(overlay, glassView, margin)
    }

    private fun addOverlayOnly(binding: ActivityMainBinding, config: NavigationBarConfig) {
        val rootView = binding.root as? ViewGroup ?: return

        rootView.findViewWithTag<LiquidGlassView>(TAG_GLASS_VIEW)?.let {
            rootView.removeView(it)
        }

        val existingOverlay = rootView.findViewWithTag<View>(TAG_OVERLAY)
        val navView = binding.bottomNavigationView

        saveOriginalState(navView)
        navView.background = null

        val margin = 16.dpToPx()
        val lp = navView.layoutParams as? LinearLayout.LayoutParams
        if (lp != null) {
            lp.setMargins(margin, 0, margin, margin)
            navView.layoutParams = lp
        }

        if (existingOverlay != null) {
            updateOverlayView(existingOverlay, navView, config)
            val overlayLp = existingOverlay.layoutParams as? FrameLayout.LayoutParams
            if (overlayLp != null) {
                overlayLp.setMargins(margin, 0, margin, margin)
                existingOverlay.layoutParams = overlayLp
            }
        } else {
            val overlay = createOverlayView(navView, config)
            rootView.addView(overlay, 0)
            val overlayLp = overlay.layoutParams as? FrameLayout.LayoutParams
            if (overlayLp != null) {
                overlayLp.setMargins(margin, 0, margin, margin)
                overlay.layoutParams = overlayLp
            }
        }
    }

    private fun saveOriginalState(navView: View) {
        if (originalBackground == null) {
            originalBackground = navView.background?.constantState?.newDrawable()?.mutate()
                ?: navView.background
        }
        if (originalNavMargins == null) {
            val lp = navView.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                originalNavMargins = intArrayOf(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
            }
        }
    }

    /**
     * 创建 overlay View
     *
     * GLASS 模式：轻色调（半透明白），让折射效果透出
     * FROSTED 模式：重色调（更不透明的白），模拟磨砂玻璃
     */
    private fun createOverlayView(navView: View, config: NavigationBarConfig): View {
        return View(navView.context).apply {
            tag = TAG_OVERLAY
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            isClickable = false
            isFocusable = false
            navView.post {
                val navHeight = navView.height
                if (navHeight > 0) {
                    layoutParams.height = navHeight
                    this.layoutParams = layoutParams
                }
            }
            background = createOverlayDrawable(config)
        }
    }

    /**
     * 创建 overlay drawable
     *
     * GLASS：轻色调渐变（顶部 20% 白 → 底部 40% 白），让折射效果主导
     * FROSTED：重色调渐变（顶部 50% 白 → 底部 80% 白），磨砂感
     */
    private fun createOverlayDrawable(config: NavigationBarConfig): GradientDrawable {
        val cornerRadius = 24f.dpToPx().toFloat()

        // 根据材质模式决定色调强度
        val (topAlpha, bottomAlpha) = when (config.materialMode) {
            MaterialMode.GLASS -> {
                // 玻璃：轻色调，让折射效果透出
                val base = (config.opacity / 100f * 0.2f * 255).toInt().coerceIn(0, 255)
                Pair(base, (base * 2f).toInt().coerceAtMost(255))
            }
            MaterialMode.FROSTED -> {
                // 磨砂：重色调，模拟磨砂白
                val base = (config.opacity / 100f * 0.5f * 255).toInt().coerceIn(0, 255)
                Pair(base, (base * 1.6f).toInt().coerceAtMost(255))
            }
            else -> {
                // SOLID 不应到这里
                Pair(0, 0)
            }
        }

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius

            colors = intArrayOf(
                (topAlpha shl 24) or 0xFFFFFF,
                (bottomAlpha shl 24) or 0xFFFFFF
            )
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TOP_BOTTOM

            val borderAlpha = (config.borderOpacity / 100f * 255).toInt()
            val borderColorWithAlpha = (borderAlpha shl 24) or (config.borderColor and 0x00FFFFFF)
            setStroke(2.dpToPx(), borderColorWithAlpha)
        }
    }

    private fun createGlassView(
        navView: View,
        binding: ActivityMainBinding,
        config: NavigationBarConfig
    ): LiquidGlassView {
        return LiquidGlassView(navView.context).apply {
            tag = TAG_GLASS_VIEW
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            isClickable = false
            isFocusable = false
            navView.post {
                val navHeight = navView.height
                if (navHeight > 0) {
                    layoutParams.height = navHeight
                    this.layoutParams = layoutParams
                }
            }
            setupGlassView(this, binding, config)
        }
    }

    private fun updateOverlay(
        glassView: LiquidGlassView,
        overlay: View,
        binding: ActivityMainBinding,
        config: NavigationBarConfig
    ) {
        setupGlassView(glassView, binding, config)
        updateOverlayView(overlay, binding.bottomNavigationView, config)

        val margin = 16.dpToPx()

        val navLp = binding.bottomNavigationView.layoutParams as? LinearLayout.LayoutParams
        if (navLp != null) {
            navLp.setMargins(margin, 0, margin, margin)
            binding.bottomNavigationView.layoutParams = navLp
        }

        applyMargins(overlay, glassView, margin)
    }

    private fun applyMargins(overlay: View, glassView: LiquidGlassView, margin: Int) {
        val overlayLp = overlay.layoutParams as? FrameLayout.LayoutParams
        if (overlayLp != null) {
            overlayLp.setMargins(margin, 0, margin, margin)
            overlay.layoutParams = overlayLp
        }
        val glassLp = glassView.layoutParams as? FrameLayout.LayoutParams
        if (glassLp != null) {
            glassLp.setMargins(margin, 0, margin, margin)
            glassView.layoutParams = glassLp
        }
    }

    private fun updateOverlayView(overlay: View, navView: View, config: NavigationBarConfig) {
        overlay.background = createOverlayDrawable(config)
        navView.post {
            val navHeight = navView.height
            if (navHeight > 0) {
                overlay.layoutParams.height = navHeight
                overlay.layoutParams = overlay.layoutParams
            }
        }
    }

    private fun removeOverlay(binding: ActivityMainBinding) {
        val rootView = binding.root as? ViewGroup ?: return
        val navView = binding.bottomNavigationView

        rootView.findViewWithTag<LiquidGlassView>(TAG_GLASS_VIEW)?.let {
            rootView.removeView(it)
        }

        rootView.findViewWithTag<View>(TAG_OVERLAY)?.let {
            rootView.removeView(it)
        }

        originalBackground?.let {
            navView.background = it
        }

        originalNavMargins?.let { margins ->
            val lp = navView.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                lp.setMargins(margins[0], margins[1], margins[2], margins[3])
                navView.layoutParams = lp
            }
        }
    }

    /**
     * 配置 LiquidGlassView
     *
     * GLASS 模式：有折射 + 中等模糊 + 色散 → 看到内容扭曲的玻璃感
     * FROSTED 模式：无折射 + 强模糊 + 无色散 → 看到模糊的内容
     */
    private fun setupGlassView(
        glassView: LiquidGlassView,
        binding: ActivityMainBinding,
        config: NavigationBarConfig
    ) {
        try {
            glassView.visibility = View.VISIBLE

            val linearLayout = binding.bottomNavigationView.parent as? ViewGroup
            if (linearLayout != null) {
                glassView.bind(linearLayout)
            }

            glassView.setCornerRadius(24f.dpToPx().toFloat())

            when (config.materialMode) {
                MaterialMode.GLASS -> {
                    // 玻璃：有折射，中等模糊，可见色散
                    glassView.setRefractionHeight(20f.dpToPx().toFloat())  // 折射高度 20dp
                    glassView.setRefractionOffset(70f.dpToPx().toFloat())  // 折射偏移 70dp
                    glassView.setBlurRadius(8f.dpToPx().toFloat())         // 中等模糊
                    glassView.setDispersion(0.4f)                          // 明显色散
                    glassView.setTintAlpha(0.08f)                          // 极轻色调
                }
                MaterialMode.FROSTED -> {
                    // 磨砂：无折射，强模糊，无色散
                    glassView.setRefractionHeight(0f)                      // 无折射
                    glassView.setRefractionOffset(0f)
                    glassView.setBlurRadius(25f.dpToPx().toFloat())        // 强模糊
                    glassView.setDispersion(0f)                            // 无色散
                    glassView.setTintAlpha(0.15f)                          // 轻色调
                }
                else -> {
                    // SOLID 不应到这里
                    glassView.setRefractionHeight(0f)
                    glassView.setBlurRadius(0f)
                    glassView.setTintAlpha(0f)
                }
            }

            glassView.setDraggableEnabled(false)
            glassView.setElasticEnabled(false)
            glassView.setTouchEffectEnabled(false)
            glassView.invalidate()
        } catch (e: Exception) {
            glassView.visibility = View.GONE
        }
    }
}
