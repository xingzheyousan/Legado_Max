package io.legado.app.model

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.LayoutMode
import io.legado.app.data.entities.MaterialMode
import io.legado.app.data.entities.NavigationBarConfig
import io.legado.app.data.entities.NavigationBarEntry
import io.legado.app.data.entities.Source
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 底栏液态玻璃效果方案管理器
 *
 * 提供方案的创建、加载、保存、删除和应用功能。
 * 方案存储在 SharedPreferences 中，使用 JSON 格式序列化。
 *
 * 主要功能：
 * - defaultEntry: 创建默认方案（固定布局、实心材质）
 * - loadEntries: 加载指定模式（日间/夜间）的所有方案
 * - loadEntry: 加载单个方案
 * - saveEntry: 保存方案（检查名称唯一性）
 * - deleteEntry: 删除方案（默认方案不可删除）
 * - apply: 应用方案并通知界面刷新
 */
object NavigationBarManager {

    /** SharedPreferences 键名前缀 */
    private const val PREFIX = "navigationBar_"

    /**
     * 创建默认方案
     *
     * 默认方案使用固定布局、实心材质，适用于日间或夜间模式。
     * dirName 固定为 "default"，来源标记为 BUILTIN（内置）。
     *
     * @param isNight 是否为夜间模式
     * @return 默认方案实例
     */
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

    /**
     * 加载指定模式的所有方案
     *
     * 返回列表始终包含默认方案作为第一个元素，后跟用户保存的自定义方案。
     * 只返回与指定模式（日间/夜间）匹配的方案。
     *
     * @param isNight 是否为夜间模式
     * @return 方案列表，至少包含默认方案
     */
    fun loadEntries(isNight: Boolean): List<NavigationBarEntry> {
        val entries = mutableListOf<NavigationBarEntry>()
        entries.add(defaultEntry(isNight))

        // 加载用户保存的方案（从 defaultSharedPreferences 读取，与 saveEntry 写入一致）
        val keys = appCtx.defaultSharedPreferences.all.keys
        keys.filter { it.startsWith(PREFIX) }.forEach { key ->
            val json = appCtx.getPrefString(key)
            json?.let {
                val entry = GSON.fromJsonObject<NavigationBarEntry>(it).getOrNull()
                if (entry != null && entry.config.isNightMode == isNight) {
                    entries.add(entry)
                }
            }
        }

        return entries
    }

    /**
     * 加载单个方案
     *
     * 如果 dirName 为 "default"，返回当前主题模式对应的默认方案。
     * 否则从 SharedPreferences 加载指定名称的方案。
     *
     * @param dirName 方案目录名（唯一标识）
     * @return 方案实例，不存在时返回 null
     */
    fun loadEntry(dirName: String): NavigationBarEntry? {
        if (dirName == "default") {
            return defaultEntry(AppConfig.isNightTheme)
        }

        val json = appCtx.getPrefString(PREFIX + dirName)
        if (json == null) return null

        return GSON.fromJsonObject<NavigationBarEntry>(json).getOrNull()
    }

    /**
     * 保存方案
     *
     * 检查方案名称的唯一性（同一模式下不允许重复名称）。
     * 方案以 JSON 格式存储在 SharedPreferences 中。
     *
     * @param entry 要保存的方案
     */
    fun saveEntry(entry: NavigationBarEntry) {
        val existingEntries = loadEntries(entry.config.isNightMode)
        if (existingEntries.any { it.config.name == entry.config.name && it.dirName != entry.dirName }) {
            appCtx.toastOnUi("方案名称已存在，请使用其他名称")
            return
        }

        val json = GSON.toJson(entry)
        appCtx.putPrefString(PREFIX + entry.dirName, json)
    }

    /**
     * 删除方案
     *
     * 默认方案（dirName="default"）不可删除。
     * 删除操作从 SharedPreferences 中移除方案配置。
     *
     * @param dirName 要删除的方案目录名
     */
    fun deleteEntry(dirName: String) {
        if (dirName == "default") {
            appCtx.toastOnUi("默认方案不可删除")
            return
        }

        appCtx.removePref(PREFIX + dirName)
    }

    /**
     * 应用方案
     *
     * 将方案记录为当前激活方案（根据模式更新 AppConfig），
     * 并发送 EventBus 事件通知界面刷新底栏效果。
     *
     * @param entry 要应用的方案
     */
    fun apply(entry: NavigationBarEntry) {
        val config = entry.config

        // 记录当前激活的方案
        if (config.isNightMode) {
            AppConfig.activeNavigationBarNight = entry.dirName
        } else {
            AppConfig.activeNavigationBarDay = entry.dirName
        }

        // 发送事件通知主界面刷新
        postEvent(EventBus.NAVIGATION_BAR_CHANGED, config.isNightMode)
    }
}