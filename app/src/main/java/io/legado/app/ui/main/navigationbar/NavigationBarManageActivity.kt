package io.legado.app.ui.main.navigationbar

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.NavigationBarConfig
import io.legado.app.data.entities.NavigationBarEntry
import io.legado.app.data.entities.Source
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.NavigationBarManager
import io.legado.app.ui.main.navigationbar.compose.EditPanel
import io.legado.app.ui.main.navigationbar.compose.SchemeCard
import io.legado.app.ui.main.navigationbar.compose.TabLayout
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.fullScreen
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.toastOnUi
import java.util.UUID

/**
 * 底栏管理界面
 *
 * 提供底栏液态玻璃效果方案的管理功能：
 * - 查看日间/夜间方案列表
 * - 应用、编辑、删除方案
 * - 创建新方案
 */
class NavigationBarManageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        initTheme()
        super.onCreate(savedInstanceState)
        setupSystemBar()
        enableEdgeToEdge()

        setContent {
            LegadoTheme {
                NavigationBarManageScreen(
                    onBackClick = { finish() }
                )
            }
        }
    }

    private fun initTheme() {
        val theme = ThemeConfig.getTheme()
        when (theme) {
            io.legado.app.constant.Theme.Dark -> {
                setTheme(io.legado.app.R.style.AppTheme_Dark)
            }
            io.legado.app.constant.Theme.Light -> {
                setTheme(io.legado.app.R.style.AppTheme_Light)
            }
            else -> {
                if (ColorUtils.isColorLight(primaryColor)) {
                    setTheme(io.legado.app.R.style.AppTheme_Light)
                } else {
                    setTheme(io.legado.app.R.style.AppTheme_Dark)
                }
            }
        }
    }

    private fun setupSystemBar() {
        fullScreen()
        val isTransparentStatusBar = AppConfig.isTransparentStatusBar
        val statusBarColor = ThemeStore.statusBarColor(this, isTransparentStatusBar)
        setStatusBarColorAuto(statusBarColor, isTransparentStatusBar, true)
        if (AppConfig.immNavigationBar) {
            setNavigationBarColorAuto(ThemeStore.navigationBarColor(this))
        } else {
            val nbColor = ColorUtils.darkenColor(ThemeStore.navigationBarColor(this))
            setNavigationBarColorAuto(nbColor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationBarManageScreen(
    onBackClick: () -> Unit
) {
    // 状态管理
    var selectedTab by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf(loadPackages(selectedTab == 1)) }
    var activeDirName by remember { mutableStateOf(getActiveDirName(selectedTab == 1)) }
    var showEditPanel by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<NavigationBarEntry?>(null) }

    // 刷新列表
    fun refreshEntries() {
        val isNight = selectedTab == 1
        entries = loadPackages(isNight)
        activeDirName = getActiveDirName(isNight)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "底栏管理",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // 创建新方案
                        val isNight = selectedTab == 1
                        val newConfig = NavigationBarConfig(
                            name = "新方案",
                            isNightMode = isNight
                        )
                        val newEntry = NavigationBarEntry(
                            config = newConfig,
                            source = Source.LOCAL,
                            dirName = UUID.randomUUID().toString()
                        )
                        editingEntry = newEntry
                        showEditPanel = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "添加"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            // 主内容区域
            if (showEditPanel && editingEntry != null) {
                // 编辑面板
                EditPanel(
                    config = editingEntry!!.config,
                    isNightMode = editingEntry!!.config.isNightMode,
                    onConfigChange = { newConfig ->
                        editingEntry = editingEntry!!.copy(config = newConfig)
                    },
                    onSave = {
                        savePackage(editingEntry!!)
                        showEditPanel = false
                        editingEntry = null
                        refreshEntries()
                    },
                    onCancel = {
                        showEditPanel = false
                        editingEntry = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 方案列表
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Tab 栏
                    TabLayout(
                        selectedTab = selectedTab,
                        onTabChange = { newTab ->
                            selectedTab = newTab
                            refreshEntries()
                        }
                    )

                    // 方案列表
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        content = {
                            items(entries) { entry ->
                                SchemeCard(
                                    entry = entry,
                                    isActive = entry.dirName == activeDirName,
                                    onClick = {
                                        applyPackage(entry)
                                        activeDirName = entry.dirName
                                    },
                                    onEdit = {
                                        showEditDialog(entry)
                                        editingEntry = entry
                                        showEditPanel = true
                                    },
                                    onDelete = {
                                        deletePackage(entry)
                                        refreshEntries()
                                    },
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 8.dp
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * 加载方案列表
 *
 * @param isNight 是否为夜间模式
 * @return 方案列表
 */
private fun loadPackages(isNight: Boolean): List<NavigationBarEntry> {
    return NavigationBarManager.loadEntries(isNight)
}

/**
 * 获取当前激活的方案名
 *
 * @param isNight 是否为夜间模式
 * @return 方案目录名
 */
private fun getActiveDirName(isNight: Boolean): String {
    return AppConfig.activeDirName(isNight)
}

/**
 * 应用方案
 *
 * @param entry 要应用的方案
 */
private fun applyPackage(entry: NavigationBarEntry) {
    NavigationBarManager.apply(entry)
}

/**
 * 显示编辑对话框
 *
 * @param entry 要编辑的方案
 */
private fun showEditDialog(entry: NavigationBarEntry) {
    // 编辑对话框由 EditPanel 组件处理
}

/**
 * 删除方案
 *
 * @param entry 要删除的方案
 */
private fun deletePackage(entry: NavigationBarEntry) {
    if (entry.source == Source.BUILTIN) {
        // 内置方案不可删除，已在 SchemeCard 中处理
        return
    }
    NavigationBarManager.deleteEntry(entry.dirName)
}

/**
 * 保存方案
 *
 * @param entry 要保存的方案
 */
private fun savePackage(entry: NavigationBarEntry) {
    NavigationBarManager.saveEntry(entry)
}