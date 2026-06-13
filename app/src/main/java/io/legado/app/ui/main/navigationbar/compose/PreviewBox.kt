package io.legado.app.ui.main.navigationbar.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.LayoutMode
import io.legado.app.data.entities.MaterialMode
import io.legado.app.data.entities.NavigationBarConfig

/**
 * 预览效果组件
 *
 * 显示底栏方案的视觉效果预览
 *
 * @param config 导航栏配置
 * @param modifier 修饰符
 */
@Composable
fun PreviewBox(
    config: NavigationBarConfig,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "预览效果",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 预览容器 - 模拟屏幕
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // 模拟内容区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .height(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
            )

            // 底栏预览
            NavigationBarPreview(
                config = config,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * 底栏预览组件
 *
 * 根据配置显示不同的布局模式和材质效果
 */
@Composable
private fun NavigationBarPreview(
    config: NavigationBarConfig,
    modifier: Modifier = Modifier
) {
    val barHeight = 48.dp
    val cornerRadius = if (config.layoutMode == LayoutMode.FLOATING) 16.dp else 0.dp

    val barModifier = when (config.layoutMode) {
        LayoutMode.FIXED -> modifier
            .fillMaxWidth()
            .height(barHeight)
        LayoutMode.FLOATING -> modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(barHeight)
            .shadow(4.dp, RoundedCornerShape(cornerRadius))
            .clip(RoundedCornerShape(cornerRadius))
    }

    Box(
        modifier = barModifier
            .then(
                when (config.materialMode) {
                    MaterialMode.SOLID -> Modifier.background(
                        getSolidBackgroundColor(config.isNightMode, config.opacity)
                    )
                    MaterialMode.GLASS -> Modifier.background(
                        getGlassBackgroundColor(config.isNightMode, config.opacity)
                    )
                    MaterialMode.FROSTED -> Modifier.background(
                        getFrostedBackgroundColor(config.isNightMode, config.opacity)
                    )
                }
            )
            .then(
                if (config.borderOpacity > 0) {
                    Modifier.border(
                        width = 1.dp,
                        color = Color(config.borderColor)
                            .copy(alpha = config.borderOpacity / 100f),
                        shape = RoundedCornerShape(cornerRadius)
                    )
                } else {
                    Modifier
                }
            )
    ) {
        // 模拟导航栏内容
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 模拟图标按钮
            repeat(4) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (index == 0) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                } else {
                                    Color.Transparent
                                }
                            )
                    )
                }
            }
        }
    }
}

/**
 * 获取实心背景颜色
 */
@Composable
private fun getSolidBackgroundColor(isNightMode: Boolean, opacity: Int): Color {
    val baseColor = if (isNightMode) {
        Color(0xFF1A1A1A)
    } else {
        Color(0xFFFFFFFF)
    }
    return baseColor.copy(alpha = opacity / 100f)
}

/**
 * 获取玻璃背景颜色
 */
@Composable
private fun getGlassBackgroundColor(isNightMode: Boolean, opacity: Int): Color {
    val baseColor = if (isNightMode) {
        Color(0xFF2D2D2D)
    } else {
        Color(0xFFF5F5F5)
    }
    return baseColor.copy(alpha = (opacity * 0.7f / 100f).coerceIn(0f, 1f))
}

/**
 * 获取磨砂背景颜色
 */
@Composable
private fun getFrostedBackgroundColor(isNightMode: Boolean, opacity: Int): Color {
    val baseColor = if (isNightMode) {
        Color(0xFF3D3D3D)
    } else {
        Color(0xFFE8E8E8)
    }
    return baseColor.copy(alpha = (opacity * 0.5f / 100f).coerceIn(0f, 1f))
}