package com.stardew.assistant.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import com.stardew.assistant.Screen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 双击底栏 tab → 滚回列表顶部。
 * 页面把自己当行的 Screen 和列表 state 传进来；同 tab 双击时这里收到事件后滚顶。
 */
@Composable
fun ScrollToTopEffect(screen: Screen, state: LazyListState) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(screen) {
        com.stardew.assistant.scrollToTop.collect { s ->
            if (s == screen) scope.launch { state.animateScrollToItem(0) }
        }
    }
}

/**
 * v2.33 网格重载：图鉴条目列表改成 LazyVerticalGrid 后用的是 LazyGridState。
 * 与上面的列表版是同一套「双击 tab 滚顶」语义，只是 state 类型不同。
 * 两个 state 在图鉴页里互斥显示（跨分类搜索结果 vs 分类条目），各挂一份互不干扰。
 */
@Composable
fun ScrollToTopEffect(screen: Screen, state: LazyGridState) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(screen) {
        com.stardew.assistant.scrollToTop.collect { s ->
            if (s == screen) scope.launch { state.animateScrollToItem(0) }
        }
    }
}
