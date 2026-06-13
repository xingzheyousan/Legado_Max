# 液态玻璃底栏效果设计

## 概述

为主界面底栏添加液态玻璃视觉效果，支持实心/玻璃/磨砂三种材质和固定/悬浮两种布局。默认关闭，用户在"底栏管理"中手动开启。

## 功能行为

- **默认状态**：底栏显示普通样式，无任何叠加层
- **开启后**：根据用户选择的方案，动态在底栏上叠加玻璃/磨砂效果
- **关闭时**：移除叠加层，恢复原始底栏

## 用户操作流程

```
主题设置 → 底栏管理 → 选择/创建方案 → 应用方案
```

入口沿用 ThemeConfigFragment 中的"底栏管理"，跳转到 NavigationBarManageActivity。

## 架构设计

### 数据流

```
用户选择方案
    ↓
NavigationBarManager.apply(entry)
    ↓
记录激活方案到 AppConfig (SharedPreferences)
    ↓
发送 EventBus.NAVIGATION_BAR_CHANGED 事件
    ↓
MainActivity 收到事件 → setupNavigationBar()
    ↓
NavigationBarEffectApplier.applyEffect(config, binding)
    ↓
┌─ SOLID  → 移除叠加层，恢复原始底栏
├─ GLASS  → 动态添加 LiquidGlassView(折射+模糊) + overlay
└─ FROSTED → 动态添加 LiquidGlassView(仅模糊) + overlay
```

### 动态叠加机制

`activity_main.xml` 保持简洁布局（只有 ThemeBottomNavigationVIew），不预设叠加层。

**开启效果时**：
1. 获取 `bottom_navigation_view` 的父容器（LinearLayout）
2. 记录 `bottom_navigation_view` 在父容器中的位置索引
3. 创建 FrameLayout 作为新容器
4. 将 `bottom_navigation_view` 从 LinearLayout 移到 FrameLayout 中
5. 在 FrameLayout 中添加 overlay View 和 LiquidGlassView
6. 将 FrameLayout 放回 LinearLayout 原位置

**关闭效果时**：
1. 从 FrameLayout 中取出 `bottom_navigation_view`
2. 将 FrameLayout 从 LinearLayout 移除
3. 将 `bottom_navigation_view` 放回 LinearLayout 原位置

### 已有基础设施（保留复用）

| 组件 | 文件 | 状态 |
|------|------|------|
| 数据结构 | NavigationBarConfig.kt, NavigationBarEntry.kt, LayoutMode, MaterialMode | 已完成 |
| 方案管理 | NavigationBarManager.kt | 已完成 |
| 配置存取 | AppConfig.activeNavigationBarDay/Night, activeDirName() | 已完成 |
| 事件常量 | EventBus.NAVIGATION_BAR_CHANGED | 已完成 |
| 偏好键 | PreferKey.navigationBarPackageDay/Night | 已完成 |
| Drawable | bg_navigation_overlay.xml, bg_navigation_solid.xml | 已完成 |
| 依赖 | liquidglass 1.0.3 | 已完成 |
| 管理界面 | NavigationBarManageActivity + Compose 组件 | 已完成 |

### 需要修改的文件

| 文件 | 修改内容 |
|------|---------|
| NavigationBarEffectApplier.kt | 重新实现 applyEffect()，使用动态叠加机制 |
| MainActivity.kt | 恢复 setupNavigationBar() 调用和事件监听 |

### SDK 兼容

- API 24+：完整支持 LiquidGlassView
- API 21-23：自动降级到实心模式（不使用 LiquidGlassView）

### 默认方案

默认方案为 SOLID（实心），即不叠加任何效果。用户首次安装后底栏显示与无液态玻璃功能时完全一致。

## 约束

- 效果范围：仅主界面底栏
- 设置入口：沿用 ThemeConfigFragment 中的"底栏管理"
- 默认关闭，手动开启
