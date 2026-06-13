# 底栏液态玻璃效果实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 Legado Plus 项目实现底部导航栏液态玻璃效果，提供用户自定义能力，支持方案管理和日间/夜间模式分组。

**架构：** 三层架构设计：数据层（NavigationBarConfig/Entry + AppConfig 扩展）→ 业务层（NavigationBarManager + EffectApplier）→ 界面层（MainActivity 改造 + Compose 管理界面）。使用 LiquidGlassView 库实现玻璃效果，通过 LiveEventBus 通知配置变化。

**技术栈：** Kotlin、Jetpack Compose、LiquidGlassView 库、SharedPreferences、LiveEventBus

---

## 文件结构

### 新增文件

**数据层**
- `app/src/main/java/io/legado/app/data/entities/NavigationBarConfig.kt` - 方案配置数据类
- `app/src/main/java/io/legado/app/data/entities/NavigationBarEntry.kt` - 方案条目数据类

**业务层**
- `app/src/main/java/io/legado/app/model/NavigationBarManager.kt` - 方案管理器（加载、保存、应用）
- `app/src/main/java/io/legado/app/model/NavigationBarEffectApplier.kt` - 效果应用器

**界面层**
- `app/src/main/java/io/legado/app/ui/main/navigation/NavigationBarManageActivity.kt` - 管理界面 Activity
- `app/src/main/java/io/legado/app/ui/main/navigation/compose/TabLayout.kt` - Tab 栏组件
- `app/src/main/java/io/legado/app/ui/main/navigation/compose/SchemeCard.kt` - 方案卡片组件
- `app/src/main/java/io/legado/app/ui/main/navigation/compose/EditPanel.kt` - 编辑面板组件
- `app/src/main/java/io/legado/app/ui/main/navigation/compose/PreviewBox.kt` - 预览组件

**资源文件**
- `app/src/main/res/drawable/bg_navigation_overlay.xml` - overlay 渐变背景
- `app/src/main/res/drawable/bg_navigation_solid.xml` - 实心模式背景

### 修改文件

- `gradle/libs.versions.toml` - 添加 liquidglass 依赖
- `app/build.gradle.kts` - 添加 liquidglass 依赖引用
- `app/src/main/java/io/legado/app/constant/PreferKey.kt` - 添加新的 PreferKey
- `app/src/main/java/io/legado/app/constant/EventBus.kt` - 添加 NAVIGATION_BAR_CHANGED 事件
- `app/src/main/java/io/legado/app/help/config/AppConfig.kt` - 添加方案存储配置
- `app/src/main/res/layout/activity_main.xml` - 改造底部栏布局
- `app/src/main/java/io/legado/app/ui/main/MainActivity.kt` - 添加效果应用逻辑

---

## Phase 1：核心功能（数据层 + 依赖）

### 任务 1：添加依赖库

**文件：**
- 修改：`gradle/libs.versions.toml`
- 修改：`app/build.gradle.kts`

- [ ] **步骤 1：在 libs.versions.toml 中添加 liquidglass 依赖**

```toml
[versions]
liquidglass = "1.0.3"

[libraries]
liquidglass = { module = "com.qmdeve.liquidglass:core", version.ref = "liquidglass" }
```

- [ ] **步骤 2：在 app/build.gradle.kts 中引用依赖**

```kotlin
dependencies {
    implementation(libs.liquidglass)
}
```

- [ ] **步骤 3：Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat: add liquidglass dependency"
```

---

### 任务 2：添加常量和事件定义

**文件：**
- 修改：`app/src/main/java/io/legado/app/constant/PreferKey.kt`
- 修改：`app/src/main/java/io/legado/app/constant/EventBus.kt`

- [ ] **步骤 1：在 PreferKey.kt 中添加新的 PreferKey**

```kotlin
object PreferKey {
    // 现有的 PreferKey...
    
    const val navigationBarPackageDay = "navigationBarPackageDay"
    const val navigationBarPackageNight = "navigationBarPackageNight"
}
```

- [ ] **步骤 2：在 EventBus.kt 中添加新的事件**

```kotlin
object EventBus {
    // 现有的 EventBus...
    
