# 底栏液态玻璃效果功能设计规格

## 概述

为 Legado Plus 项目实现底部导航栏液态玻璃效果，提供用户自定义能力，支持方案管理和日间/夜间模式分组。

---

## 1. 整体架构

### 三层结构

**数据层（Data Layer）**
- `NavigationBarConfig`：方案配置数据类（布局模式、材质模式、透明度、边框等）
- `NavigationBarEntry`：方案条目（配置 + 来源 + 目录名）
- `AppConfig` 扩展：存储当前激活的日间/夜间方案名
- 使用 SharedPreferences 持久化

**业务层（Business Layer）**
- `NavigationBarManager`：方案管理器（加载、保存、应用方案）
- `NavigationBarEffectApplier`：效果应用器（根据配置动态设置 LiquidGlassView 参数）
- 事件通知：通过 LiveEventBus 发送 `NAVIGATION_BAR_CHANGED` 事件

**界面层（UI Layer）**
- `MainActivity`：主界面，响应配置变化，应用效果
- `NavigationBarManageActivity`：管理界面（Compose），提供方案列表、编辑、切换功能

### 数据流向

```
用户操作 → 管理界面 → NavigationBarManager → AppConfig → 
LiveEventBus → MainActivity → NavigationBarEffectApplier → LiquidGlassView
```

---

## 2. 数据结构设计

### 核心数据类

**NavigationBarConfig（方案配置）**

```kotlin
data class NavigationBarConfig(
    var name: String,                    // 方案名称
    var isNightMode: Boolean,            // 是否夜间模式方案
    var layoutMode: LayoutMode,          // 布局模式：FIXED（固定）/ FLOATING（悬浮）
    var materialMode: MaterialMode,      // 材质模式：SOLID（实心）/ GLASS（玻璃）/ FROSTED（磨砂）
    var opacity: Int,                    // 不透明度 0-100（实心模式不可调）
    var borderColor: Int,                // 边框颜色（ARGB）
    var borderOpacity: Int               // 边框透明度 0-100
)

enum class LayoutMode { FIXED, FLOATING }
enum class MaterialMode { SOLID, GLASS, FROSTED }
```

**NavigationBarEntry（方案条目）**

```kotlin
data class NavigationBarEntry(
    val config: NavigationBarConfig,
    val source: Source,                  // 来源：BUILTIN（内置）/ LOCAL（本地）
    val dirName: String                  // 方案目录名，"default" 为默认方案
)

enum class Source { BUILTIN, LOCAL }
```

**AppConfig 扩展（存储激活方案）**

```kotlin
// 在 AppConfig 中添加
var activeNavigationBarDay: String
    get() = appCtx.getPrefString(PreferKey.navigationBarPackageDay, "default")
    set(value) { appCtx.putPrefString(PreferKey.navigationBarPackageDay, value) }

var activeNavigationBarNight: String
    get() = appCtx.getPrefString(PreferKey.navigationBarPackageNight, "default")
    set(value) { appCtx.putPrefString(PreferKey.navigationBarPackageNight, value) }
```

### 存储策略

- 方案配置以 JSON 格式存储在 SharedPreferences
- 每个方案有唯一的 `dirName` 作为标识
- 日间/夜间方案分别存储，切换主题时自动应用对应方案

---

## 3. 布局结构与效果实现

### MainActivity 布局改造

**当前布局**（activity_main.xml）

```xml
<ThemeBottomNavigationVIew
    android:id="@+id/bottom_navigation_view"
    android:background="@color/background" />
```

**改造后布局**（三层叠加结构）

```xml
<FrameLayout
    android:id="@+id/bottom_navigation_container"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:elevation="8dp">
    
    <!-- 第一层：玻璃折射效果（仅在玻璃/磨砂模式显示） -->
    <com.qmdeve.liquidglass.widget.LiquidGlassView
        android:id="@+id/bottom_navigation_glass_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />
    
    <!-- 第二层：半透明渐变 overlay（增强质感） -->
    <View
        android:id="@+id/bottom_navigation_overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="@drawable/bg_navigation_overlay" />
    
    <!-- 第三层：实际内容 -->
    <io.legado.app.lib.theme.view.ThemeBottomNavigationVIew
        android:id="@+id/bottom_navigation_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="@android:color/transparent" />
    
</FrameLayout>
```

