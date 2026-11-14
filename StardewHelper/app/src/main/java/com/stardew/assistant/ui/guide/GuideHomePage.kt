package com.stardew.assistant.ui.guide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.GameData
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.pressFeedback
import com.stardew.assistant.ui.calendar.parseFestivalDate

/** 一张攻略入口卡：图标 + 标题 + 说明 + 条目数 */
@Composable
private fun GuideEntryCard(
    emoji: String,
    title: String,
    desc: String,
    badge: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val c = LocalPixel.current
    val isrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    PixelCard(accent = accent, modifier = Modifier
        .fillMaxWidth()
        .pressFeedback(isrc)
        .clickable(interactionSource = isrc, indication = null, onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelText(emoji, style = Px.title)
            Spacer(Modifier.width(8.dp))
            PixelText(title, style = Px.title)
            Spacer(Modifier.weight(1f))
            // c223：夜间浅色 accent 底上白字 1.68 → 文字按底亮度切（GlassLadder 单源）；
            //   日间深 accent 底白字保留。金/绿/蓝 accent 全部自动兜住。
            Chip(
                badge,
                border = accent,
                content = if (com.stardew.assistant.ui.glass.GlassLadder.isLightSurface(accent)) c.woodDark
                else androidx.compose.ui.graphics.Color.White,
                fill = accent,
            )
        }
        Spacer(Modifier.height(6.dp))
        PixelText(desc, style = Px.caption, color = c.inkDim)
    }
}

/**
 * 数据缺失时的统一占位（某份 assets 解析失败时显示，不崩）
 *
 * @param onRetry c200 A2：可选的「重新加载」动作。传了就画按钮——
 *   原来失败态只有一句说明，用户**只能杀进程重开**（全项目没有第二处出路）。
 */
@Composable
internal fun GuideLoadFail(what: String, onRetry: (() -> Unit)? = null) {
    val c = LocalPixel.current
    PixelCard(accent = c.red) {
        PixelText("⚠ $what 数据加载失败", color = c.red)
        Spacer(Modifier.height(6.dp))
        PixelText(
            "该模块的 assets 文件可能缺失或格式有误。其余页签不受影响。",
            style = Px.caption, color = c.inkDim,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            PixelButton("🔄 重新加载数据", onClick = onRetry)
        }
    }
}

/**
 * v2.28 攻略首页正文（**不含 PageBar**）。
 *
 * 原来这里叫 `GuideHomePage`，自己画了一个 `PageBar("📖 攻略")`。图鉴 tab 引入
 * 「📕 数据 / 📖 攻略」平级分段后，攻略要能被内嵌进图鉴的分段区里——内嵌时上方
 * 已经有图鉴的 PageBar + 分段，再画一个就是双顶栏。故正文与页头分离，页头交给调用方。
 *
 * `onBack` 一并删掉：全项目从未使用过（返回由 MainActivity 的
 * `BackHandler(enabled = stack.isNotEmpty())` 统一处理），留着只会误导后人。
 *
 * 文件名保持 GuideHomePage.kt 不变（verify 按路径取文件取内容，不认函数名）。
 */
