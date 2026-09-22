package io.legado.app.help.config

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.LruCache
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.help.DefaultData
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.ThemeTransition
import io.legado.app.model.BookCover
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import androidx.core.content.edit
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.stackBlur
import splitties.init.appCtx
import java.io.File
import androidx.core.graphics.drawable.toDrawable
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

@Keep
object ThemeConfig {
    const val configFileName = "themeConfig.json"
    val configFilePath = FileUtils.getPath(appCtx.filesDir, configFileName)

    /** 单线程调度器，保证所有写操作串行执行，避免并发竞态 */
    private val ioSerialDispatcher = Dispatchers.IO.limitedParallelism(1)

    val configList: ArrayList<Config> by lazy {
        val cList = getConfigs() ?: DefaultData.themeConfigs
        ArrayList(cList)
    }

    private var needClearImg = true

    private val recreateHandler = Handler(Looper.getMainLooper())

    /** 连续编辑（颜色选择器、滑条等）的重建合并窗口：把一串修改合并成一次重建 */
    private const val recreateEditDelay = 500L

    /** 已排队广播的延迟，-1 表示当前没有排队，见 [notifyRecreate] */
    private var recreatePendingDelay = -1L

    private val recreateRunnable = Runnable {
        recreatePendingDelay = -1L
        postEvent(EventBus.RECREATE, "")
    }

    /**
     * 请求重建界面（广播 [EventBus.RECREATE]）。
     *
     * @param immediate 一次性动作（日夜切换、跟随系统时系统翻转）传 true：下一次主线程消息即广播，
     *                  不让用户在切换后干等；连续编辑保持默认 false：尾沿防抖 [recreateEditDelay]，
     *                  把一串修改合并成一次重建，避免每拖一下颜色/滑条都重建整个页面。
     *
     * 历史（勿随手改回固定长窗口）：2026-09-10 的「重建风暴」修复曾让所有请求统一走 1500ms 尾沿防抖，
     * 结果是**整次日夜切换被推迟 1.5s 才起步**（用户侧就是"切个主题要等好几秒"），
     * 且在分批送达配置变化的 ROM 上窗口还会被持续顺延。风暴真正的根因是
     * `App.onConfigurationChanged` 又走一次 `applyDayNight` 的反馈环，它已在同一轮修复里断开；
     * 剩下的那对重复请求（App 主动切换 + 随后送来的配置变化回声）改由 [consumeNightModeEcho]
     * 在源头过滤，因此切换路径不再需要靠时间窗来防止重复广播。
     */
    fun notifyRecreate(immediate: Boolean = false) {
        val delay = if (immediate) 0L else recreateEditDelay
        // 已排队的广播只会被更早的时刻提前，不会被后来的请求推迟（合并编辑的同时不拖慢切换）
        if (recreatePendingDelay in 0 until delay) return
        recreateHandler.removeCallbacks(recreateRunnable)
        recreatePendingDelay = delay
        recreateHandler.postDelayed(recreateRunnable, delay)
    }

    /** 本 App 主动发起的日夜切换目标值（仅在实际会引发配置变化时非空），见 [consumeNightModeEcho] */
    private var requestedNightMode: Boolean? = null

    /**
     * 记下「本次日夜切换由 App 主动发起」，供随后的配置变化回调识别自身回声。
     *
     * 只在目标日夜状态与当前配置不同（即确实会收到配置变化回调）时才记录：否则会留下一个陈旧标记，
     * 把后来真正的系统翻转（跟随系统）误判成回声，漏掉那次重建。
     * 必须在 [initNightMode] 改动配置之前调用。
     */
    private fun markNightModeRequested(context: Context) {
        val configNight =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        requestedNightMode = AppConfig.isNightTheme.takeIf { it != configNight }
    }

