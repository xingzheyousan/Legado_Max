package io.legado.app.ui.main.navigationbar.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.LayoutMode
import io.legado.app.data.entities.MaterialMode
import io.legado.app.data.entities.NavigationBarConfig

/**
 * 编辑面板组件
 *
 * 提供方案编辑功能
 *
 * @param config 导航栏配置
 * @param isNightMode 是否为夜间模式
 * @param onConfigChange 配置变更回调
 * @param onSave 保存回调
 * @param onCancel 取消回调
 * @param modifier 修饰符
 */
@Composable
fun EditPanel(
    config: NavigationBarConfig,
    isNightMode: Boolean,
    onConfigChange: (NavigationBarConfig) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember(config.name) { mutableStateOf(config.name) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 标题
            Text(
                text = if (isNightMode) "编辑夜间方案" else "编辑日间方案",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 方案名称输入框
            OutlinedTextField(
                value = name,
                onValueChange = { newName ->
                    name = newName
                    onConfigChange(config.copy(name = newName))
                },
                label = { Text("方案名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 布局模式选择
            ConfigSection(title = "布局模式") {
                LayoutModeSelector(
                    selectedMode = config.layoutMode,
                    onModeSelected = { newMode ->
                        onConfigChange(config.copy(layoutMode = newMode))
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 材质模式选择
            ConfigSection(title = "材质模式") {
                MaterialModeSelector(
                    selectedMode = config.materialMode,
                    onModeSelected = { newMode ->
                        onConfigChange(config.copy(materialMode = newMode))
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 不透明度滑块（实心模式禁用）
            if (config.materialMode != MaterialMode.SOLID) {
                ConfigSection(title = "不透明度 (${config.opacity}%)") {
                    Slider(
                        value = config.opacity.toFloat(),
                        onValueChange = { newValue ->
                            onConfigChange(config.copy(opacity = newValue.toInt()))
                        },
                        valueRange = 0f..100f,
                        steps = 20,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // 边框颜色选择器
            ConfigSection(title = "边框颜色") {
                BorderColorSelector(
                    selectedColor = config.borderColor,
                    onColorSelected = { newColor ->
                        onConfigChange(config.copy(borderColor = newColor))
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 边框透明度滑块
            ConfigSection(title = "边框透明度 (${config.borderOpacity}%)") {
                Slider(
                    value = config.borderOpacity.toFloat(),
                    onValueChange = { newValue ->
                        onConfigChange(config.copy(borderOpacity = newValue.toInt()))
                    },
                    valueRange = 0f..100f,
                    steps = 20,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) {
                    Text("取消")
                }

                Spacer(modifier = Modifier.width(8.dp))

                TextButton(onClick = onSave) {
                    Text("保存")
                }
            }
        }
    }
}

/**
 * 配置区块
 */
@Composable
private fun ConfigSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        content()
    }
}

/**
 * 布局模式选择器
 */
@Composable
private fun LayoutModeSelector(
    selectedMode: LayoutMode,
    onModeSelected: (LayoutMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LayoutMode.values().forEach { mode ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onModeSelected(mode) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedMode == mode,
                    onClick = { onModeSelected(mode) }
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = mode.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 材质模式选择器
 */
@Composable
private fun MaterialModeSelector(
    selectedMode: MaterialMode,
    onModeSelected: (MaterialMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MaterialMode.values().forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModeSelected(mode) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedMode == mode,
                    onClick = { onModeSelected(mode) }
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = mode.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 材质效果预览
                Box(
                    modifier = Modifier
                        .size(32.dp, 16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .then(
                            when (mode) {
                                MaterialMode.SOLID -> Modifier.background(
                                    MaterialTheme.colorScheme.surfaceVariant
                                )
                                MaterialMode.GLASS -> Modifier.background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                )
                                MaterialMode.FROSTED -> Modifier.background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(4.dp)
                        )
                )
            }
        }
    }
}

/**
 * 边框颜色选择器
 */
@Composable
private fun BorderColorSelector(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 预设颜色列表
    val presetColors = listOf(
        0xFFE7EEF5.toInt(), // 默认浅色
        0xFF42A5F5.toInt(), // 蓝色
        0xFF66BB6A.toInt(), // 绿色
        0xFFFFA726.toInt(), // 橙色
        0xFFEF5350.toInt(), // 红色
        0xFFAB47BC.toInt(), // 紫色
        0xFFFFFFFF.toInt(), // 白色
        0xFF000000.toInt()  // 黑色
    )

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(presetColors) { color ->
            ColorItem(
                color = Color(color),
                isSelected = color == selectedColor,
                onClick = { onColorSelected(color) }
            )
        }
    }
}

/**
 * 颜色选项
 */
@Composable
private fun ColorItem(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = CircleShape
                    )
                }
            )
            .clickable { onClick() }
    )
}