package com.stardew.assistant.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stardew.assistant.data.CodexItem
import com.stardew.assistant.ui.AssetImage
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelFont
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.today.TodayFacts

/** 分享卡的**设计尺寸** 1080×1620（3:4）。实际渲染密度由调用方给（预览传 Density(scale)）。 */
object CardSpec {
    const val W = 1080
    const val H = 1620
}

/** 卡片字号：1080px 宽的图上，手机端 10~12sp 会糊成一条线，全部放大 */
private object CPx {
    val hero = TextStyle(fontFamily = PixelFont, fontSize = 76.sp, lineHeight = 92.sp)
    val name = TextStyle(fontFamily = PixelFont, fontSize = 62.sp, lineHeight = 78.sp)
    val tag = TextStyle(fontFamily = PixelFont, fontSize = 36.sp, lineHeight = 50.sp)
    val v = TextStyle(fontFamily = PixelFont, fontSize = 34.sp, lineHeight = 48.sp)
    val lab = TextStyle(fontFamily = PixelFont, fontSize = 28.sp, lineHeight = 42.sp)
    val cap = TextStyle(fontFamily = PixelFont, fontSize = 24.sp, lineHeight = 34.sp)
}

/**
 * v2.26 卡片骨架：单色底 + 细描边 + 顶部色带 + 底部水印。
 *
 * 为什么不再用 PixelCard：把它的木纹粗框按比例放到 1080px 宽上是四条黑边，
 * 显得笨重且脏（大图看着像木箱子标签）。分享图要的是「干净、聚焦、一眼能看完」。
 */
@Composable
private fun CardSheet(accent: Color, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalPixel.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.parchment)
            .border(3.dp, c.woodDark),
    ) {
        Box(Modifier.fillMaxWidth().height(14.dp).background(accent))
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 64.dp, vertical = 56.dp),
            content = content,
        )
    }
}

/** 信息行：左标签（固定宽，灰）+ 右值 */
@Composable
private fun InfoRow(label: String, value: String) {
    val c = LocalPixel.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PixelText(label, style = CPx.lab, color = c.inkDim, modifier = Modifier.width(230.dp))
        PixelText(value, style = CPx.v, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ColumnScope.Footer() {
    val c = LocalPixel.current
    // v2.26.2：不再用 weight 把水印沉底——那个 spacer 会和主视觉区的 weight 平分
    // 剩余空间，把键值行和水印之间撑出一大片空。剩余空间全归主视觉区，这里紧跟内容。
    Spacer(Modifier.height(20.dp))
    Box(Modifier.fillMaxWidth().height(2.dp).background(c.parchment2))
    Spacer(Modifier.height(14.dp))
    PixelText(ShareHelper.watermark(), style = CPx.cap, color = c.inkDim)
}

/** 今日概览卡：焦点是日期。内容全部来自 TodayFacts（与今日页同一份计算），不含天气。 */
@Composable
fun TodayCardBody(f: TodayFacts, profile: String) {
    val c = LocalPixel.current
    CardSheet(accent = c.gold) {
        // v2.26.2：日期区独占剩余空间并垂直居中，键值行与水印紧跟其后贴到卡片底部。
        // 旧写法让 Footer 的 spacer 参与平分，行下方就多出一大片空白。
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PixelText("${f.seasonName} ${f.day} 日", style = CPx.hero, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                PixelText(
                    "第 ${f.year} 年 · 存档「$profile」",
                    style = CPx.v, color = c.inkDim,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.parchment2))
        Spacer(Modifier.height(12.dp))

        InfoRow(
            "今日生日",
            if (f.todayBirthdays.isEmpty()) "今天没有人过生日" else f.todayBirthdays.joinToString("、"),
        )
        InfoRow(
            "下个节日",
            if (f.nextFestivalName == null) "60 天内没有节日" else
                "${f.nextFestivalName} · ${if (f.nextFestivalDays == 0) "就是今天" else "${f.nextFestivalDays} 天后"}",
        )
        InfoRow(
            "播种死线",
            if (f.nearestDeadCrop == null) "当前没有到死线的作物" else
                "${f.nearestDeadCrop} · 还剩 ${f.nearestDeadDays.coerceAtLeast(0)} 天",
        )
        InfoRow("今日待办", "${f.todoDone} / ${f.todoTotal}")

        Footer()
    }
}

/**
 * 图鉴条目卡：焦点是大图标（assets 里有 823 张现成像素图）。
 * 数据全部来自 CodexItem 现成字段，零新增数据包。
 */
@Composable
fun ItemCardBody(item: CodexItem, groupTitle: String) {
    val c = LocalPixel.current
    CardSheet(accent = accentOfGroup(groupTitle, c.gold)) {
        // 标题区（图标+名称+分类）作为一个整体吃掉剩余空间并居中：
        // 剩一行放名字会让它掉到图标区外面，条目数据少时下方又是一片空。
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AssetImage(item.iconKey, 200)
                Spacer(Modifier.height(24.dp))
                PixelText(item.name, style = CPx.name, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                PixelText(
                    groupTitle,
                    style = CPx.tag, color = c.gold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.parchment2))
        Spacer(Modifier.height(12.dp))

        // rows 是现成的键值对；放宽到 9 行，条目数据多时能填满而不是提前收尾
        item.rows.take(9).forEach { (k, v) ->
            if (k.isNotBlank()) InfoRow(k, v)
        }
        if (item.text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            PixelText(item.text, style = CPx.cap, color = c.inkDim, maxLines = 3)
        }

        Footer()
    }
}

/** 分类 → 顶部色带颜色（只分四档，避免卡片五颜六色） */
private fun accentOfGroup(title: String, fallback: Color): Color = when {
    title.contains("矿") || title.contains("古") || title.contains("鱼") || title.contains("水晶") ->
        Color(0xFF4A7FB5)
    title.contains("怪") || title.contains("战") || title.contains("Boos") ->
        Color(0xFFB5524A)
    title.contains("菜") || title.contains("制作") || title.contains("装备") || title.contains("工具") || title.contains("渔具") ->
        Color(0xFFC08A4E)
    else -> fallback
}

/**
 * 离屏渲染容器：把 drawContent 的结果录进 [layer]，之后可 captureBitmap() 导出位图。
 *
 * v2.26.2：**不再自己锁 Density(1f)**——那会把外层给的缩放密度覆盖掉，
 * 导致卡片仍按 1080 真实像素绘制、撑破预览容器。改为继承调用方的 Density：
 * 预览时调用方给 Density(scale)，卡片连同字号一起等比缩小；
 * 导出得到的 Bitmap 就是这个尺寸。
 */
@Composable
fun ShareCardSurface(layer: GraphicsLayer, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(CardSpec.W.dp, CardSpec.H.dp)
            .drawWithContent {
                // 用闭包捕获的 layer，不写 graphicsLayer 属性——那个名字会撞上 Modifier.graphicsLayer
                layer.record { this@drawWithContent.drawContent() }
                drawContent()
            },
    ) { content() }
}

/** 新建一个可导出的离屏图层 */
@Composable
fun rememberShareCardLayer(): GraphicsLayer = rememberGraphicsLayer()

/** 把离屏图层导成 Bitmap。GraphicsLayer 的导出 API 是 suspend 且带实验性标注，这里包一层。 */
@OptIn(androidx.compose.ui.graphics.ExperimentalGraphicsApi::class)
suspend fun GraphicsLayer.captureBitmap(): android.graphics.Bitmap =
    toImageBitmap().asAndroidBitmap()