    /**
     * 消费一次「本 App 主动切换」标记，返回这条配置变化是否为我们自己切换产生的回声。
     *
     * 回声对应的重建请求已在 [applyDayNight] 里发过，再发一次就是重复重建窗口（「重建风暴」的构成之一）。
     * 标记只消费一次，且无论结果如何都会清空：跟随系统时由系统翻转触发的回调、或标记已用掉时
     * 一律返回 false，调用方仍会正常请求重建——最坏情况只是多一次重建，绝不会漏掉重建。
     */
    fun consumeNightModeEcho(night: Boolean): Boolean {
        val requested = requestedNightMode
        requestedNightMode = null
        return requested == night
    }

    fun getTheme() = when {
        AppConfig.isEInkMode -> Theme.EInk
        AppConfig.isNightTheme -> Theme.Dark
        else -> Theme.Light
    }

    fun isDarkTheme(): Boolean = getTheme() == Theme.Dark

    fun applyDayNight(context: Context) {
        // 过渡起点（上一轮显示的颜色/背景图）只能在主题改动前快照，且必须早于任何表面按新主题落地
        ThemeTransition.notifyThemeChanged()
        applyTheme(context)
        // 必须在 initNightMode 之前判断：之后配置就已变成新值，无从区分"会否真的收到配置变化回调"
        markNightModeRequested(context)
        initNightMode()
        BookCover.upDefaultCover()
        // 日夜切换是一次性动作：立即请求重建，别让用户在切换后干等（旧版 1500ms 尾沿防抖的代价）
        notifyRecreate(immediate = true)
    }

    fun applyDayNightInit(context: Context) {
        applyTheme(context)
        // 启动时按用户设置设定模式，随后送来的配置变化回调同样是自家回声：
        // 若不标记，启动瞬间就会请求一次重建（还会被 LiveEventBus 粘住，让刚订阅的主界面白重建一次）
        markNightModeRequested(context)
        initNightMode()
    }

    private fun initNightMode() {
        val targetMode =
            if (AppConfig.isNightTheme) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        AppCompatDelegate.setDefaultNightMode(targetMode)
    }

    /**
     * 获取链接获取图片文件名
     */
    fun getUrlToFile(url: String): String {
        val suffix = when {
            url.contains(".9.png", ignoreCase = true) -> ".9.png"
            url.contains(".png", ignoreCase = true) -> ".png"
            url.contains(".gif", ignoreCase = true) -> ".gif"
            url.contains("webp", ignoreCase = true) -> ".webp"
            else -> ".jpg"
        }
        return MD5Utils.md5Encode16(url) + suffix
    }