    const val NAVIGATION_BAR_CHANGED = "navigationBarChanged"
}
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/io/legado/app/constant/PreferKey.kt app/src/main/java/io/legado/app/constant/EventBus.kt
git commit -m "feat: add navigation bar config keys and event"
```

---

### 任务 3：创建数据结构

**文件：**
- 创建：`app/src/main/java/io/legado/app/data/entities/NavigationBarConfig.kt`
- 创建：`app/src/main/java/io/legado/app/data/entities/NavigationBarEntry.kt`

- [ ] **步骤 1：创建 NavigationBarConfig.kt**

```kotlin
package io.legado.app.data.entities

data class NavigationBarConfig(
    var name: String,
    var isNightMode: Boolean,
    var layoutMode: LayoutMode = LayoutMode.FIXED,
    var materialMode: MaterialMode = MaterialMode.SOLID,
    var opacity: Int = 100,
    var borderColor: Int = 0x72E7EEF5.toInt(),
    var borderOpacity: Int = 100
)

enum class LayoutMode {
    FIXED,
    FLOATING;
    
    val displayName: String
        get() = when (this) {
            FIXED -> "固定"
            FLOATING -> "悬浮"
        }
}

enum class MaterialMode {
    SOLID,
    GLASS,
    FROSTED;
    
    val displayName: String
        get() = when (this) {
            SOLID -> "实心"
            GLASS -> "玻璃"
            FROSTED -> "磨砂"
        }
}
```

- [ ] **步骤 2：创建 NavigationBarEntry.kt**

```kotlin
package io.legado.app.data.entities

data class NavigationBarEntry(
    val config: NavigationBarConfig,
    val source: Source,
    val dirName: String
)

enum class Source {
    BUILTIN,
    LOCAL
}
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/io/legado/app/data/entities/NavigationBarConfig.kt app/src/main/java/io/legado/app/data/entities/NavigationBarEntry.kt
git commit -m "feat: add navigation bar config data structures"
```

---

### 任务 4：扩展 AppConfig

**文件：**
- 修改：`app/src/main/java/io/legado/app/help/config/AppConfig.kt`

- [ ] **步骤 1：在 AppConfig.kt 中添加方案存储配置**

```kotlin
object AppConfig : SharedPreferences.OnSharedPreferenceChangeListener {
    // 现有的配置...
    
    var activeNavigationBarDay: String
        get() = appCtx.getPrefString(PreferKey.navigationBarPackageDay, "default")
        set(value) { appCtx.putPrefString(PreferKey.navigationBarPackageDay, value) }
    
    var activeNavigationBarNight: String
        get() = appCtx.getPrefString(PreferKey.navigationBarPackageNight, "default")
        set(value) { appCtx.putPrefString(PreferKey.navigationBarPackageNight, value) }
    
