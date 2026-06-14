package io.legado.app.utils

import android.os.Build
import androidx.core.view.WindowInsetsCompat

/**
 * 获取导航栏（三键/手势条）的准确高度。
 *
 * 优先使用 [WindowInsetsCompat.Type.navigationBars]（Android 10+ 原生手势导航 API），
 * 回退到 [WindowInsetsCompat.Type.systemBars]（兼容旧设备）。
 *
 * 小米 MIUI/HyperOS 在手势导航模式下，Type.systemBars().bottom 可能返回 0，
 * Type.navigationBars() 能更准确地反映手势条高度。
 */
val WindowInsetsCompat.navigationBarHeight
    get(): Int {
        val navBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        } else {
            getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        }
        return (navBottom - imeHeight).coerceAtLeast(0)
    }

val WindowInsetsCompat.imeHeight
    get() = getInsets(WindowInsetsCompat.Type.ime()).bottom
