package io.legado.app.help.config

import android.os.Build
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import splitties.init.appCtx

/**
 * 页眉/页脚模板渲染所需的动态值。
 */
data class ReaderInfoValues(
    val bookName: String = "",
    val chapterTitle: String = "",
    val time: String = "",
    val battery: Int = 0,
    val page: String = "",
    val totalPages: String = "",
    val readProgress: String = "",
    val chapter: String = "",
    val totalChapters: String = "",
)

/**
 * 模板解析结果片段。
 *
 * [BatteryIcon.showLevel] 为 true 时在图标内部绘制电量数字（对应旧规则"电量(图标内数字)"），
 * 为 false 时按电量填充图标。
 */
sealed interface ReaderInfoPart {
    data class Text(val value: String) : ReaderInfoPart
    data class BatteryIcon(val level: Int, val showLevel: Boolean = false) : ReaderInfoPart
}

/**
 * 页眉/页脚信息模板。
 *
 * 每个位置保存一段模板文本，其中可混排普通文字与占位符。占位符同时识别中文与英文两种写法，
 * 历史版本保存的枚举规则（[ReadTipConfig] 的 Int 常量）通过 [fromLegacy] 转成等价模板，
 * 因此旧配置无需迁移即可继续生效。
 */
object ReaderInfoTemplate {

    // 中文占位符
    const val BOOK_NAME = "{书名}"
    const val CHAPTER_TITLE = "{章节}"
    const val TIME = "{时间}"
    const val BATTERY = "{电量}"
    const val BATTERY_ICON = "{电量图标}"
    const val BATTERY_ICON_LEVEL = "{电量图标数字}"
    const val PAGE = "{页码}"
    const val TOTAL_PAGES = "{总页数}"
    const val READ_PROGRESS = "{阅读进度}"
    const val CHAPTER = "{章节序号}"
    const val TOTAL_CHAPTERS = "{章节总数}"

    // 英文别名，与中文占位符等价
    const val BOOK_NAME_EN = "{bookName}"
    const val CHAPTER_TITLE_EN = "{chapterTitle}"
    const val TIME_EN = "{time}"
    const val BATTERY_EN = "{battery}"
    const val BATTERY_ICON_EN = "{batteryIcon}"
    const val BATTERY_ICON_LEVEL_EN = "{batteryIconLevel}"
    const val PAGE_EN = "{page}"
    const val TOTAL_PAGES_EN = "{totalPages}"
    const val READ_PROGRESS_EN = "{readProgress}"
    const val CHAPTER_EN = "{chapter}"
    const val TOTAL_CHAPTERS_EN = "{totalChapters}"

    private val chinesePlaceholders = listOf(
        BOOK_NAME, CHAPTER_TITLE, TIME, BATTERY, BATTERY_ICON, BATTERY_ICON_LEVEL,
        PAGE, TOTAL_PAGES, READ_PROGRESS, CHAPTER, TOTAL_CHAPTERS,
    )

    private val englishPlaceholders = listOf(
        BOOK_NAME_EN, CHAPTER_TITLE_EN, TIME_EN, BATTERY_EN, BATTERY_ICON_EN,
        BATTERY_ICON_LEVEL_EN, PAGE_EN, TOTAL_PAGES_EN, READ_PROGRESS_EN,
        CHAPTER_EN, TOTAL_CHAPTERS_EN,
    )

    /**
     * 模板编辑器中展示、可一键插入的占位符，随界面语言切换中文/英文写法。
     *
     * 两组写法 [parse] 都识别，这里只决定给用户看哪一套；繁中界面同样给中文写法，
     * 因为占位符是解析用的标记，必须与 [parse] 识别的字面量完全一致。
     */
    val placeholders: List<String>
        get() = if (isChineseUi()) chinesePlaceholders else englishPlaceholders

