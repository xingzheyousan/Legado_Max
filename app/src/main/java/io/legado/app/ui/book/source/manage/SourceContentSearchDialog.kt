package io.legado.app.ui.book.source.manage

import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.source.BaseContentSearchDialog
import io.legado.app.ui.source.ContentSearchType
import io.legado.app.ui.source.SourceFieldItem
import io.legado.app.utils.share
import io.legado.app.utils.startActivity
import kotlinx.coroutines.launch

/**
 * 书源内容查询界面，用于按规则字段或完整 JSON 搜索书源配置。
 * 遵循 MVVM 原则：View 只负责展示，业务逻辑下沉到 ViewModel。
 */
class SourceContentSearchDialog : BaseContentSearchDialog() {

    private val viewModel by viewModels<SourceContentSearchViewModel>()

    override fun getDialogTitle() = "书源内容查询"

    override fun getSearchHint() = "输入关键词搜索选择的书源"

    override fun getContentSearchType() = ContentSearchType.BOOK_SOURCE

    override suspend fun loadSourceItems(allSources: Boolean): List<SourceFieldItem> {
        return viewModel.loadSourceItems(!allSources)
    }

    override suspend fun performSearch(query: String, allItems: List<SourceFieldItem>): List<SourceFieldItem> {
        // 直接使用ViewModel的search方法，它现在会返回结果
        return viewModel.search(query, searchByRuleField, selectedTab)
    }

    override fun navigateToEdit(sourceUrl: String, tabKey: String?, fieldKey: String?) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
            if (!tabKey.isNullOrBlank()) {
                putExtra("tabKey", tabKey)
            }
            if (!fieldKey.isNullOrBlank()) {
                putExtra("fieldKey", fieldKey)
            }
        }
    }

    override fun getTabNames(): Map<String, String> = viewModel.getTabNames()

    override fun exportSources(sourceUrls: List<String>) {
        lifecycleScope.launch {
            try {
                val file = viewModel.exportSources(sourceUrls)
                activity?.share(file)
            } catch (e: Exception) {
                // 处理导出错误
                e.printStackTrace()
            }
        }
    }
}