### 效果应用逻辑

**NavigationBarEffectApplier 核心方法**

```kotlin
fun applyEffect(config: NavigationBarConfig, binding: ActivityMainBinding) {
    when (config.materialMode) {
        SOLID -> {
            // 实心模式：隐藏 LiquidGlassView，使用纯色背景
            binding.bottomNavigationGlassView.visibility = View.GONE
            binding.bottomNavigationOverlay.background = createSolidDrawable(config.borderColor)
            binding.bottomNavigationContainer.elevation = 0f
        }
        GLASS -> {
            // 玻璃模式：启用 LiquidGlassView 全参数
            setupGlassView(binding, config, refractionHeight = config.opacity * 1.06f)
        }
        FROSTED -> {
            // 磨砂模式：仅模糊，无折射
            setupGlassView(binding, config, refractionHeight = 0f)
        }
    }
    
    // 应用布局模式（固定/悬浮）
    applyLayoutMode(config.layoutMode, binding)
}
```

### 关键参数映射

- `opacity` → `tintAlpha`（着色透明度）
- `borderColor` + `borderOpacity` → overlay 的 stroke 颜色
- `layoutMode` → 控制容器的外边距和圆角

---

## 4. 管理界面设计（Compose）

### NavigationBarManageActivity 界面结构

**整体布局**

```
顶部标题栏
├─ 返回按钮
├─ 标题："底栏管理"
└─ 添加方案按钮

Tab 栏
├─ "日间方案" Tab
├─ "夜间方案" Tab

当前 Tab 下的方案列表（LazyColumn）
├─ 方案卡片 1（显示名称、材质、布局、预览效果）
├─ 方案卡片 2
└─ ...

编辑面板（底部弹出或侧边展开）
├─ 方案名称输入框
├─ 布局模式选择（固定/悬浮）
├─ 材质模式选择（实心/玻璃/磨砂）
├─ 不透明度滑块（实心模式禁用）
├─ 边框颜色选择器
├─ 边框透明度滑块
└─ 保存/取消按钮
```

### 核心组件设计

**TabLayout（Tab 栏）**

```kotlin
@Composable
fun TabLayout(
    selectedTab: Int,  // 0: 日间, 1: 夜间
    onTabChange: (Int) -> Unit
) {
    TabRow(selectedTabIndex = selectedTab) {
        Tab(
            selected = selectedTab == 0,
            onClick = { onTabChange(0) },
            text = { Text("日间方案") }
        )
        Tab(
            selected = selectedTab == 1,
            onClick = { onTabChange(1) },
            text = { Text("夜间方案") }
        )
    }
}
```

**SchemeCard（方案卡片）**

```kotlin
@Composable
fun SchemeCard(
    entry: NavigationBarEntry,
    isActive: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = if (isActive) 8.dp else 2.dp,
        border = if (isActive) BorderStroke(2.dp, MaterialTheme.colors.primary) else null
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            // 左侧：预览效果（小型底栏预览）
            PreviewBox(entry.config)
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // 右侧：方案信息
            Column {
                Text(
                    text = entry.config.name,
                    style = MaterialTheme.typography.h6
                )
                Text(
                    text = "${entry.config.materialMode.displayName} | ${entry.config.layoutMode.displayName}",
                    style = MaterialTheme.typography.body2
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 操作按钮
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                    if (entry.source != Source.BUILTIN) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
        }
    }
}
```

**EditPanel（编辑面板）**

