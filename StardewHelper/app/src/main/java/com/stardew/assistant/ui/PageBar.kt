package com.stardew.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 页面顶栏：木纹底 + 标题投影 + 底部深棕压边（示意图 .app-top） */
@Composable
fun PageBar(title: String, right: @Composable () -> Unit = {}) {
    val c = LocalPixel.current
    Column(Modifier.fillMaxWidth().background(c.wood).woodGrain()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelText(title, color = c.cream, shadow = true)
            Spacer(Modifier.weight(1f))
            right()
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(c.woodDark))
    }
}

/**
 * 二级页顶栏：木纹底 + 标题投影 + 底部深棕压边，左侧带「← 返回」。
 *
 * v2.35 起所有压栈页（9 本攻略册 + 年历 + 设置）都走这个，而不是 [PageBar]：
 * 系统返回键能退，但页面上没有任何可点的退路，只能靠用户知道要按返回键。
 * [onBack] 与 `AppRoot` 的 `BackHandler` 退的是同一个栈，所以两者永远一致。
 *
 * [right] 与 [PageBar] 同签名：年历的「第 N 年」徽章这类右侧信息得以保留。
 */
@Composable
fun BackBar(title: String, onBack: () -> Unit, right: @Composable () -> Unit = {}) {
    val c = LocalPixel.current
    Column(Modifier.fillMaxWidth().background(c.wood).woodGrain()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .background(c.slot)
                    .slotBevel(inset = 2.dp, width = 1.dp)
                    .border(2.dp, c.slotEdge)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) { PixelText("← 返回", color = c.ink) }
            Spacer(Modifier.width(10.dp))
            // maxLines = 1：标题挤不下时宁可截断，也不要换行把顶栏撑成两行高。
            // 今天九本册子的标题都短，加这行是为将来新增长标题时不至于静默变形。
            PixelText(title, color = c.cream, shadow = true, maxLines = 1)
            Spacer(Modifier.weight(1f))
            right()
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(c.woodDark))
    }
}
