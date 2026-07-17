package io.legado.app.ui.book.source.manage

import android.app.Application
import android.os.Parcelable
import com.google.gson.JsonObject
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.ui.source.ContentSearchEngine
import io.legado.app.ui.source.JsonSearchItem
import io.legado.app.ui.source.SourceFieldItem
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.io.File

/**
 * 书源内容搜索 ViewModel
 * 负责管理书源数据的加载、解析、搜索等所有业务逻辑
 * 遵循 MVVM 原则，数据组装逻辑从 Fragment 下沉到 ViewModel
 */
class SourceContentSearchViewModel(application: Application) : BaseViewModel(application) {

    companion object {
        /**
         * 标签页名称映射：标签key -> 显示名称
         * 用于界面显示各个功能模块的分类
         */
        private val TAB_NAMES = mapOf(
            "base" to "基本",
            "search" to "搜索",
            "explore" to "发现",
            "info" to "详情",
            "toc" to "目录",
            "content" to "正文"
        )

        /**
         * 各标签页的字段定义：标签key -> 字段列表(字段key -> 字段显示名称)
         * 完整定义了书源JSON结构中所有可搜索的字段
         */
        private val TAB_FIELDS = mapOf(
            "base" to listOf(
                "bookSourceUrl" to "源地址",
                "bookSourceName" to "源名称",
                "bookSourceGroup" to "源分组",
                "bookSourceComment" to "源注释",
                "loginUrl" to "登录地址",
                "loginUi" to "登录界面",
                "loginCheckJs" to "登录检查JS",
                "coverDecodeJs" to "封面解密JS",
                "bookUrlPattern" to "书籍URL正则",
                "header" to "请求头",
                "variableComment" to "变量说明",
                "concurrentRate" to "并发率",
                "jsLib" to "jsLib"
            ),
            "search" to listOf(
                "searchUrl" to "搜索地址",
                "checkKeyWord" to "校验关键字",
                "bookList" to "书籍列表",
                "name" to "书名",
                "author" to "作者",
                "kind" to "分类",
                "wordCount" to "字数",
                "lastChapter" to "最新章节",
                "intro" to "简介规则",
                "coverUrl" to "封面规则",
                "bookUrl" to "书籍URL"
            ),
            "explore" to listOf(
                "exploreUrl" to "发现地址",
                "bookList" to "书籍列表",
                "name" to "书名",
                "author" to "作者",
                "kind" to "分类",
                "wordCount" to "字数",
                "lastChapter" to "最新章节",
                "intro" to "简介规则",
                "coverUrl" to "封面规则",
                "bookUrl" to "书籍URL"
            ),
            "info" to listOf(
                "init" to "初始化",
                "name" to "书名",
                "author" to "作者",
                "kind" to "分类",
                "wordCount" to "字数",
                "lastChapter" to "最新章节",
                "intro" to "简介规则",
                "coverUrl" to "封面规则",
                "tocUrl" to "目录URL",
                "canReName" to "允许修改书名作者",
                "downloadUrls" to "下载地址"
            ),
            "toc" to listOf(
                "preUpdateJs" to "更新之前JS",
                "chapterList" to "目录列表规则",
                "chapterName" to "章节名称",
                "chapterUrl" to "章节URL",
                "formatJs" to "格式化规则",
                "isVolume" to "Volume标识",
                "updateTime" to "更新时间",
                "isVip" to "是否VIP",
                "isPay" to "购买标识",
                "nextTocUrl" to "目录下一页规则"
            ),
            "content" to listOf(
                "content" to "正文内容",
                "nextContentUrl" to "正文下一页URL规则",
                "subContent" to "字内容规则",
                "replaceRegex" to "替换正则",
                "ChapterName" to "章节名称规则",
                "sourceRegex" to "资源正则",
                "imageStyle" to "图片样式",
                "imageDecode" to "图片解密",
                "webJs" to "WebView JS",
                "payAction" to "购买操作",
                "callBackJs" to "回调操作"
            )
        )
    }

    /**
     * UI状态流，用于向界面提供响应式数据更新
     */
    private val _uiState = MutableStateFlow(SourceSearchUiState())
    val uiState: StateFlow<SourceSearchUiState> = _uiState.asStateFlow()

    /**
     * 缓存所有书源原始数据：(源名称, 源URL, JSON对象)
     * 用于JSON全文搜索和字段提取
     */
    private var allSources: List<Triple<String, String, JsonObject>> = emptyList()

    /**
     * 所有可搜索的字段条目列表
     * 由原始书源数据解析而来，包含每个字段的详细信息
     */
    private var allSourceItems: List<SourceFieldItem> = emptyList()

    /**
     * JSON搜索项目列表
     * 用于全文JSON搜索功能
     */
    private var jsonSearchItems: List<JsonSearchItem> = emptyList()