@Composable
fun GuideHomeBody(
    onOpenIsland: () -> Unit,
    onOpenMarriage: () -> Unit,
    onOpenWeapons: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenForaging: () -> Unit,
    onOpenPets: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenCombat: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenShops: () -> Unit,
) {
    val c = LocalPixel.current
    val weaponCount = remember {
        GameData.weaponCompendium?.optJSONArray("weapons")?.length() ?: 0
    }
    val walnutCount = remember {
        GameData.gingerIslandGuide?.optJSONObject("golden_walnuts")
            ?.optInt("total", 0) ?: 0
    }
    val npcCount = remember {
        GameData.marriageGuide?.optJSONArray("marriageable_npcs")?.length() ?: 0
    }
    val stepCount = remember {
        GameData.marriageGuide?.optJSONArray("process")?.length() ?: 0
    }
    // v2.15 四册的条数：任一份缺失显示 0，页面自身会提示「数据加载失败」
    val skillCount = remember {
        GameData.skillLeveling?.optJSONArray("skills")?.length() ?: 0
    }
    val forageCount = remember {
        GameData.foraging?.optJSONArray("items")?.length() ?: 0
    }
    val petCount = remember {
        GameData.petGuide?.optJSONArray("pets")?.length() ?: 0
    }
    val weatherCount = remember {
        GameData.weatherLuck?.optJSONObject("weather")?.optJSONObject("seasons")?.length() ?: 0
    }
    // v2.18 战斗刷级：badge 取冒险家公会目标数（12 条击杀目标）
    val guildCount = remember {
        GameData.monsterFarming?.optJSONArray("guild_goals")?.length() ?: 0
    }
    // v2.25 年历入口徽章：实时数，不写死（数据包一扩充写死就是谎话）
    val calBadge = remember {
        val fest = GameData.festivals.count { parseFestivalDate(it.date) != null }
        val bday = GameData.gifts.count { it.birthdaySeason != null && it.birthdayDay != null }
        "$fest 节日 · $bday 生日"
    }
    // v2.56 商店目录徽章：店数与商品行数都现算
    val shopBadge = remember {
        val cat = GameData.shopCatalog
        if (cat == null) "加载失败"
        else {
            val stores = cat.length()
            var items = 0
            for (k in cat.keys()) items += cat.optJSONArray(k)?.length() ?: 0
            "$stores 店 · $items 件"
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = guideContentPadding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                PixelText(
                    "查资料用的十本册子。数据全部离线，来自星露谷官方维基 1.6。",
                    style = Px.caption, color = c.inkDim,
                )
            }
            item {
                GuideEntryCard(
                    emoji = "🏝", title = "姜岛攻略", accent = c.gold,
                    desc = "解锁流程、修船材料、金核桃来源与消费、鹦鹉服务、火山地牢、隐藏要素",
                    badge = "$walnutCount 核桃",
                    onClick = onOpenIsland,
                )
            }
            item {
                GuideEntryCard(
                    emoji = "💍", title = "结婚攻略", accent = c.red,
                    desc = "可结婚 NPC、追求到婚礼的完整流程、婚后生活、离婚与克罗布斯室友",
                    badge = "$npcCount 人 · $stepCount 步",
                    onClick = onOpenMarriage,
                )
            }
            item {
                GuideEntryCard(
                    emoji = "⚔", title = "武器收集", accent = c.green,
                    desc = "全部武器的伤害区间、暴击、获取方式，以及容易错过的几把",
                    badge = "$weaponCount 件",
                    onClick = onOpenWeapons,
                )
            }
            item {
                GuideEntryCard(
                    emoji = "⭐", title = "技能升级", accent = c.gold,
                    desc = "五个技能的经验来源与速成路线、1→10 升级经验表、1.6 精通系统与石柱奖励",
                    badge = "$skillCount 技能",
                    onClick = onOpenSkills,
                )
            }
            item {
                GuideEntryCard(
                    emoji = "🍄", title = "采集攻略", accent = c.green,
                    desc = "全部采集物的季节、出现地点与概率、售价体力、用途，以及采集技能与职业",
                    badge = "$forageCount 种",
                    onClick = onOpenForaging,
                )
            }
            item {
                GuideEntryCard(
                    emoji = "🐱", title = "宠物攻略", accent = c.red,
                    desc = "猫狗乌龟的品种、好感度怎么刷、心数里程碑、宠物碗与 1.6 领养规则",
                    badge = "$petCount 类",
                    onClick = onOpenPets,
                )
            }
            item {
                GuideEntryCard(
                    emoji = "🌦", title = "天气与幸运", accent = c.blue,
                    desc = "四季天气概率与效果、电视占卜运势等级、每日幸运值机制与提升物品",
                    badge = "$weatherCount 季",
                    onClick = onOpenWeather,
                )
            }
            item {
                GuideEntryCard(
                    emoji = "⚔", title = "刷级路线", accent = c.red,
                    desc = "战斗等级怎么冲最快：各区域怪物经验对比、深层推荐、公会 12 条击杀目标与奖励",
                    badge = "$guildCount 目标",
                    onClick = onOpenCombat,
                )
            }
            item {
                GuideEntryCard(
                    emoji = "📅", title = "年历", accent = c.blue,
                    desc = "全年 112 天：节日、村民生日、每种作物的最晚下种日与解锁清单",
                    badge = calBadge,
                    onClick = onOpenCalendar,
                )
            }
            item {
                GuideEntryCard(
                    emoji = "🛒", title = "商店目录", accent = c.gold,
                    desc = "15 家店各卖什么：金币价、第 2 年调价、换购货币、上架季节与轮换规则",
                    badge = shopBadge,
                    onClick = onOpenShops,
                )
            }
            item {
                PixelText(
                    "「加工品利润排序」在「计算 → 🍶 加工」里，不在本页。",
                    style = Px.caption, color = c.inkDim,
                )
            }
        }
    }
}