```kotlin
@Composable
fun EditPanel(
    config: NavigationBarConfig,
    isNightMode: Boolean,  // 当前 Tab 决定
    onConfigChange: (NavigationBarConfig) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        // 方案名称
        OutlinedTextField(
            value = config.name,
            onValueChange = { onConfigChange(config.copy(name = it)) },
            label = { Text("方案名称") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 布局模式
        Text(text = "布局模式", style = MaterialTheme.typography.subtitle2)
        Row(modifier = Modifier.selectableGroup()) {
            RadioButton(
                selected = config.layoutMode == LayoutMode.FIXED,
                onClick = { onConfigChange(config.copy(layoutMode = LayoutMode.FIXED)) }
            )
            Text("固定", modifier = Modifier.padding(start = 8.dp))
            
            Spacer(modifier = Modifier.width(16.dp))
            
            RadioButton(
                selected = config.layoutMode == LayoutMode.FLOATING,
                onClick = { onConfigChange(config.copy(layoutMode = LayoutMode.FLOATING)) }
            )
            Text("悬浮", modifier = Modifier.padding(start = 8.dp))
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 材质模式
        Text(text = "材质模式", style = MaterialTheme.typography.subtitle2)
        Row(modifier = Modifier.selectableGroup()) {
            RadioButton(
                selected = config.materialMode == MaterialMode.SOLID,
                onClick = { onConfigChange(config.copy(materialMode = MaterialMode.SOLID)) }
            )
            Text("实心", modifier = Modifier.padding(start = 8.dp))
            
            Spacer(modifier = Modifier.width(16.dp))
            
            RadioButton(
                selected = config.materialMode == MaterialMode.GLASS,
                onClick = { onConfigChange(config.copy(materialMode = MaterialMode.GLASS)) }
            )
            Text("玻璃", modifier = Modifier.padding(start = 8.dp))
            
            Spacer(modifier = Modifier.width(16.dp))
            
            RadioButton(
                selected = config.materialMode == MaterialMode.FROSTED,
                onClick = { onConfigChange(config.copy(materialMode = MaterialMode.FROSTED)) }
            )
            Text("磨砂", modifier = Modifier.padding(start = 8.dp))
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 不透明度（实心模式禁用）
        if (config.materialMode != MaterialMode.SOLID) {
            Text(text = "不透明度: ${config.opacity}%", style = MaterialTheme.typography.subtitle2)
            Slider(
                value = config.opacity.toFloat(),
                onValueChange = { onConfigChange(config.copy(opacity = it.toInt())) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // 边框颜色
        Text(text = "边框颜色", style = MaterialTheme.typography.subtitle2)
        ColorPickerButton(
            currentColor = config.borderColor,
            onColorChange = { onConfigChange(config.copy(borderColor = it)) }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 边框透明度
        Text(text = "边框透明度: ${config.borderOpacity}%", style = MaterialTheme.typography.subtitle2)
        Slider(
            value = config.borderOpacity.toFloat(),
            onValueChange = { onConfigChange(config.copy(borderOpacity = it.toInt())) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 操作按钮
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onCancel) {
                Text("取消")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onSave) {
                Text("保存")
            }
        }
    }
}
```

### 交互流程

1. 用户打开管理界面 → 默认显示日间方案 Tab
2. 用户切换 Tab → 显示对应模式（日间/夜间）的方案列表
3. 用户点击方案卡片 → 应用该方案（立即生效），卡片显示激活状态
4. 用户点击编辑按钮 → 底部弹出编辑面板
5. 用户修改参数 → 实时预览效果
6. 用户点击保存 → 更新方案配置，发送事件通知 MainActivity

---

## 5. 错误处理与边界情况

### 关键错误处理场景

**1. LiquidGlassView 库加载失败**

```kotlin
// 在 MainActivity 中
private fun setupLiquidGlass() {
    try {
        binding.bottomNavigationGlassView.bind(contentContainer)
        // 配置参数...
    } catch (e: Exception) {
        // 库加载失败，降级到实心模式
        Log.e("MainActivity", "LiquidGlassView failed", e)
        AppConfig.activeNavigationBarDay = "default_solid"
        applySolidMode()
    }
}
```

**2. 方案配置损坏**

```kotlin
// 在 NavigationBarManager 中
fun loadEntry(dirName: String): NavigationBarEntry? {
    val json = appCtx.getPrefString("navigationBar_$dirName")
    if (json == null) return null
    
    return try {
        GSON.fromJsonObject<NavigationBarEntry>(json).getOrNull()
    } catch (e: Exception) {
        // 配置损坏，返回默认方案
        Log.e("NavigationBarManager", "Config corrupted: $dirName", e)
        defaultEntry(isNightMode)
    }
}
```