    fun getBgImage(context: Context, metrics: DisplayMetrics): Drawable? {
        val themeMode = getTheme()
        val preferenceKey = when (themeMode) {
            Theme.Light -> PreferKey.bgImage
            Theme.Dark -> PreferKey.bgImageN
            else -> return null
        }
        var path = context.getPrefString(preferenceKey)
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) {
            val name = getUrlToFile(path)
            val fileRoot = context.externalFiles
            val filePath = FileUtils.getPath(fileRoot, preferenceKey, name)
            if (!FileUtils.exist(filePath)) {
                appCtx.toastOnUi("未缓存在线背景图\n请重新应用主题")
                return null
            }
            path = filePath
        } else if (!path.contains(File.separator)) {
            // 只有文件名，拼接完整路径
            val filePath = FileUtils.getPath(context.externalFiles, preferenceKey, path)
            if (FileUtils.exist(filePath)) {
                path = filePath
            } else {
                return null
            }
        }
        if (path.endsWith(".9.png")) {
            val bgDrawable = BitmapUtils.decodeNinePatchDrawable(path)
            return bgDrawable
        }
        val bgImgBlu = when (themeMode) {
            Theme.Light -> context.getPrefInt(PreferKey.bgImageBlurring, 0)
            Theme.Dark -> context.getPrefInt(PreferKey.bgImageNBlurring, 0)
            else -> 0
        }
        // 当 Activity 在后台重建时 windowSize 可能为 0，使用屏幕真实尺寸作为兜底
        val safeWidth = if (metrics.widthPixels > 0) metrics.widthPixels else context.resources.displayMetrics.widthPixels
        val safeHeight = if (metrics.heightPixels > 0) metrics.heightPixels else context.resources.displayMetrics.heightPixels
        val bgImage = BitmapUtils
            .decodeBitmap(path, safeWidth, safeHeight)
        if (bgImgBlu == 0) {
            return bgImage?.toDrawable(context.resources)
        }
        return bgImage?.stackBlur(bgImgBlu)?.toDrawable(context.resources)
    }

    // ==================== 背景图进程级缓存（消除重建时的纯色闪烁） ====================

    /** 背景图签名 → 解码结果缓存。整屏 ARGB 位图约 10MB/张，最多保留 2 张（当前 + 上一张切换占位） */
    private val bgDrawableCache = object : LruCache<String, Drawable>(2) {}

    /** 最近一次成功解码的背景图（签名 + Drawable），未命中缓存时用作解码期间的占位 */
    @Volatile
    private var lastBgImage: Pair<String, Drawable>? = null

    /**
     * 计算当前主题背景的签名，取图逻辑与 [getBgImage] 保持一致。
     * 纳入主题模式（日/夜）、背景路径、文件最后修改时间与大小、模糊强度，
     * 任一变化都会使签名不同而触发重新解码。
     * 未配置背景图、或配置的图片文件不存在时返回 null（此时应显示纯色底，
     * 不得使用占位图）。
     */
    fun getBackgroundSignature(context: Context): String? {
        val night = AppConfig.isNightTheme
        val prefKey = if (night) PreferKey.bgImageN else PreferKey.bgImage
        val rawPath = context.getPrefString(prefKey).orEmpty()
        if (rawPath.isBlank()) return null
        // 与 getBgImage 相同：在线背景需先落到缓存文件，仅文件名的需拼接完整路径，
        // 绝对路径须为真实存在的文件，否则视为无背景图
        val path = if (rawPath.startsWith("http")) {
            val filePath = FileUtils.getPath(context.externalFiles, prefKey, getUrlToFile(rawPath))
            filePath.takeIf { FileUtils.exist(it) }
        } else if (!rawPath.contains(File.separator)) {
            val filePath = FileUtils.getPath(context.externalFiles, prefKey, rawPath)
            filePath.takeIf { FileUtils.exist(it) }
        } else {
            rawPath.takeIf { File(it).isFile }
        }
        if (path == null) return null
        val blurring = context.getPrefInt(
            if (night) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring,
            0,
        )
        val file = File(path)
        return "bg:$prefKey:${file.absolutePath}:${file.lastModified()}:${file.length()}:$blurring"
    }

    /** 命中缓存返回独立副本（mutate），避免多窗口共享同一 Drawable 实例导致状态冲突 */
    fun getCachedBgImage(signature: String): Drawable? =
        bgDrawableCache.get(signature)?.constantState?.newDrawable()?.mutate()

    /** 缓存解码结果 */
    fun cacheBgImage(signature: String, drawable: Drawable) {
        bgDrawableCache.put(signature, drawable)
        lastBgImage = signature to drawable
    }

    /** 最近一次应用的背景图（排除指定签名），供未命中缓存时作占位，返回独立副本 */
    fun getLastBgImage(excludeSignature: String): Drawable? =
        lastBgImage?.takeIf { it.first != excludeSignature }
            ?.second?.constantState?.newDrawable()?.mutate()

    suspend fun upConfig() {
        addConfigs(DefaultData.themeConfigs)
    }

    /**
     * 升级默认主题配置
     * 检查用户是否使用旧的默认主题，如果是则更新为新的默认主题
     */
    fun upDefaultThemeConfig() {
        val context = appCtx

        val dayThemeName = context.getPrefString(PreferKey.dThemeName)
        val nightThemeName = context.getPrefString(PreferKey.dNThemeName)

        val newDayPrimary = context.getCompatColor(R.color.default_primary)
        val newDayAccent = context.getCompatColor(R.color.default_accent)
        val newDayBackground = context.getCompatColor(R.color.default_background)
        val newDayBottomBackground = context.getCompatColor(R.color.default_bottom_background)
        val newNightPrimary = context.getCompatColor(R.color.default_night_primary)
        val newNightAccent = context.getCompatColor(R.color.default_night_accent)
        val newNightBackground = context.getCompatColor(R.color.default_night_background)
        val newNightBottomBackground = context.getCompatColor(R.color.default_night_bottom_background)

        if (dayThemeName == "默认") {
            context.putPrefInt(PreferKey.cPrimary, newDayPrimary)
            context.putPrefInt(PreferKey.cAccent, newDayAccent)
            context.putPrefInt(PreferKey.cBackground, newDayBackground)
            context.putPrefInt(PreferKey.cBBackground, newDayBottomBackground)
            context.putPrefString(PreferKey.dThemeName, "红柚白")
        }

        if (nightThemeName == "默认") {
            context.putPrefInt(PreferKey.cNPrimary, newNightPrimary)
            context.putPrefInt(PreferKey.cNAccent, newNightAccent)
            context.putPrefInt(PreferKey.cNBackground, newNightBackground)
            context.putPrefInt(PreferKey.cNBBackground, newNightBottomBackground)
            context.putPrefString(PreferKey.dNThemeName, "A屏黑")
        }
    }

    suspend fun replaceConfigs(newConfigs: List<Config>?) {
        val validConfigs = newConfigs?.filter { validateConfig(it) } ?: emptyList()
        configList.clear()
        configList.addAll(validConfigs)
        save()
    }

    suspend fun save() {
        val json = GSON.toJson(configList)
        withContext(ioSerialDispatcher) {
            FileUtils.delete(configFilePath)
            FileUtils.createFileIfNotExist(configFilePath).writeText(json)
        }
    }

    suspend fun delConfig(index: Int) {
        configList.removeAt(index)
        save()
    }

    suspend fun toTopConfigs(positions: List<Int>) {
        val configs = positions.map { configList[it] }
        positions.sortedDescending().forEach { configList.removeAt(it) }
        configList.addAll(0, configs)
        save()
    }

    suspend fun addConfig(json: String): Int {
        val trimmedJson = json.trim { it < ' ' }
        var count = 0
        GSON.fromJsonArray<Config>(trimmedJson).getOrNull()?.let { configs ->
            configs.forEach { config ->
                if (validateConfig(config)) {
                    addConfig(config)
                    count++
                }
            }
            return count
        }
        trimmedJson.lines().forEach { line ->
            val lineTrimmed = line.trim()
            if (lineTrimmed.isNotEmpty() && lineTrimmed.startsWith("{")) {
                GSON.fromJsonObject<Config>(lineTrimmed).getOrNull()?.let { config ->
                    if (validateConfig(config)) {
                        addConfig(config)
                        count++
                    }
                }
            }
        }
        if (count == 0) {
            GSON.fromJsonObject<Config>(trimmedJson).getOrNull()?.let {
                if (validateConfig(it)) {
                    addConfig(it)
                    count = 1
                }
            }
        }
        return count
    }

    suspend fun addConfig(newConfig: Config) {
        if (!validateConfig(newConfig)) {
            return
        }
        var hasTheme = false
        configList.forEachIndexed { index, config ->
            if (newConfig.themeName == config.themeName && newConfig.isNightTheme == config.isNightTheme) {
                configList[index] = newConfig
                hasTheme = true
                return@forEachIndexed
            }
        }
        if (!hasTheme) {
            configList.add(newConfig)
        }
        save()
    }

    suspend fun addConfigs(newConfigs: List<Config>?) {
        val newConfigs = newConfigs?.filter {
            validateConfig(it)
        }
        if (newConfigs.isNullOrEmpty()) {
            return
        }
        newConfigs.forEach { newConfig ->
            val existingIndex = configList.indexOfFirst {
                it.themeName == newConfig.themeName && it.isNightTheme == newConfig.isNightTheme
            }
            if (existingIndex != -1) {
                configList[existingIndex] = newConfig
            } else {
                configList.add(newConfig)
            }
        }
        save()
    }

    private fun validateConfig(config: Config): Boolean {
        try {
            config.primaryColor.toColorInt()
            config.accentColor.toColorInt()
            config.backgroundColor.toColorInt()
            config.bottomBackground.toColorInt()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun getConfigs(): List<Config>? {
        val configFile = File(configFilePath)
        if (configFile.exists()) {
            kotlin.runCatching {
                val json = configFile.readText()
                return GSON.fromJsonArray<Config>(json).getOrThrow()
            }.onFailure {
                it.printOnDebug()
            }
        }
        return null
    }

    fun applyConfig(context: Context, config: Config, applyNow: Boolean = true) {
        try {
            if (needClearImg) {
                needClearImg = false
                clearBg(context)
            }
            val primary = config.primaryColor.toColorInt()
            val accent = config.accentColor.toColorInt()
            val background = config.backgroundColor.toColorInt()
            val bBackground = config.bottomBackground.toColorInt()
            val isNightTheme = config.isNightTheme
            val transparentNavBar = config.transparentNavBar
            val backgroundPath = config.backgroundImgPath
            if (backgroundPath != null && backgroundPath.startsWith("http")) {
                val fileRoot = context.externalFiles
                val preferenceKey = if (isNightTheme) {
                    PreferKey.bgImageN
                } else {
                    PreferKey.bgImage
                }
                val name = getUrlToFile(backgroundPath)
                val fileFold = File(fileRoot, preferenceKey)
                if (!fileFold.exists()) {
                    fileFold.mkdirs()
                }
                val fileImg = File(fileFold, name)
                if (!fileImg.exists()) {
                    appCtx.toastOnUi("下载背景图片中...")
                    Coroutine.async {
                        kotlin.runCatching {
                            val res = okHttpClient.newCallResponse(0) {
                                url(backgroundPath)
                            }
                            res.body.byteStream().use { inputStream ->
                                FileOutputStream(fileImg).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }.onSuccess {
                            appCtx.toastOnUi("背景图下载成功\n请重新应用主题")
                        }.onFailure {
                            appCtx.toastOnUi(it.localizedMessage)
                        }
                    }
                    return
                }
            }
            val backgroundBlur = config.backgroundImgBlur
            // 批量写入：合并为单次 edit()，减少 8 次 Editor 创建 + 8 次磁盘调度为 1 次
            context.defaultSharedPreferences.edit {
                if (isNightTheme) {
                    putString(PreferKey.dNThemeName, config.themeName)
                    putInt(PreferKey.cNPrimary, primary)
                    putInt(PreferKey.cNAccent, accent)
                    putInt(PreferKey.cNBackground, background)
                    putInt(PreferKey.cNBBackground, bBackground)
                    putBoolean(PreferKey.tNavBarN, transparentNavBar)
                    putString(PreferKey.bgImageN, backgroundPath)
                    putInt(PreferKey.bgImageNBlurring, backgroundBlur)
                } else {
                    putString(PreferKey.dThemeName, config.themeName)
                    putInt(PreferKey.cPrimary, primary)
                    putInt(PreferKey.cAccent, accent)
                    putInt(PreferKey.cBackground, background)
                    putInt(PreferKey.cBBackground, bBackground)
                    putBoolean(PreferKey.tNavBar, transparentNavBar)
                    putString(PreferKey.bgImage, backgroundPath)
                    putInt(PreferKey.bgImageBlurring, backgroundBlur)
                }
            }
            if (applyNow) {
                AppConfig.isNightTheme = isNightTheme
                applyDayNight(context)
            }
        } catch (e: Exception) {
            AppLog.put("设置主题出错\n$e", e, true)
        }
    }

    fun getDurConfig(context: Context): Config {
        val isNight = AppConfig.isNightTheme
        val name = if (isNight) {
            context.getPrefString(PreferKey.dNThemeName) ?: ""
        } else {
            context.getPrefString(PreferKey.dThemeName) ?: ""
        }
        return if (isNight) {
            getNightTheme(context, name)
        } else {
            getDayTheme(context, name)
        }
    }

    private fun getDayTheme(context: Context, name: String): Config {
        val primary =
            context.getPrefInt(PreferKey.cPrimary, context.getCompatColor(R.color.default_primary))
        val accent =
            context.getPrefInt(PreferKey.cAccent, context.getCompatColor(R.color.default_accent))
        val background =
            context.getPrefInt(PreferKey.cBackground, context.getCompatColor(R.color.default_background))
        val bBackground =
            context.getPrefInt(PreferKey.cBBackground, context.getCompatColor(R.color.default_bottom_background))
        val transparentNavBar =
            context.getPrefBoolean(PreferKey.tNavBar, false)
        val bgImgPath =
            context.getPrefString(PreferKey.bgImage)
        val bgImgBlur =
            context.getPrefInt(PreferKey.bgImageBlurring, 0)

        return Config(
            themeName = name,
            isNightTheme = false,
            primaryColor = "#${primary.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${background.hexString}",
            bottomBackground = "#${bBackground.hexString}",
            transparentNavBar = transparentNavBar,
            backgroundImgPath = bgImgPath,
            backgroundImgBlur = bgImgBlur,
        )
    }

    suspend fun saveDayTheme(context: Context, name: String) {
        val config = getDayTheme(context, name)
        addConfig(config)
    }

    private fun getNightTheme(context: Context, name: String): Config {
        val primary =
            context.getPrefInt(
                PreferKey.cNPrimary,
                context.getCompatColor(R.color.default_night_primary),
            )
        val accent =
            context.getPrefInt(
                PreferKey.cNAccent,
                context.getCompatColor(R.color.default_night_accent),
            )
        val background =
            context.getPrefInt(PreferKey.cNBackground, context.getCompatColor(R.color.default_night_background))
        val bBackground =
            context.getPrefInt(PreferKey.cNBBackground, context.getCompatColor(R.color.default_night_bottom_background))
        val transparentNavBar =
            context.getPrefBoolean(PreferKey.tNavBarN, false)
        val bgImgPath =
            context.getPrefString(PreferKey.bgImageN)
        val bgImgBlur =
            context.getPrefInt(PreferKey.bgImageNBlurring, 0)
        return Config(
            themeName = name,
            isNightTheme = true,
            primaryColor = "#${primary.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${background.hexString}",
            bottomBackground = "#${bBackground.hexString}",
            transparentNavBar = transparentNavBar,
            backgroundImgPath = bgImgPath,
            backgroundImgBlur = bgImgBlur,
        )
    }

    suspend fun saveNightTheme(context: Context, name: String) {
        val config = getNightTheme(context, name)
        addConfig(config)
    }

    /**
     * 更新主题
     */
    fun applyTheme(context: Context) = with(context) {
        when {
            AppConfig.isEInkMode -> {
                ThemeStore.editTheme(this)
                    .primaryColor(Color.WHITE)
                    .accentColor(Color.BLACK)
                    .backgroundColor(Color.WHITE)
                    .bottomBackground(Color.WHITE)
                    .transparentNavBar(false)
                    .apply()
            }

            AppConfig.isNightTheme -> {
                val primary =
                    getPrefInt(PreferKey.cNPrimary, getCompatColor(R.color.default_night_primary))
                val accent =
                    getPrefInt(PreferKey.cNAccent, getCompatColor(R.color.default_night_accent))
                var background =
                    getPrefInt(PreferKey.cNBackground, getCompatColor(R.color.default_night_background))
                if (ColorUtils.isColorLight(background)) {
                    background = getCompatColor(R.color.default_night_background)
                    putPrefInt(PreferKey.cNBackground, background)
                }
                val bBackground =
                    getPrefInt(PreferKey.cNBBackground, getCompatColor(R.color.default_night_bottom_background))
                val transparentNavBar =
                    getPrefBoolean(PreferKey.tNavBarN, false)
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(ColorUtils.withAlpha(background, 1f))
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .transparentNavBar(transparentNavBar)
                    .apply()
            }

            else -> {
                val primary =
                    getPrefInt(PreferKey.cPrimary, getCompatColor(R.color.default_primary))
                val accent =
                    getPrefInt(PreferKey.cAccent, getCompatColor(R.color.default_accent))
                var background =
                    getPrefInt(PreferKey.cBackground, getCompatColor(R.color.default_background))
                if (!ColorUtils.isColorLight(background)) {
                    background = getCompatColor(R.color.default_background)
                    putPrefInt(PreferKey.cBackground, background)
                }
                val bBackground =
                    getPrefInt(PreferKey.cBBackground, getCompatColor(R.color.default_bottom_background))
                val transparentNavBar =
                    getPrefBoolean(PreferKey.tNavBar, false)
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(ColorUtils.withAlpha(background, 1f))
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .transparentNavBar(transparentNavBar)
                    .apply()
            }
        }
    }

    fun clearBg(context: Context) {
        val (nightConfigs, dayConfigs) = configList.partition { it.isNightTheme }
        val fileRoot = context.externalFiles
        val nightBackgroundImgPaths = nightConfigs.mapNotNull {
            val path = it.backgroundImgPath ?: return@mapNotNull null
            if (path.startsWith("http")) {
                val name = getUrlToFile(path)
                FileUtils.getPath(fileRoot, PreferKey.bgImageN, name)
            } else {
                path
            }
        }
        val dayBackgroundImgPaths = dayConfigs.mapNotNull {
            val path = it.backgroundImgPath ?: return@mapNotNull null
            if (path.startsWith("http")) {
                val name = getUrlToFile(path)
                FileUtils.getPath(fileRoot, PreferKey.bgImage, name)
            } else {
                path
            }
        }
        appCtx.externalFiles.getFile(PreferKey.bgImage).listFiles()?.forEach {
            if (!dayBackgroundImgPaths.contains(it.absolutePath)) {
                it.delete()
            }
        }
        appCtx.externalFiles.getFile(PreferKey.bgImageN).listFiles()?.forEach {
            if (!nightBackgroundImgPaths.contains(it.absolutePath)) {
                it.delete()
            }
        }
    }

    @Keep
    data class Config(
        var themeName: String,
        var isNightTheme: Boolean,
        var primaryColor: String,
        var accentColor: String,
        var backgroundColor: String,
        var bottomBackground: String,
        var transparentNavBar: Boolean,
        var backgroundImgPath: String?,
        var backgroundImgBlur: Int,
    ) {

        override fun hashCode(): Int = GSON.toJson(this).hashCode()

        override fun equals(other: Any?): Boolean {
            other ?: return false
            if (other is Config) {
                return other.themeName == themeName &&
                    other.isNightTheme == isNightTheme &&
                    other.primaryColor == primaryColor &&
                    other.accentColor == accentColor &&
                    other.backgroundColor == backgroundColor &&
                    other.bottomBackground == bottomBackground &&
                    other.transparentNavBar == transparentNavBar &&
                    other.backgroundImgPath == backgroundImgPath &&
                    other.backgroundImgBlur == backgroundImgBlur
            }
            return false
        }

        fun toMap() = mapOf(
            "themeName" to themeName,
            "isNightTheme" to isNightTheme,
            "primaryColor" to primaryColor,
            "accentColor" to accentColor,
            "backgroundColor" to backgroundColor,
            "bottomBackground" to bottomBackground,
            "transparentNavBar" to transparentNavBar,
            "backgroundImgPath" to backgroundImgPath,
            "backgroundImgBlur" to backgroundImgBlur,
        )
    }
}