    /**
     * 加载书源数据并转换为可搜索的字段项目
     * @param enabledOnly 是否只加载启用的书源
     * @return 构建好的搜索字段列表
     */
    suspend fun loadSourceItems(enabledOnly: Boolean): List<SourceFieldItem> {
        return withContext(Dispatchers.IO) {
            // 从数据库获取书源数据
            val sources = if (enabledOnly) {
                appDb.bookSourceDao.getAllSources().filter { it.enabled }
            } else {
                appDb.bookSourceDao.getAllSources()
            }

            // 转换为(名称, URL, JSON对象)的三元组列表
            allSources = sources.map { source ->
                Triple(
                    source.bookSourceName,
                    source.bookSourceUrl,
                    GSON.toJsonTree(source).asJsonObject
                )
            }

            // 构建可搜索的字段条目
            allSourceItems = buildSourceFieldItems(allSources)

            // 准备JSON全文搜索数据
            jsonSearchItems = allSources.map { (sourceName, sourceUrl, jsonObj) ->
                JsonSearchItem(sourceName, sourceUrl, jsonObj.toString())
            }

            // 更新各标签页的统计数量
            updateTabCounts()

            // 返回构建结果
            allSourceItems
        }
    }

    /**
     * 导出选中的书源到JSON文件
     * @param sourceUrls 要导出的书源URL列表
     * @return 导出的文件对象
     */
    suspend fun exportSources(sourceUrls: List<String>): File {
        return withContext(Dispatchers.IO) {
            // 筛选出要导出的书源
            val sources = appDb.bookSourceDao.getAllSources().filter {
                it.bookSourceUrl in sourceUrls
            }

            // 创建导出文件
            val path = "${context.filesDir}/shareBookSource.json"
            FileUtils.delete(path)  // 删除已存在文件
            val file = FileUtils.createFileWithReplace(path)

            // 将书源数据写入JSON文件
            file.outputStream().buffered().use { out ->
                GSON.writeToOutputStream(out, sources)
            }

            file  // 返回创建的文件
        }
    }

    /**
     * 执行搜索操作
     * @param query 搜索关键词
     * @param searchByRuleField 是否按规则字段搜索(true=字段搜索, false=JSON全文搜索)
     * @param selectedTab 当前选中的标签页，"__ALL__"表示全部
     * @return 搜索结果列表
     */
    suspend fun search(query: String, searchByRuleField: Boolean, selectedTab: String): List<SourceFieldItem> {
        return withContext(Dispatchers.IO) {
            // 根据选中的标签页过滤要搜索的内容
            val itemsToSearch = if (selectedTab == "__ALL__") {
                allSourceItems
            } else {
                allSourceItems.filter { it.tabKey == selectedTab }
            }

            // 选择搜索模式：规则字段搜索或JSON全文搜索
            val results = if (searchByRuleField) {
                ContentSearchEngine.searchFields(query, itemsToSearch)
            } else {
                ContentSearchEngine.searchJson(
                    query = query,
                    sourceItems = itemsToSearch,
                    jsonItems = jsonSearchItems
                )
            }

            // 更新UI状态
            _uiState.value = _uiState.value.copy(
                searchResults = results,
                isLoading = false
            )

            results
        }
    }

    /**
     * 导出的选中的书源到JSON文件
     * @param sourceUrls 要导出的书源URL列表
     * @param success 成功回调，返回导出的文件对象
     */
    fun exportSources(sourceUrls: List<String>, success: (File) -> Unit) {
        execute {
            // 筛选出要导出的书源
            val sources = appDb.bookSourceDao.getAllSources().filter {
                it.bookSourceUrl in sourceUrls
            }

            // 创建导出文件
            val path = "${context.filesDir}/shareBookSource.json"
            FileUtils.delete(path)  // 删除已存在文件
            val file = FileUtils.createFileWithReplace(path)

            // 将书源数据写入JSON文件
            file.outputStream().buffered().use { out ->
                GSON.writeToOutputStream(out, sources)
            }

            file  // 返回创建的文件
        }.onSuccess {
            if (it != null) success(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)  // 显示错误信息
        }
    }

    /**
     * 获取标签页名称映射
     * @return 标签key到显示名称的映射表
     */
    fun getTabNames(): Map<String, String> = TAB_NAMES

