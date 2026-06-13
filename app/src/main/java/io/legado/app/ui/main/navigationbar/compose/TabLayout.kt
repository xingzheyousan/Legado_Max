package io.legado.app.ui.main.navigationbar.compose

import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Tab 栏组件，用于切换日间方案和夜间方案
 *
 * @param selectedTab 当前选中的 Tab 索引，0 表示日间方案，1 表示夜间方案
 * @param onTabChange Tab 切换回调
 */
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