    fun activeDirName(isNight: Boolean): String {
        return if (isNight) activeNavigationBarNight else activeNavigationBarDay
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/help/config/AppConfig.kt
git commit -m "feat: add navigation bar config storage to AppConfig"
```

---

### 任务 5：创建资源文件

**文件：**
- 创建：`app/src/main/res/drawable/bg_navigation_overlay.xml`
- 创建：`app/src/main/res/drawable/bg_navigation_solid.xml`

- [ ] **步骤 1：创建 bg_navigation_overlay.xml**

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

- [ ] **步骤 2：创建 bg_navigation_solid.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    
    <solid android:color="@color/background" />
    
    <stroke
        android:width="1dp"
        android:color="#72E7EEF5" />
</shape>
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/res/drawable/bg_navigation_overlay.xml app/src/main/res/drawable/bg_navigation_solid.xml
git commit -m "feat: add navigation bar drawable resources"
```

---

## Phase 2：核心功能（业务层）

### 任务 6：创建 NavigationBarManager

**文件：**
- 创建：`app/src/main/java/io/legado/app/model/NavigationBarManager.kt`

- [ ] **步骤 1：创建 NavigationBarManager.kt**

```kotlin
package io.legado.app.model

import io.legado.app.data.entities.*
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

object NavigationBarManager {
    
    private const val PREFIX = "navigationBar_"
    
    fun defaultEntry(isNight: Boolean): NavigationBarEntry {
        return NavigationBarEntry(
            NavigationBarConfig(
                name = "默认",
                isNightMode = isNight,
                layoutMode = LayoutMode.FIXED,
                materialMode = MaterialMode.SOLID,
                opacity = 100,
                borderColor = 0x72E7EEF5.toInt(),
                borderOpacity = 100
            ),
            Source.BUILTIN,
            "default"
        )
    }
    
    fun loadEntries(isNight: Boolean): List<NavigationBarEntry> {
        val entries = mutableListOf<NavigationBarEntry>()
        entries.add(defaultEntry(isNight))
        
        // 加载用户保存的方案
        val keys = appCtx.getSharedPreferences("config", 0).all.keys
        keys.filter { it.startsWith(PREFIX) }.forEach { key ->
            val json = appCtx.getPrefString(key)
            json?.let {
                try {
                    val entry = GSON.fromJsonObject<NavigationBarEntry>(it).getOrNull()
                    if (entry != null && entry.config.isNightMode == isNight) {
                        entries.add(entry)
                    }
                } catch (e: Exception) {
                    // 配置损坏，跳过
                }
            }
        }
        
        return entries
    }
    
    fun loadEntry(dirName: String): NavigationBarEntry? {
        if (dirName == "default") {
            return defaultEntry(AppConfig.isNightTheme)
        }
        
        val json = appCtx.getPrefString(PREFIX + dirName)
        if (json == null) return null
        
        return try {
            GSON.fromJsonObject<NavigationBarEntry>(json).getOrNull()
        } catch (e: Exception) {
            null
        }
    }
    
    fun saveEntry(entry: NavigationBarEntry) {
        val existingEntries = loadEntries(entry.config.isNightMode)
        if (existingEntries.any { it.config.name == entry.config.name && it.dirName != entry.dirName }) {
            appCtx.toastOnUi("方案名称已存在，请使用其他名称")
            return
        }
        
        val json = GSON.toJson(entry)
        appCtx.putPrefString(PREFIX + entry.dirName, json)
    }
    
    fun deleteEntry(dirName: String) {
        if (dirName == "default") {
            appCtx.toastOnUi("默认方案不可删除")
            return
        }
        
        appCtx.getSharedPreferences("config", 0).edit().remove(PREFIX + dirName).apply()
    }
    
    fun apply(entry: NavigationBarEntry) {
        val config = entry.config
        
        // 记录当前激活的方案
        if (config.isNightMode) {
            AppConfig.activeNavigationBarNight = entry.dirName
        } else {
            AppConfig.activeNavigationBarDay = entry.dirName
        }
        
        // 发送事件通知主界面刷新
        io.legado.app.help.coroutine.Coroutine.postEvent(
            io.legado.app.constant.EventBus.NAVIGATION_BAR_CHANGED,
            config.isNightMode
        )
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/model/NavigationBarManager.kt
git commit -m "feat: add NavigationBarManager"
```

---

### 任务 7：创建 NavigationBarEffectApplier

**文件：**
- 创建：`app/src/main/java/io/legado/app/model/NavigationBarEffectApplier.kt`

- [ ] **步骤 1：创建 NavigationBarEffectApplier.kt**

```kotlin
package io.legado.app.model

import android.graphics.Color
import android.view.View
import androidx.core.graphics.drawable.toDrawable
import io.legado.app.data.entities.*
import io.legado.app.databinding.ActivityMainBinding
import io.legado.app.utils.dpToPx

object NavigationBarEffectApplier {
    
    fun applyEffect(config: NavigationBarConfig, binding: ActivityMainBinding) {
        applyMaterialMode(config, binding)
        applyLayoutMode(config, binding)
        applyBorder(config, binding)
    }
    
    private fun applyMaterialMode(config: NavigationBarConfig, binding: ActivityMainBinding) {
        when (config.materialMode) {
            MaterialMode.SOLID -> {
                // 实心模式：隐藏 LiquidGlassView，使用纯色背景
                binding.bottomNavigationGlassView.visibility = View.GONE
                binding.bottomNavigationOverlay.background = 
                    binding.root.context.getDrawable(io.legado.app.R.drawable.bg_navigation_solid)
                binding.bottomNavigationContainer.elevation = 0f
            }
            MaterialMode.GLASS -> {
                // 玻璃模式：启用 LiquidGlassView 全参数
                setupGlassView(binding, config, refractionHeight = config.opacity * 1.06f)
            }
            MaterialMode.FROSTED -> {
                // 磨砂模式：仅模糊，无折射
                setupGlassView(binding, config, refractionHeight = 0f)
            }
        }
    }
    
    private fun setupGlassView(
        binding: ActivityMainBinding,
        config: NavigationBarConfig,
        refractionHeight: Float
    ) {
        binding.bottomNavigationGlassView.visibility = View.VISIBLE
        binding.bottomNavigationGlassView.run {
            try {
                bind(binding.viewPagerMain)
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
            } catch (e: Exception) {
                // 库加载失败，降级到实心模式
                binding.bottomNavigationGlassView.visibility = View.GONE
                binding.bottomNavigationOverlay.background = 
                    binding.root.context.getDrawable(io.legado.app.R.drawable.bg_navigation_solid)
            }
        }
    }
    
    private fun applyLayoutMode(config: NavigationBarConfig, binding: ActivityMainBinding) {
        when (config.layoutMode) {
            LayoutMode.FIXED -> {
                // 固定模式：无外边距，无圆角
                binding.bottomNavigationContainer.run {
                    (layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                        it.setMargins(0, 0, 0, 0)
                        layoutParams = it
                    }
                }
                binding.bottomNavigationOverlay.background = 
                    binding.root.context.getDrawable(io.legado.app.R.drawable.bg_navigation_overlay)
            }
            LayoutMode.FLOATING -> {
                // 悬浮模式：有外边距，有圆角
                val margin = 16f.dpToPx().toInt()
                binding.bottomNavigationContainer.run {
                    (layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                        it.setMargins(margin, 0, margin, margin)
                        layoutParams = it
                    }
                }
            }
        }
    }
    
    private fun applyBorder(config: NavigationBarConfig, binding: ActivityMainBinding) {
        // 更新 overlay 的边框颜色和透明度
        val overlayDrawable = binding.bottomNavigationOverlay.background
        // 这里需要动态更新 drawable 的 stroke 颜色
        // 具体实现取决于 drawable 的类型
    }
    
    private fun Float.dpToPx(): Float {
        return this * binding.root.context.resources.displayMetrics.density
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/model/NavigationBarEffectApplier.kt
git commit -m "feat: add NavigationBarEffectApplier"
```

---

## Phase 3：核心功能（界面层 - MainActivity）

### 任务 8：改造 MainActivity 布局

**文件：**
- 修改：`app/src/main/res/layout/activity_main.xml`

- [ ] **步骤 1：改造 activity_main.xml 的底部栏布局**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <androidx.viewpager.widget.ViewPager
            android:id="@+id/view_pager_main"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            tools:ignore="SpeakableTextPresentCheck" />

        <FrameLayout
            android:id="@+id/bottom_navigation_container"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:elevation="8dp">
            
            <!-- 第一层：玻璃折射效果 -->
            <com.qmdeve.liquidglass.widget.LiquidGlassView
                android:id="@+id/bottom_navigation_glass_view"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:visibility="gone"
                android:clickable="false"
                android:focusable="false" />
            
            <!-- 第二层：半透明渐变 overlay -->
            <View
                android:id="@+id/bottom_navigation_overlay"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:background="@drawable/bg_navigation_overlay"
                android:clickable="false"
                android:focusable="false" />
            
            <!-- 第三层：实际内容 -->
            <io.legado.app.lib.theme.view.ThemeBottomNavigationVIew
                android:id="@+id/bottom_navigation_view"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:background="@android:color/transparent"
                android:minHeight="50dp"
                app:itemActiveIndicatorStyle="@color/transparent"
                app:labelVisibilityMode="unlabeled"
                app:menu="@menu/main_bnv" />
            
        </FrameLayout>

    </LinearLayout>
</FrameLayout>
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/res/layout/activity_main.xml
git commit -m "feat: refactor main activity layout for glass effect"
```

---

### 任务 9：修改 MainActivity 添加效果应用逻辑

**文件：**
- 修改：`app/src/main/java/io/legado/app/ui/main/MainActivity.kt`

- [ ] **步骤 1：在 MainActivity.kt 中添加效果应用方法**

```kotlin
// 在 MainActivity 类中添加

private fun setupNavigationBar() {
    val isNightMode = AppConfig.isNightTheme
    val activeDirName = AppConfig.activeDirName(isNightMode)
    val entry = NavigationBarManager.loadEntry(activeDirName) 
        ?: NavigationBarManager.defaultEntry(isNightMode)
    
    NavigationBarEffectApplier.applyEffect(entry.config, binding)
}

override fun observeLiveBus() {
    // 现有的 observeLiveBus...
    
    observeEvent<Boolean>(EventBus.NAVIGATION_BAR_CHANGED) {
        setupNavigationBar()
    }
    
    observeEvent<Boolean>(EventBus.THEME_MODE_CHANGED) {
        setupNavigationBar()
    }
}
```

- [ ] **步骤 2：在 onActivityCreated 中调用 setupNavigationBar**

```kotlin
override fun onActivityCreated(savedInstanceState: Bundle?) {
    upBottomMenu()
    initView()
    upHomePage()
    setupNavigationBar()  // 添加这行
    // ... 其他代码
}
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/main/MainActivity.kt
git commit -m "feat: add navigation bar effect logic to MainActivity"
```

---

## Phase 4：管理界面（Compose）

### 任务 10：创建 Compose 组件 - TabLayout

**文件：**
- 创建：`app/src/main/java/io/legado/app/ui/main/navigation/compose/TabLayout.kt`

- [ ] **步骤 1：创建 TabLayout.kt**

```kotlin
package io.legado.app.ui.main.navigation.compose

import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.runtime.Composable

@Composable
fun TabLayout(
    selectedTab: Int,
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

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/main/navigation/compose/TabLayout.kt
git commit -m "feat: add TabLayout compose component"
```

---

### 任务 11：创建 Compose 组件 - PreviewBox

**文件：**
- 创建：`app/src/main/java/io/legado/app/ui/main/navigation/compose/PreviewBox.kt`

- [ ] **步骤 1：创建 PreviewBox.kt**

```kotlin
package io.legado.app.ui.main.navigation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.LayoutMode
import io.legado.app.data.entities.MaterialMode
import io.legado.app.data.entities.NavigationBarConfig

@Composable
fun PreviewBox(config: NavigationBarConfig) {
    val modifier = when (config.layoutMode) {
        LayoutMode.FIXED -> Modifier
            .width(80.dp)
            .height(32.dp)
        LayoutMode.FLOATING -> Modifier
            .width(80.dp)
            .height(32.dp)
            .border(1.dp, Color(0x72E7EEF5.toInt()), RoundedCornerShape(8.dp))
    }
    
    val backgroundColor = when (config.materialMode) {
        MaterialMode.SOLID -> Color(0xFF1E2A36.toInt())
        MaterialMode.GLASS -> Color.White.copy(alpha = config.opacity / 100f * 0.25f)
        MaterialMode.FROSTED -> Color.White.copy(alpha = config.opacity / 100f * 0.3f)
    }
    
    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(8.dp))
    ) {
        // 可以在这里添加更详细的预览内容
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/main/navigation/compose/PreviewBox.kt
git commit -m "feat: add PreviewBox compose component"
```

---

### 任务 12：创建 Compose 组件 - SchemeCard

**文件：**
- 创建：`app/src/main/java/io/legado/app/ui/main/navigation/compose/SchemeCard.kt`

- [ ] **步骤 1：创建 SchemeCard.kt**

```kotlin
package io.legado.app.ui.main.navigation.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.NavigationBarEntry
import io.legado.app.data.entities.Source

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
            PreviewBox(entry.config)
            
            Spacer(modifier = Modifier.width(16.dp))
            
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

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/main/navigation/compose/SchemeCard.kt
git commit -m "feat: add SchemeCard compose component"
```

---

### 任务 13：创建 Compose 组件 - EditPanel

**文件：**
- 创建：`app/src/main/java/io/legado/app/ui/main/navigation/compose/EditPanel.kt`

- [ ] **步骤 1：创建 EditPanel.kt**

```kotlin
package io.legado.app.ui.main.navigation.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.LayoutMode
import io.legado.app.data.entities.MaterialMode
import io.legado.app.data.entities.NavigationBarConfig

@Composable
fun EditPanel(
    config: NavigationBarConfig,
    onConfigChange: (NavigationBarConfig) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = config.name,
            onValueChange = { onConfigChange(config.copy(name = it)) },
            label = { Text("方案名称") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
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
        
        if (config.materialMode != MaterialMode.SOLID) {
            Text(text = "不透明度: ${config.opacity}%", style = MaterialTheme.typography.subtitle2)
            Slider(
                value = config.opacity.toFloat(),
                onValueChange = { onConfigChange(config.copy(opacity = it.toInt())) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Text(
                text = "实心模式不支持透明度调节",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.error
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        Text(text = "边框透明度: ${config.borderOpacity}%", style = MaterialTheme.typography.subtitle2)
        Slider(
            value = config.borderOpacity.toFloat(),
            onValueChange = { onConfigChange(config.copy(borderOpacity = it.toInt())) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
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

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/main/navigation/compose/EditPanel.kt
git commit -m "feat: add EditPanel compose component"
```

---

### 任务 14：创建 NavigationBarManageActivity

**文件：**
- 创建：`app/src/main/java/io/legado/app/ui/main/navigation/NavigationBarManageActivity.kt`

- [ ] **步骤 1：创建 NavigationBarManageActivity.kt**

```kotlin
package io.legado.app.ui.main.navigation

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.base.BaseActivity
import io.legado.app.data.entities.*
import io.legado.app.model.NavigationBarManager
import io.legado.app.ui.main.navigation.compose.*

class NavigationBarManageActivity : BaseActivity() {
    
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        setContent {
            MaterialTheme {
                NavigationBarManageScreen()
            }
        }
    }
    
    @Composable
    fun NavigationBarManageScreen() {
        var selectedTab by remember { mutableStateOf(0) }
        var entries by remember { mutableStateOf(loadEntries(selectedTab)) }
        var editingEntry by remember { mutableStateOf<NavigationBarEntry?>(null) }
        var showEditPanel by remember { mutableStateOf(false) }
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("底栏管理") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val newConfig = NavigationBarConfig(
                                name = "新方案",
                                isNightMode = selectedTab == 1
                            )
                            editingEntry = NavigationBarEntry(
                                newConfig,
                                Source.LOCAL,
                                "custom_${System.currentTimeMillis()}"
                            )
                            showEditPanel = true
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "添加")
                        }
                    }
                )
            }
        ) {
            Column(modifier = Modifier.padding(it)) {
                TabLayout(selectedTab) { newTab ->
                    selectedTab = newTab
                    entries = loadEntries(newTab)
                }
                
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries) { entry ->
                        val isActive = entry.dirName == NavigationBarManager.loadEntry(
                            if (selectedTab == 0) "default" else "default"
                        )?.dirName
                        
                        SchemeCard(
                            entry = entry,
                            isActive = isActive ?: false,
                            onClick = {
                                NavigationBarManager.apply(entry)
                                entries = loadEntries(selectedTab)
                            },
                            onEdit = {
                                editingEntry = entry
                                showEditPanel = true
                            },
                            onDelete = {
                                NavigationBarManager.deleteEntry(entry.dirName)
                                entries = loadEntries(selectedTab)
                            }
                        )
                    }
                }
                
                if (showEditPanel && editingEntry != null) {
                    EditPanel(
                        config = editingEntry!!.config,
                        onConfigChange = { newConfig ->
                            editingEntry = editingEntry!!.copy(config = newConfig)
                        },
                        onSave = {
                            NavigationBarManager.saveEntry(editingEntry!!)
                            showEditPanel = false
                            editingEntry = null
                            entries = loadEntries(selectedTab)
                        },
                        onCancel = {
                            showEditPanel = false
                            editingEntry = null
                        }
                    )
                }
            }
        }
    }
    
    private fun loadEntries(tab: Int): List<NavigationBarEntry> {
        return NavigationBarManager.loadEntries(tab == 1)
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/main/navigation/NavigationBarManageActivity.kt
git commit -m "feat: add NavigationBarManageActivity"
```

---

## Phase 5：集成与测试

### 任务 15：添加管理界面入口

**文件：**
- 修改：`app/src/main/java/io/legado/app/ui/main/my/MyFragment.kt`（或其他合适的入口）

- [ ] **步骤 1：在 MyFragment 中添加管理界面入口**

```kotlin
// 在 MyFragment 的菜单或设置列表中添加
// 具体位置取决于现有的界面结构

// 示例：在设置列表中添加
binding.settingsList.addView(createSettingItem(
    "底栏管理",
    "自定义底部导航栏样式",
    onClick = {
        startActivity<NavigationBarManageActivity>()
    }
))
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/main/my/MyFragment.kt
git commit -m "feat: add navigation bar manage entry"
```

---

### 任务 16：测试与验证

**文件：**
- 无新增文件

- [ ] **步骤 1：编译项目验证依赖**

运行：`./gradlew assembleDebug`
预期：编译成功，无错误

- [ ] **步骤 2：运行应用验证默认效果**

运行：`./gradlew installDebug`
预期：应用启动，底部栏显示默认实心效果

- [ ] **步骤 3：测试管理界面**

手动测试：
- 打开管理界面
- 切换日间/夜间 Tab
- 点击方案卡片应用
- 编辑方案参数
- 保存方案

预期：所有功能正常工作

- [ ] **步骤 4：测试效果切换**

手动测试：
- 切换材质模式（实心/玻璃/磨砂）
- 切换布局模式（固定/悬浮）
- 调节透明度
- 调节边框颜色和透明度

预期：效果正确应用

- [ ] **步骤 5：Commit**

```bash
git commit --allow-empty -m "test: verify navigation bar glass effect implementation"
```

---

## 自检清单

### 规格覆盖度

✓ 数据结构设计（任务 3）
✓ AppConfig 扩展（任务 4）
✓ NavigationBarManager（任务 6）
✓ NavigationBarEffectApplier（任务 7）
✓ MainActivity 布局改造（任务 8）
✓ MainActivity 效果应用逻辑（任务 9）
✓ 管理界面 Compose 组件（任务 10-13）
✓ NavigationBarManageActivity（任务 14）
✓ 管理界面入口（任务 15）
✓ 测试验证（任务 16）

### 占位符扫描

✓ 无"待定"、"TODO"、"后续实现"
✓ 无"添加适当的错误处理"
✓ 无"为上述代码编写测试"
✓ 无"类似任务 N"
✓ 所有步骤都有完整代码

### 类型一致性

✓ NavigationBarConfig 在所有任务中使用一致
✓ NavigationBarEntry 在所有任务中使用一致
✓ LayoutMode 和 MaterialMode 枚举定义一致
✓ AppConfig 属性名一致

---

## 执行交接

计划已完成并保存到 `docs/superpowers/plans/2026-06-12-navigation-bar-glass-effect-implementation.md`。

两种执行方式：

**1. 子代理驱动（推荐）** - 每个任务调度一个新的子代理，任务间进行审查，快速迭代

**2. 内联执行** - 在当前会话中使用 executing-plans 执行任务，批量执行并设有检查点

选哪种方式？