    /**
     * 构建可搜索的字段项目列表
     * 将原始书源数据解析为StructuredFieldItem对象列表
     * @param sources 书源数据列表 (名称, URL, JSON对象)
     * @return 可搜索的字段条目列表
     */
    private fun buildSourceFieldItems(sources: List<Triple<String, String, JsonObject>>): List<SourceFieldItem> {
        val items = mutableListOf<SourceFieldItem>()

        // 遍历每个书源
        for ((sourceName, sourceUrl, jsonObj) in sources) {
            // 获取书源分组信息
            val sourceGroup = getFieldValue(jsonObj, "base", "bookSourceGroup")

            // 遍历所有标签页的字段定义
            for ((tabKey, fields) in TAB_FIELDS) {
                for ((fieldKey, fieldName) in fields) {
                    // 从JSON中提取字段值
                    val value = getFieldValue(jsonObj, tabKey, fieldKey) ?: continue

                    // 构建字段项目对象
                    items.add(SourceFieldItem(
                        sourceName = sourceName,
                        sourceUrl = sourceUrl,
                        tabKey = tabKey,
                        tabName = TAB_NAMES[tabKey] ?: tabKey,
                        fieldKey = fieldKey,
                        fieldName = fieldName,
                        value = value,
                        sourceGroup = sourceGroup
                    ))
                }
            }
        }
        return items
    }

    /**
     * 更新各标签页的统计数量
     * 计算每个标签页包含的字段数量，用于界面显示
     */
    private fun updateTabCounts() {
        val counts = TAB_NAMES.keys.associateWith { tabKey ->
            allSourceItems.count { it.tabKey == tabKey }
        }
        _uiState.value = _uiState.value.copy(tabCounts = counts)
    }

    /**
     * 从书源JSON对象中提取指定字段的值
     * 处理不同标签页的字段位置差异和数据类型转换
     * @param jsonObj 书源JSON对象
     * @param tabKey 标签页key (base/search/explore/info/toc/content)
     * @param fieldKey 字段key
     * @return 提取的字段值字符串，如果字段不存在或为空则返回null
     */
    private fun getFieldValue(jsonObj: JsonObject, tabKey: String, fieldKey: String): String? {
        return when (tabKey) {
            // 基础信息字段直接位于JSON根节点
            "base" -> {
                if (!jsonObj.has(fieldKey)) return null
                val element = jsonObj.get(fieldKey)
                when {
                    element.isJsonNull -> null
                    element.isJsonPrimitive -> element.asString
                    else -> element.toString()
                }
            }
            "search" -> {
                if (fieldKey == "searchUrl") {
                    if (!jsonObj.has("searchUrl")) return null
                    val element = jsonObj.get("searchUrl")
                    when {
                        element.isJsonNull -> null
                        element.isJsonPrimitive -> element.asString
                        else -> element.toString()
                    }
                } else {
                    val rule = jsonObj.getAsJsonObject("ruleSearch")
                    if (rule == null || !rule.has(fieldKey)) return null
                    val element = rule.get(fieldKey)
                    when {
                        element.isJsonNull -> null
                        element.isJsonPrimitive -> element.asString
                        else -> element.toString()
                    }
                }
            }
            "explore" -> {
                if (fieldKey == "exploreUrl") {
                    if (!jsonObj.has("exploreUrl")) return null
                    val element = jsonObj.get("exploreUrl")
                    when {
                        element.isJsonNull -> null
                        element.isJsonPrimitive -> element.asString
                        else -> element.toString()
                    }
                } else {
                    val rule = jsonObj.getAsJsonObject("ruleExplore")
                    if (rule == null || !rule.has(fieldKey)) return null
                    val element = rule.get(fieldKey)
                    when {
                        element.isJsonNull -> null
                        element.isJsonPrimitive -> element.asString
                        else -> element.toString()
                    }
                }
            }
            "info" -> {
                val rule = jsonObj.getAsJsonObject("ruleBookInfo")
                if (rule == null || !rule.has(fieldKey)) return null
                val element = rule.get(fieldKey)
                when {
                    element.isJsonNull -> null
                    element.isJsonPrimitive -> element.asString
                    else -> element.toString()
                }
            }
            "toc" -> {
                val rule = jsonObj.getAsJsonObject("ruleToc")
                if (rule == null || !rule.has(fieldKey)) return null
                val element = rule.get(fieldKey)
                when {
                    element.isJsonNull -> null
                    element.isJsonPrimitive -> element.asString
                    else -> element.toString()
                }
            }
            "content" -> {
                val rule = jsonObj.getAsJsonObject("ruleContent")
                if (rule == null || !rule.has(fieldKey)) return null
                val element = rule.get(fieldKey)
                when {
                    element.isJsonNull -> null
                    element.isJsonPrimitive -> element.asString
                    else -> element.toString()
                }
            }
            else -> null
        }
    }
}

@Parcelize
data class SourceSearchUiState(
    val searchResults: List<SourceFieldItem> = emptyList(), // 搜索结果
    val tabCounts: Map<String, Int> = emptyMap(),// 标签页计数
    val isLoading: Boolean = false // 加载状态
) : Parcelable