**3. 实心模式透明度调节**

```kotlin
// 在 EditPanel 中
if (config.materialMode == MaterialMode.SOLID) {
    // 实心模式不允许调节透明度
    // 显示提示信息
    Text("实心模式不支持透明度调节", color = MaterialTheme.colors.error)
    // 禁用透明度滑块
    Slider(
        value = 100f,
        enabled = false,
        modifier = Modifier.alpha(0.5f)
    )
}
```

### 边界情况处理

**1. 默认方案不可删除**

```kotlin
// 在 SchemeCard 中
if (entry.source == Source.BUILTIN) {
    // 内置方案（default）不可删除
    IconButton(onClick = onDelete, enabled = false) {
        Icon(Icons.Default.Delete, contentDescription = "删除")
    }
}
```

**2. 主题切换时的方案应用**

```kotlin
// 在 MainActivity 中
override fun observeLiveBus() {
    observeEvent<Boolean>(EventBus.THEME_MODE_CHANGED) { isNightMode ->
        // 主题切换时，自动应用对应的方案
        val activeDirName = if (isNightMode) {
            AppConfig.activeNavigationBarNight
        } else {
            AppConfig.activeNavigationBarDay
        }
        val entry = NavigationBarManager.loadEntry(activeDirName)
        entry?.let { applyEffect(it.config) }
    }
}
```

**3. 方案名称冲突**

```kotlin
// 在 NavigationBarManager 中
fun saveEntry(entry: NavigationBarEntry) {
    val existingEntries = loadEntries(entry.config.isNightMode)
    if (existingEntries.any { it.config.name == entry.config.name && it.dirName != entry.dirName }) {
        // 名称冲突，提示用户
        toastOnUi("方案名称已存在，请使用其他名称")
        return
    }
    // 保存方案...
}
```

---

## 6. 默认行为

应用启动时使用当前的默认底栏样式（实心、固定），用户只有主动进入管理界面修改后才改变效果。

---

## 7. 实现要点

### 依赖添加

**libs.versions.toml**

```toml
liquidglass = "1.0.3"
liquidglass = { module = "com.qmdeve.liquidglass:core", version.ref = "liquidglass" }
```

**build.gradle.kts**

```kotlin
implementation(libs.liquidglass)
```

### overlay 渐变背景

创建 `res/drawable/bg_navigation_overlay.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    
    <corners android:radius="24dp" />
    
    <gradient
        android:angle="270"
        android:startColor="#46FFFFFF"
        android:centerColor="#2AFFFFFF"
        android:endColor="#261E2A36" />
    
    <stroke
        android:width="1dp"
        android:color="#72E7EEF5" />
</shape>
```

### LiquidGlassView 配置参数

```kotlin
private fun setupGlassView(
    binding: ActivityMainBinding,
    config: NavigationBarConfig,
    refractionHeight: Float
) {
    binding.bottomNavigationGlassView.run {
        visibility = View.VISIBLE
        bind(contentContainer)
        setCornerRadius(24f.dpToPx())
        setBlurRadius(config.opacity * 0.5f.dpToPx())
        setRefractionHeight(refractionHeight.dpToPx())
        setRefractionOffset(46f.dpToPx())
        setDispersion(0.15f)
        setTintAlpha(config.opacity / 100f * 0.25f)
        setDraggableEnabled(false)
        setElasticEnabled(true)
        setTouchEffectEnabled(true)
        isClickable = false
        isFocusable = false
        invalidate()
    }
}
```

---

## 8. 文件清单

### 新增文件

**数据层**
- `app/src/main/java/io/legado/app/data/entities/NavigationBarConfig.kt`
- `app/src/main/java/io/legado/app/data/entities/NavigationBarEntry.kt`

**业务层**
- `app/src/main/java/io/legado/app/model/NavigationBarManager.kt`
- `app/src/main/java/io/legado/app/model/NavigationBarEffectApplier.kt`

