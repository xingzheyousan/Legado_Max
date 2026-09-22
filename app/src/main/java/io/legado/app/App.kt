package io.legado.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import com.github.liuyueyi.quick.transfer.constants.TransType
import com.jeremyliao.liveeventbus.LiveEventBus
import com.jeremyliao.liveeventbus.logger.DefaultLogger
import com.script.rhino.ReadOnlyJavaObject
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.RhinoWrapFactory
import io.legado.app.utils.log.TimberLog
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.AppConst.channelIdDownload
import io.legado.app.constant.AppConst.channelIdReadAloud
import io.legado.app.constant.AppConst.channelIdWeb
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.AppWebDav
import io.legado.app.help.CrashHandler
import io.legado.app.help.DefaultData
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.RuleBigDataHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookshelfMatcher
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig.applyDayNightInit
import io.legado.app.help.config.ThemeConfig.applyTheme
import io.legado.app.help.config.ThemeConfig.consumeNightModeEcho
import io.legado.app.help.config.ThemeConfig.notifyRecreate
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.Cronet
import io.legado.app.help.http.ObsoleteUrlFactory
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.rhino.NativeBaseSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceRecycleBinHelp
import io.legado.app.help.storage.Backup
import io.legado.app.lib.theme.ThemeTransition
import io.legado.app.model.BookCover
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.LogUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.isDebuggable
import kotlinx.coroutines.launch
import org.chromium.base.ThreadUtils
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.net.URL
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import java.util.logging.Level

@HiltAndroidApp
class App : Application() {

    private lateinit var oldConfig: Configuration

    override fun onCreate() {
        super.onCreate()
        CrashHandler(this)
        if (isDebuggable) {
            ThreadUtils.setThreadAssertsDisabledForTesting(true)
        }
        // 初始化 Timber 日志框架
        TimberLog.init(this, isDebuggable)
        oldConfig = Configuration(resources.configuration)
        applyDayNightInit(this)
        LauncherIconHelp.fixLauncherIconPref()
        AppConfig.migrateClipboardImportMode()
        registerActivityLifecycleCallbacks(LifecycleHelp)
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(AppConfig)
        Coroutine.async {
            LogUtils.init(this@App)
            LogUtils.d("App", "onCreate")
            LogUtils.logDeviceInfo()
            // 预下载Cronet so
            Cronet.preDownload()
            createNotificationChannels()
            // LiveEventBus 全局配置：基于 LiveData 的事件总线，用于跨组件通信
            LiveEventBus.config()
                .lifecycleObserverAlwaysActive(true) // 观察者始终活跃，不受 Lifecycle 状态限制（后台也能收到事件）
                .autoClear(false) // 不自动清除粘性事件，新订阅者仍可收到最近一次事件
                .enableLogger(BuildConfig.DEBUG || AppConfig.recordLog)
                .setLogger(EventLogger()) // 将 LiveEventBus 日志桥接到项目 LogUtils
            DefaultData.upVersion()
            AppFreezeMonitor.init(this@App)
            DispatchersMonitor.init()
            URL.setURLStreamHandlerFactory(ObsoleteUrlFactory(okHttpClient))
            launch { installGmsTlsProvider(appCtx) }
            initRhino()
            // 初始化封面
            BookCover.toString()
            // 初始化书架匹配器（轻量查询，全局共享）
            BookshelfMatcher.start()
            // 清除过期数据
            appDb.cacheDao.clearDeadline(System.currentTimeMillis())
            if (getPrefBoolean(PreferKey.autoClearExpired, true)) {
                val clearTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                appDb.searchBookDao.clearExpired(clearTime)
            }
            RuleBigDataHelp.clearInvalid()
            BookHelp.clearInvalidCache()
            Backup.clearCache()
            SourceRecycleBinHelp.cleanupExpired()
            ReadBookConfig.clearBgAndCache()
//            ThemeConfig.clearBg() //每次手动切换主题时清理多余图片
            // 初始化简繁转换引擎
            when (AppConfig.chineseConverterType) {
                1 -> {
                    ChineseUtils.fixT2sDict()
                    ChineseUtils.preLoad(true, TransType.TRADITIONAL_TO_SIMPLE)
                }

                2 -> ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TRADITIONAL)
            }
            // 调整排序序号
            SourceHelp.adjustSortNumber()
            // 同步阅读记录
            if (AppConfig.syncBookProgress) {
                AppWebDav.downloadAllBookProgress()
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(base))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val diff = newConfig.diff(oldConfig)
        if ((diff and ActivityInfo.CONFIG_UI_MODE) != 0) {
            val oldNight = oldConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val newNight = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
            // App 自己发起的日夜切换（applyDayNight）随后也会把配置变化送来这里，这条回调是它的回声：
            // 那次的重建请求与过渡标记都已在 applyDayNight 里发过，再发一次就是重复重建窗口
            // （「重建风暴」的构成之一）。必须无条件消费标记，否则陈旧标记会把后来的系统翻转误判成回声。
            val echo = consumeNightModeEcho(newNight == Configuration.UI_MODE_NIGHT_YES)
            // 昼夜位真的翻转（且不是自己的回声）时标记一次主题过渡，供主界面在重建后把背景与底栏
            // 放进同一条动画时间轴。用昼夜位而非 diff 判定是关键：过渡起点要在主题改动前快照，
            // 拿不到旧颜色的回调不能再覆盖它。
            if (oldNight != newNight && !echo) {
                ThemeTransition.notifyThemeChanged()
            }
            // 模式此时已生效，不能再次 setDefaultNightMode/applyDayNight，
            // 否则会再次触发配置变化，形成「RECREATE 广播风暴」（见 docs/archive/主题列表应用主题后UI卡死根因分析）
            applyTheme(this)
            if (!echo) {
                // 系统翻转（跟随系统）同样是一次性动作，与手动切换一样立即重建
                notifyRecreate(immediate = true)
            }
        }
        oldConfig = Configuration(newConfig)
    }