    /**
     * 是否中文界面。
     *
     * 必须读 [PreferKey.language]：App 内语言是通过 `AppContextWrapper.createConfigurationContext`
     * 包装 Activity 生效的，而这里的 [appCtx] 是未包装的 Application Context，
     * 它的 `resources.configuration` 仍是系统语言，判断不出用户设置的英文。
     */
    private fun isChineseUi(): Boolean {
        return when (appCtx.getPrefString(PreferKey.language)) {
            "zh", "tw" -> true
            "en" -> false
            // 跟随系统时按系统语言判断
            else -> {
                val configuration = appCtx.resources.configuration
                val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    configuration.locales[0]
                } else {
                    @Suppress("DEPRECATION")
                    configuration.locale
                }
                locale.language == "zh"
            }
        }
    }

    /**
     * 把模板文本切成普通文字与电量图标片段。
     *
     * 未识别的 `{...}` 原样保留，方便用户输入花括号；嵌套或紧贴外层花括号的写法同样按普通文字处理。
     */
    fun parse(template: String, values: ReaderInfoValues): List<ReaderInfoPart> {
        val parts = mutableListOf<ReaderInfoPart>()
        val text = StringBuilder()
        val battery = values.battery.coerceIn(0, 100)
        var index = 0

        fun flushText() {
            if (text.isNotEmpty()) {
                parts.add(ReaderInfoPart.Text(text.toString()))
                text.clear()
            }
        }

        fun appendBatteryIcon(showLevel: Boolean) {
            flushText()
            parts.add(ReaderInfoPart.BatteryIcon(battery, showLevel))
        }

        while (index < template.length) {
            if (template[index] != '{') {
                text.append(template[index])
                index++
                continue
            }

            var depth = 0
            var close = -1
            var containsNestedBraces = false
            var cursor = index
            while (cursor < template.length) {
                when (template[cursor]) {
                    '{' -> {
                        depth++
                        containsNestedBraces = containsNestedBraces || depth > 1
                    }

                    '}' -> {
                        depth--
                        if (depth == 0) {
                            close = cursor
                            break
                        }
                    }
                }
                cursor++
            }
            if (close < 0) {
                text.append('{')
                index++
                continue
            }

            val token = template.substring(index, close + 1)
            val touchesOuterBrace =
                index > 0 && template[index - 1] == '{' ||
                    close + 1 < template.length && template[close + 1] == '}'
            when {
                containsNestedBraces || touchesOuterBrace -> text.append(token)
                token == BOOK_NAME || token == BOOK_NAME_EN -> text.append(values.bookName)
                token == CHAPTER_TITLE || token == CHAPTER_TITLE_EN -> text.append(values.chapterTitle)
                token == TIME || token == TIME_EN -> text.append(values.time)
                token == BATTERY || token == BATTERY_EN -> text.append("$battery%")
                token == BATTERY_ICON || token == BATTERY_ICON_EN -> appendBatteryIcon(false)
                token == BATTERY_ICON_LEVEL || token == BATTERY_ICON_LEVEL_EN -> appendBatteryIcon(true)
                token == PAGE || token == PAGE_EN -> text.append(values.page)
                token == TOTAL_PAGES || token == TOTAL_PAGES_EN -> text.append(values.totalPages)
                token == READ_PROGRESS || token == READ_PROGRESS_EN -> text.append(values.readProgress)
                token == CHAPTER || token == CHAPTER_EN -> text.append(values.chapter)
                token == TOTAL_CHAPTERS || token == TOTAL_CHAPTERS_EN -> text.append(values.totalChapters)
                else -> text.append(token)
            }
            index = close + 1
        }
        flushText()
        return parts
    }

    /**
     * 旧版枚举规则转等价模板，保证历史配置的显示效果不变。
     *
     * 生成的写法跟随界面语言：中文界面给中文占位符、英文界面给英文占位符，
     * 这样配置界面的摘要不会在英文环境下突然冒出一串中文；两种写法解析结果完全等价。
     */
    fun fromLegacy(tip: Int): String {
        val chinese = isChineseUi()
        fun r(chineseTemplate: String, englishTemplate: String) =
            if (chinese) chineseTemplate else englishTemplate

        return when (tip) {
            ReadTipConfig.chapterTitle -> r(CHAPTER_TITLE, CHAPTER_TITLE_EN)
            ReadTipConfig.time -> r(TIME, TIME_EN)
            ReadTipConfig.battery -> r(BATTERY_ICON, BATTERY_ICON_EN)
            ReadTipConfig.batteryInside -> r(BATTERY_ICON_LEVEL, BATTERY_ICON_LEVEL_EN)
            ReadTipConfig.batteryPercentage -> r(BATTERY, BATTERY_EN)
            ReadTipConfig.page -> r("$PAGE/$TOTAL_PAGES", "$PAGE_EN/$TOTAL_PAGES_EN")
            ReadTipConfig.totalProgress -> r(READ_PROGRESS, READ_PROGRESS_EN)
            ReadTipConfig.pageAndTotal -> r(
                "$PAGE/$TOTAL_PAGES  $READ_PROGRESS",
                "$PAGE_EN/$TOTAL_PAGES_EN  $READ_PROGRESS_EN",
            )
            ReadTipConfig.bookName -> r(BOOK_NAME, BOOK_NAME_EN)
            // 旧实现下"时间+电量"会在图标内显示数字，这里必须用 {电量图标数字} 才能保持视觉一致
            ReadTipConfig.timeBattery ->
                r("$TIME  $BATTERY_ICON_LEVEL", "$TIME_EN  $BATTERY_ICON_LEVEL_EN")
            ReadTipConfig.timeBatteryIconOnly -> r("$TIME $BATTERY_ICON", "$TIME_EN $BATTERY_ICON_EN")
            ReadTipConfig.timeBatteryPercentage -> r("$TIME $BATTERY", "$TIME_EN $BATTERY_EN")
            ReadTipConfig.totalProgress1 -> r("$CHAPTER/$TOTAL_CHAPTERS", "$CHAPTER_EN/$TOTAL_CHAPTERS_EN")
            else -> ""
        }
    }
}