**界面层**
- `app/src/main/java/io/legado/app/ui/main/navigation/NavigationBarManageActivity.kt`
- `app/src/main/java/io/legado/app/ui/main/navigation/compose/TabLayout.kt`
- `app/src/main/java/io/legado/app/ui/main/navigation/compose/SchemeCard.kt`
- `app/src/main/java/io/legado/app/ui/main/navigation/compose/EditPanel.kt`
- `app/src/main/java/io/legado/app/ui/main/navigation/compose/PreviewBox.kt`

**资源文件**
- `app/src/main/res/drawable/bg_navigation_overlay.xml`
- `app/src/main/res/drawable/bg_navigation_solid.xml`

### 修改文件

- `app/src/main/java/io/legado/app/help/config/AppConfig.kt`（添加方案存储配置）
- `app/src/main/java/io/legado/app/constant/PreferKey.kt`（添加新的 PreferKey）
- `app/src/main/java/io/legado/app/constant/EventBus.kt`（添加 NAVIGATION_BAR_CHANGED 事件）
- `app/src/main/res/layout/activity_main.xml`（改造底部栏布局）
- `app/src/main/java/io/legado/app/ui/main/MainActivity.kt`（添加效果应用逻辑）
- `gradle/libs.versions.toml`（添加 liquidglass 依赖）

---

## 9. 测试要点

### 功能测试

1. **方案切换测试**：验证日间/夜间方案切换是否正确应用
2. **材质模式测试**：验证实心、玻璃、磨砂三种模式的效果
3. **布局模式测试**：验证固定、悬浮两种布局的效果
4. **参数调节测试**：验证透明度、边框颜色、边框透明度的调节
5. **默认方案测试**：验证默认方案不可删除
6. **主题切换测试**：验证主题切换时自动应用对应方案

### 边界测试

1. **库加载失败测试**：验证 LiquidGlassView 加载失败时的降级处理
2. **配置损坏测试**：验证方案配置损坏时的恢复处理
3. **名称冲突测试**：验证方案名称冲突时的提示

### 性能测试

1. **效果切换性能**：验证方案切换时的响应速度
2. **内存占用测试**：验证 LiquidGlassView 的内存占用
3. **渲染性能测试**：验证玻璃效果的渲染性能

---

## 10. 实现优先级

### Phase 1：核心功能（必须）

1. 添加依赖库
2. 改造 MainActivity 布局
3. 实现数据结构和 AppConfig 扩展
4. 实现 NavigationBarEffectApplier
5. 实现实心、玻璃、磨砂三种材质模式

### Phase 2：管理界面（必须）

1. 实现 NavigationBarManageActivity（Compose）
2. 实现 TabLayout、SchemeCard、EditPanel
3. 实现 NavigationBarManager
4. 实现方案保存、加载、切换逻辑

### Phase 3：边界处理（必须）

1. 实现错误处理（库加载失败、配置损坏）
2. 实现默认方案保护
3. 实现主题切换自动应用

### Phase 4：优化与测试（可选）

1. 性能优化
2. 完整的测试覆盖
3. 用户文档

---

## 11. 风险与限制

### 技术风险

1. **LiquidGlassView 库稳定性**：第三方库可能存在兼容性问题或性能问题
2. **性能影响**：玻璃效果可能增加 GPU 渲染负担，影响低端设备性能
3. **Android 版本兼容性**：不同 Android 版本的模糊效果表现可能不一致

### 功能限制

1. **实心模式透明度不可调**：实心模式不支持透明度调节，这是设计决策
2. **默认方案不可删除**：内置的 default 方案不可删除，保证用户始终有可用方案
3. **方案数量限制**：建议限制方案数量，避免 SharedPreferences 过大

---

## 12. 后续扩展

### 可能的扩展方向

1. **图标自定义**：支持自定义底部栏图标样式
2. **动画效果**：支持方案切换时的过渡动画
3. **方案分享**：支持方案导出/导入，用户可以分享自己的配置
4. **预设方案库**：提供更多内置预设方案供用户选择

---

## 附录：参考文档

- [底栏管理功能实现.md](../底栏管理功能实现.md)
- [LiquidGlassView 官方文档](https://github.com/qmdeve/LiquidGlass)
- [Jetpack Compose 官方文档](https://developer.android.com/jetpack/compose)