    /**
     * 尝试在安装了GMS的设备上(GMS或者MicroG)使用GMS内置的Conscrypt
     * 作为首选JCE提供程序，而使Okhttp在低版本Android上
     * 能够启用TLSv1.3
     * https://f-droid.org/zh_Hans/2020/05/29/android-updates-and-tls-connections.html
     * https://developer.android.google.cn/reference/javax/net/ssl/SSLSocket
     *
     * @param context
     * @return
     */
    private fun installGmsTlsProvider(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return
        }
        try {
            val gmsPackageName = "com.google.android.gms"
            val appInfo = packageManager.getApplicationInfo(gmsPackageName, 0)
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                return
            }
            val gms = context.createPackageContext(
                gmsPackageName,
                CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY,
            )
            gms.classLoader
                .loadClass("com.google.android.gms.common.security.ProviderInstallerImpl")
                .getMethod("insertProvider", Context::class.java)
                .invoke(null, gms)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 创建通知ID
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val downloadChannel = NotificationChannel(
            channelIdDownload,
            getString(R.string.action_download),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val readAloudChannel = NotificationChannel(
            channelIdReadAloud,
            getString(R.string.read_aloud),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val webChannel = NotificationChannel(
            channelIdWeb,
            getString(R.string.web_service),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        // 向notification manager 提交channel
        notificationManager.createNotificationChannels(
            listOf(
                downloadChannel,
                readAloudChannel,
                webChannel,
            ),
        )
    }

    private fun initRhino() {
        RhinoScriptEngine
        RhinoWrapFactory.register(BookSource::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(RssSource::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(HttpTTS::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(ExploreRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(SearchRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(BookInfoRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(ContentRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(BookChapter::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(Book.ReadConfig::class.java, ReadOnlyJavaObject.factory)
    }

    class EventLogger : DefaultLogger() {

        override fun log(level: Level, msg: String) {
            super.log(level, msg)
            LogUtils.d(TAG, msg)
        }

        override fun log(level: Level, msg: String, th: Throwable?) {
            super.log(level, msg, th)
            LogUtils.d(TAG, "$msg\n${th?.stackTraceToString()}")
        }

        companion object {
            private const val TAG = "[LiveEventBus]"
        }
    }

    companion object {
        init {
            if (BuildConfig.DEBUG) {
                System.setProperty("kotlinx.coroutines.debug", "on")
            }
        }
    }
}
