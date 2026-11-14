package com.stardew.assistant.ui.today

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.MyStatusSummary
import com.stardew.assistant.ui.KVRow
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px

/**
 * 「🎒 你的状态」总览卡（v2.100.0 / c184）。
 *
 * ## 落在哪儿
 * 今日页 →「我的农场」分段 → **建议卡之后、「🌾 今日农场」之前**。
 * 顺序理由：行动（今天该做什么）→ 现状总览（我有什么 / 我是谁）→ 明细（田里）。
 *
 * ## 显示哪几行（用户 2026-10-28 拍板，四项全要）
 * ① **背包**：识别出几类几件 + 名字没对上的件数；
 * ② **村民好感**：结交了几人 + 好感最高的是谁；
 * ③ **图鉴进度**：已勾多少条（分母实时算）；
 * ④ **收集包**：已提交多少件（与进度页同源口径）。
 *
 * ## ⚠ 不列什么
 * 现金 / 今日能收 / 田里明细 / 农场地图 —— 那些「🌾 今日农场」卡里已经有了，
 * 这张卡**不重复**（同屏两处同一个数看着像两个数）。
 *
 * ## ⚠ 诚实口径
 * · 好感点数一个都判不出 ⇒ 写「好感点数存档里没记，以游戏内为准」，**不补 0**；
 * · 背包有名字没对上的件数 ⇒ 括号里如实写出来（「没找到」因此不可靠）；
 * · 总结论为 null（没导入存档）⇒ **整张卡不出现**。
 */
@Composable
fun MyStatusCard(summary: MyStatusSummary?) {
    if (summary == null) return
    val c = LocalPixel.current
    PixelCard(title = "🎒 你的状态") {
        // ── ① 背包 ────────────────────────────────────────────────
        val bag = buildString {
            append("${summary.bagKinds} 类 · ${summary.bagItems} 件")
            if (summary.unmappedItems > 0) {
                // ⚠ 如实写出来：有名字没对上的 ⇒ 「背包里有什么」这件事本身不完整
                append("（另有 ${summary.unmappedItems} 件名字没对上）")
            }
        }
        KVRow("背包", bag)

        // ── ② 村民好感 ────────────────────────────────────────────
        val friend = buildString {
            append("${summary.friendCount} 人已结交")
            val nm = summary.topFriendName
            val p = summary.topFriendPoints
            if (nm != null && p != null) {
                append(" · 最高 $nm $p 点")
            } else {
                // 判不出点数就不写「0 点」—— 那是编的
                append(" · 好感点数存档里没记，以游戏内为准")
            }
        }
        KVRow("村民好感", friend)

        // ── ③ 图鉴进度（分母实时算，不是写死的常量）────────────────
        KVRow("图鉴进度", "${summary.codexDone} / ${summary.codexTotal} 条已勾")

        // ── ④ 收集包（与进度页同一份 ProgressStats）───────────────
        KVRow("收集包", "${summary.bundleDone} / ${summary.bundleTotal} 件已提交")

        Spacer(Modifier.height(4.dp))
        PixelText(
            "背包与村民好感来自导入的存档；图鉴与收集包按你的勾选实时算。",
            style = Px.caption, color = c.inkDim,
        )
    }
}
