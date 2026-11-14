package com.stardew.assistant.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.GameData
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.KVRow
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.BackBar
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import org.json.JSONObject

private val SEASON_EMOJI = mapOf(
    "春" to "🌱", "夏" to "☀️", "秋" to "🍂", "冬" to "❄️",
)

/** v2.15 天气与幸运：四季天气概率、天气效果、每日幸运值机制 */
@Composable
fun GuideWeatherPage(onBack: () -> Unit) {
    val c = LocalPixel.current
    val root = GameData.weatherLuck
    if (root == null) {
        Column(Modifier.fillMaxSize()) {
            BackBar("🌦 天气与幸运", onBack)
            PixelCard(Modifier.padding(12.dp)) { GuideLoadFail("天气") }
        }
        return
    }

    val w = remember(root) { root.optJSONObject("weather") }
    val lk = remember(root) { root.optJSONObject("luck") }
    val seasons = remember(w) {
        w?.optJSONObject("seasons")?.let { o ->
            o.keys().asSequence().sortedBy { listOf("春", "夏", "秋", "冬").indexOf(it) }
                .map { it to o.getJSONObject(it) }.toList()
        }.orEmpty()
    }
    var idx by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        BackBar("🌦 天气与幸运", onBack)
        PixelSegmented(
            options = listOf("四季天气", "天气效果", "幸运值"),
            selected = idx,
            onSelect = { idx = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = guideContentPadding(4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (idx) {
                0 -> items(seasons.size) { i ->
                    val (name, so) = seasons[i]
                    PixelCard(title = "${SEASON_EMOJI[name] ?: ""} $name", accent = c.blue) {
                        val pw = so.optJSONArray("possible_weather")
                        PixelText(
                            "可能天气 ${pw?.length() ?: 0} 种",
                        )
                        Spacer(Modifier.height(4.dp))
                        val pr = so.optJSONObject("probabilities")
                        if (pr != null && pr.length() > 0) {
                            for (k in pr.keys().asSequence().sorted()) {
                                // v2.17 D9：缺键时 optDouble 返回 NaN → 上屏「NaN%」，直接跳过该行
                                val raw = pr.optDouble(k, Double.NaN)
                                if (raw.isNaN()) continue
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PixelText(k, modifier = Modifier.width(56.dp))
                                    val pct = raw * 100
                                    // 简易占比条：满格 20dp = 100%
                                    val barW = (pct / 100.0 * 120).toInt().coerceIn(2, 120)
                                    PixelText("█".repeat(barW / 6), color = c.gold)
                                    Spacer(Modifier.width(6.dp))
                                    PixelText("%.0f%%".format(pct), style = Px.caption)
                                }
                            }
                        }
                        so.optString("notes").takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(6.dp))
                            PixelText(it, style = Px.caption, color = c.inkDim)
                        }
                    }
                }

                1 -> {
                    val eff = w?.optJSONObject("weather_effects")
                    val ks = eff?.keys()?.asSequence()?.toList()?.sorted().orEmpty()
                    items(ks.size) { i ->
                        val k = ks[i]
                        PixelCard(title = "${k}的影响") {
                            // v2.17 A1：值是对象，直接 optString 会吐原始 JSON；走共享助手渲染
                            PixelText(effectText(eff!!, k))
                        }
                    }
                    w?.optString("probabilities_note")?.takeIf { it.isNotBlank() }?.let {
                        item {
                            PixelCard(title = "📌 概率说明", accent = c.gold) {
                                PixelText(it, style = Px.caption)
                            }
                        }
                    }
                    w?.optString("tv_forecast")?.takeIf { it.isNotBlank() }?.let {
                        item {
                            PixelCard(title = "📺 电视天气预报", accent = c.blue) {
                                PixelText(it)
                            }
                        }
                    }
                }

                else -> {
                    item {
                        PixelCard(title = "🍀 每日幸运值", accent = c.gold) {
                            lk?.optString("daily_luck_mechanism")?.takeIf { it.isNotBlank() }
                                ?.let { PixelText(it) } ?: PixelText("—")
                        }
                    }
                    val tv = lk?.optJSONObject("tv_fortune")
                    item {
                        PixelCard(title = "🔮 占卜频道") {
                            tv?.optString("频道")?.takeIf { it.isNotBlank() }
                                ?.let { KVRow("频道", it) }
                        }
                    }
                    val lv = tv?.optJSONArray("levels")
                    item {
                        PixelCard(title = "📺 今日运势等级") {
                            if (lv == null || lv.length() == 0) {
                                PixelText("—")
                            } else {
                                for (i in 0 until lv.length()) {
                                    if (i > 0) RowSep()
                                    val v = lv.getJSONObject(i)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        PixelText(
                                            v.optString("level_text"),
                                            modifier = Modifier.weight(1f),
                                        )
                                        Chip(v.optString("luck_range"), border = c.gold)
                                    }
                                    v.optString("effect").takeIf { it.isNotBlank() }
                                        ?.let { PixelText(it, style = Px.caption) }
                                }
                            }
                        }
                    }
                    val le = lk?.optJSONObject("luck_effects")
                    item {
                        PixelCard(title = "⚡ 幸运影响什么") {
                            if (le == null || le.length() == 0) {
                                PixelText("—")
                            } else {
                                var first = true
                                for (k in le.keys().asSequence()) {
                                    if (!first) RowSep()
                                    first = false
                                    PixelText("· $k")
                                    // v2.17：同样走 effectText，防值将来对象化后复发原始 JSON 上屏
                                    PixelText(effectText(le, k), style = Px.caption)
                                }
                            }
                        }
                    }
                    val bi = lk?.optJSONArray("luck_boost_items")
                    item {
                        PixelCard(title = "🎁 提升幸运的物品") {
                            if (bi == null || bi.length() == 0) {
                                PixelText("—")
                            } else {
                                for (i in 0 until bi.length()) {
                                    val o = bi.optJSONObject(i) ?: continue
                                    if (i > 0) RowSep()
                                    PixelText("· ${o.optString("name")}")
                                    o.optString("effect").takeIf { it.isNotBlank() }
                                        ?.let { PixelText(it, style = Px.caption) }
                                    o.optString("source").takeIf { it.isNotBlank() }
                                        ?.let { PixelText(it, style = Px.caption, color = c.inkDim) }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}