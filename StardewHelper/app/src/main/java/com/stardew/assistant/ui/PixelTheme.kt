package com.stardew.assistant.ui

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import com.stardew.assistant.R
import com.stardew.assistant.ui.codex.buildLinkedAnnotated
import com.stardew.assistant.ui.codex.segmentGuideText

val PixelFont = FontFamily(Font(R.font.fusion_pixel))

/**
 * v2.93.0：当前 App 内字体（像素 / 系统 / 自定义三档，单源在 [AppFont]）。
 *
 * ## 默认值为什么是 PixelFont 而不是「系统」
 * 这是**安全默认**：将来新页面 / 新组件忘了 provide，天然还是像素字体，
 * 不会因为漏接一次而让某半屏突然变系统字体。反过来（默认系统、显式钉像素）
 * 则是「漏接就变脸」，排查起来极难。
 *
 * ## 谁 provide
 * MainActivity 从 snapshot 读出字体档后 provide；分享卡在
 * `ui/share/ShareEntry.kt` 里**显式钉回 PixelFont**（分享图不能跟着 App 内字体变）。
 *
 * ## 只管 App 内
 * 桌面小组件走 RemoteViews、农场报告长图走 Canvas，两者与 Compose 无关，
 * 天然不受这个开关影响（这是用户 2026-10-25 定的边界）。
 */
val LocalAppFont = staticCompositionLocalOf { PixelFont }

@Immutable
data class PixelColors(
    val screen: Color,
    val wood: Color,
    val woodDark: Color,
    val parchment: Color,
    val parchment2: Color,
    val slot: Color,
    val slotEdge: Color,
    val ink: Color,
    val inkDim: Color,
    val cream: Color,
    val gold: Color,
    val green: Color,
    val red: Color,
    val blue: Color,
)

val DayColors = PixelColors(
    screen = Color(0xFFA97D45), wood = Color(0xFFC08A4E), woodDark = Color(0xFF6B4218),
    parchment = Color(0xFFF8E7C0), parchment2 = Color(0xFFE9D3A0),
    slot = Color(0xFFEECF8F), slotEdge = Color(0xFF8A5A28),
    ink = Color(0xFF4A2C12), inkDim = Color(0xFF7E5B31), cream = Color(0xFFFFE9C4),
    gold = Color(0xFF96580C), green = Color(0xFF376E29), red = Color(0xFFC44538),
    blue = Color(0xFF306490),
)

val NightColors = PixelColors(
    screen = Color(0xFF2B2338), wood = Color(0xFF3D2F4A), woodDark = Color(0xFF171021),
    parchment = Color(0xFF41345A), parchment2 = Color(0xFF382C4E),
    slot = Color(0xFF524267), slotEdge = Color(0xFF2A2040),
    ink = Color(0xFFF0E2C0), inkDim = Color(0xFFBDAA92), cream = Color(0xFFF0E2C0),
    gold = Color(0xFFF2C069), green = Color(0xFF6FBF5E), red = Color(0xFFE06A5A),
    blue = Color(0xFF71ACDD),
)

/**
 * v2.97.0 强调色对比度校准（可读性审计第二轮）。
 *
 * ## 改了什么
 * gold / green / blue 三个强调色在 **16 套色板** 上全部重新取值，
 * 目标是它们压**卡片底 parchment** 的 WCAG 对比度 ≥ 4.5:1（正文阈值）。
 * 改前最差的能到 1.41:1（节日·月光水母起舞的 gold）——「去勾 ›」「✔ 已全部完成」
 * 这类小字在季节/节日配色下基本读不出来。
 *
 * ## 怎么调的
 * 线性 RGB **等比缩放**：色相与饱和度关系不变，只压暗/提亮亮度，
 * 所以像素风观感不会跑偏（不是换个新颜色，是同一个颜色加深）。
 * 同一原值出现在多套色板时取**组内最严**的那条要求，一个值统管一族，
 * 免得节日块写一堆覆盖。
 *
 * ## 为什么夜间（月光温室）只动了 blue
 * 夜间的 parchment 是**深紫**（#41345A），浅字压深底本来就达标
 * （gold 6.72 / green 4.98），再压暗反而看不见；只有 blue 差一点（4.44），
 * 往亮里提一档即可。
 *
 * ⚠ 这次只动**强调色**。正文 ink / inkDim 压木色壳偏低（1.0~2.4），
 * 那是**结构问题**（文字没套卡片直接压木纹底），改色板解决不了，
 * 要在组件层把正文包进 PixelCard。
 */
/** 季节配色（v2.5）：日间基础上加四层覆盖，取意新草粉花/烈日麦浪/红叶南瓜/雪冰蓝。
 *  只覆盖 screen/wood/parchment 系与强调色，slot 系保持日间槽位观感。 */
val SeasonColors = mapOf(
    0 to PixelColors( // 春
        screen = Color(0xFF9FB86A), wood = Color(0xFF7C9A4E), woodDark = Color(0xFF3E5424),
        parchment = Color(0xFFF3F1D8), parchment2 = Color(0xFFE2E0BE),
        slot = Color(0xFFD8E2A8), slotEdge = Color(0xFF5A7228),
        ink = Color(0xFF33421A), inkDim = Color(0xFF5B6C36), cream = Color(0xFFF0F2D0),
        gold = Color(0xFFA74E75), green = Color(0xFF376E29), red = Color(0xFFC44538),
        blue = Color(0xFF306490),
    ),
    1 to PixelColors( // 夏
        screen = Color(0xFFB08A46), wood = Color(0xFF8A6532), woodDark = Color(0xFF5A3A14),
        parchment = Color(0xFFFBEDC6), parchment2 = Color(0xFFEFDCA8),
        slot = Color(0xFFF5D796), slotEdge = Color(0xFF8A5A28),
        ink = Color(0xFF4A2C12), inkDim = Color(0xFF825E34), cream = Color(0xFFFFEFC8),
        gold = Color(0xFF906014), green = Color(0xFF376E29), red = Color(0xFFC44538),
        blue = Color(0xFF306490),
    ),
    2 to PixelColors( // 秋
        screen = Color(0xFF9A6134), wood = Color(0xFF7A4522), woodDark = Color(0xFF4A2410),
        parchment = Color(0xFFF6E2BE), parchment2 = Color(0xFFE8CE9E),
        slot = Color(0xFFEBC888), slotEdge = Color(0xFF7A4522),
        ink = Color(0xFF3E2210), inkDim = Color(0xFF835526), cream = Color(0xFFFFE9C4),
        gold = Color(0xFFB43A27), green = Color(0xFF506825), red = Color(0xFFC44538),
        blue = Color(0xFF306490),
    ),
    3 to PixelColors( // 冬
        screen = Color(0xFF7C8FA6), wood = Color(0xFF5A6B80), woodDark = Color(0xFF2E3A48),
        parchment = Color(0xFFEAF0F4), parchment2 = Color(0xFFD8E0E8),
        slot = Color(0xFFC8D4E0), slotEdge = Color(0xFF4A5A70),
        ink = Color(0xFF26303C), inkDim = Color(0xFF57677C), cream = Color(0xFFE8F0F6),
        gold = Color(0xFF3F6E9E), green = Color(0xFF426773), red = Color(0xFFC44538),
        blue = Color(0xFF306490),
    ),
)

val LocalPixel = staticCompositionLocalOf { DayColors }

/** 节日氛围（v2.10）：大节日当天自动套专属色板。
 *  配色口径与 SeasonColors 一致——只覆盖 screen/wood/parchment 系 + 强调色（gold/red），
 *  slot 系照搬对应季节色保持观感。5 个节日：花舞节/星露谷展览会/万灵节/冰雪节/冬日星盛宴 */
val FestivalColors = mapOf(
    "花舞节" to SeasonColors[0]!!.copy( // 春24：粉调——春色基础上 gold 提亮更粉
        screen = Color(0xFFE8A8C8), wood = Color(0xFFC8789A), woodDark = Color(0xFF7A3854),
        parchment = Color(0xFFFDEEF4), parchment2 = Color(0xFFF4DCEA),
        gold = Color(0xFFAE497B), red = Color(0xFFC44570),
    ),
    "星露谷展览会" to SeasonColors[2]!!.copy( // 秋28：金红暖调——秋色基础上 gold 提亮
        screen = Color(0xFFC86838), wood = Color(0xFF9A4A24), woodDark = Color(0xFF5A2810),
        parchment = Color(0xFFFCE6CC), parchment2 = Color(0xFFF4D2A4),
        gold = Color(0xFF9B5713), red = Color(0xFFB8301A),
    ),
    "万灵节" to SeasonColors[2]!!.copy( // 秋27：暗紫夜调——偏紫的深秋
        screen = Color(0xFF5A3A6A), wood = Color(0xFF3E2A4A), woodDark = Color(0xFF1E1224),
        parchment = Color(0xFFE6DAE8), parchment2 = Color(0xFFC8B8CC),
        ink = Color(0xFF2A1A2E), inkDim = Color(0xFF6A547A), cream = Color(0xFFEAE0EC),
        gold = Color(0xFF735389), red = Color(0xFFD04060),
    ),
    "冰雪节" to SeasonColors[3]!!.copy( // 冬8：冰蓝冷调——冬色基础上更冷更亮
        screen = Color(0xFF9EC4D8), wood = Color(0xFF6A8EA0), woodDark = Color(0xFF3A5260),
        parchment = Color(0xFFF2FAFC), parchment2 = Color(0xFFE0EEF2),
        ink = Color(0xFF1A2A38), inkDim = Color(0xFF526F7F), cream = Color(0xFFF4FAFC),
        gold = Color(0xFF497595), red = Color(0xFFB85060),
    ),
    "冬日星盛宴" to SeasonColors[3]!!.copy( // 冬25：金红暖调——冬色基础上暖化（年关节日）
        screen = Color(0xFF8A4A38), wood = Color(0xFF6A3528), woodDark = Color(0xFF3A1A14),
        parchment = Color(0xFFFCEAD8), parchment2 = Color(0xFFF4D4B8),
        ink = Color(0xFF2E1810), inkDim = Color(0xFF7A4A30), cream = Color(0xFFFAE8D0),
        gold = Color(0xFF9B5713), red = Color(0xFFB8301A),
    ),
    "复活节" to SeasonColors[0]!!.copy( // 春13：蛋彩——春色基础上 gold 偏鹅黄、ink 偏暖
        screen = Color(0xFFB8C878), wood = Color(0xFF8A9A4E), woodDark = Color(0xFF4A5A24),
        parchment = Color(0xFFFCF6D8), parchment2 = Color(0xFFEEE6B8),
        gold = Color(0xFF846C20), red = Color(0xFFD8506A),
    ),
    "夏威夷宴会" to SeasonColors[1]!!.copy( // 夏11：椰树海蓝——夏色基础上 blue 提亮
        screen = Color(0xFF4A8AB0), wood = Color(0xFF2A5A78), woodDark = Color(0xFF143A52),
        parchment = Color(0xFFE8F2F4), parchment2 = Color(0xFFD0E0E4),
        ink = Color(0xFF1A3A4A), inkDim = Color(0xFF476B7B), cream = Color(0xFFEAF4F6),
        gold = Color(0xFF8F6124), red = Color(0xFFD85070),
        blue = Color(0xFF2F7193),
    ),
    "月光水母起舞" to SeasonColors[1]!!.copy( // 夏28：月夜海蓝紫——偏冷的暗海夜
        // ⚠ v2.52.0 修正：原来 ink/inkDim 是**浅色**（#EAF0FA/#A8B8D0），而 parchment 也是浅色
        //    （#D8E0F0），正文压卡片底只有 1.16:1 —— 整个 App 在夏28 基本看不见字。
        //    夜色只体现在外框（screen/wood/woodDark 都是深蓝），卡片底仍是浅色，
        //    所以文字必须是**深色**——这与同为夜调的「万灵节」写法一致（那边 ink 是 #2A1A2E，12.10:1）。
        screen = Color(0xFF2A4A78), wood = Color(0xFF1A3258), woodDark = Color(0xFF0A1A38),
        parchment = Color(0xFFD8E0F0), parchment2 = Color(0xFFB8C8E0),
        ink = Color(0xFF16233C), inkDim = Color(0xFF4A5C80), cream = Color(0xFFEAF0FA),
        gold = Color(0xFF516379), red = Color(0xFFB868A0),
        blue = Color(0xFF3C6399),
    ),
    "鱿鱼节" to SeasonColors[3]!!.copy( // 冬12-13：深海暗蓝——冬色基础上 blue 暗化
        // ⚠ v2.52.0 修正：同「月光水母起舞」，ink/inkDim 原本也是浅色，压浅色 parchment
        //    只有 1.04:1（比前者还差一点）。改为深色后 11.44:1。
        screen = Color(0xFF3A4A68), wood = Color(0xFF2A3A58), woodDark = Color(0xFF14243A),
        parchment = Color(0xFFD8E0E8), parchment2 = Color(0xFFB8C4D0),
        ink = Color(0xFF1C2634), inkDim = Color(0xFF4C5A6C), cream = Color(0xFFDCE4EC),
        gold = Color(0xFF456380), red = Color(0xFF6A3A50),
        blue = Color(0xFF2A4A6A),
    ),
    "夜市" to SeasonColors[3]!!.copy( // 冬15-17：星露谷周年——冬色基础上 gold 偏暖黄
        screen = Color(0xFF5A4A6A), wood = Color(0xFF3A2A48), woodDark = Color(0xFF1A1228),
        parchment = Color(0xFFFCE8C8), parchment2 = Color(0xFFE8D0A0),
        ink = Color(0xFF2A1A2E), inkDim = Color(0xFF7A5A6A), cream = Color(0xFFFAE8C0),
        gold = Color(0xFF866120), red = Color(0xFFC84060),
        blue = Color(0xFF4A6A8A),
    ),
)

/** 手册主题名：日间图鉴 / 月光温室 / 跟随季节 */
object ThemeMode {
    const val SYSTEM = "system"
    const val DAY = "day"
    const val NIGHT = "night"
    const val SEASON = "season"

    /**
     * c201 B4：把「落盘值」与「界面标签」捆在一起（照 [AppFont.OPTIONS] 的现成范式）。
     *
     * 原来设置页写的是两个平行数组（`listOf("跟随系统","日间图鉴",…)` 靠**下标**映射
     * 到 ThemeMode 常量），加一档只改一个数组就会显示别的档位的名字。
     * Pair 让两者不可能分开。
     */
    val OPTIONS: List<Pair<String, String>> = listOf(
        SYSTEM to "跟随系统",
        DAY to "日间图鉴",
        NIGHT to "月光温室",
        SEASON to "季节配色",
    )

    /** 落盘值是否合法（读处/写处守卫都用它，不各处手写字符串） */
    fun isValid(v: String?): Boolean = v != null && OPTIONS.any { it.first == v }
}

@Composable
fun PixelAppTheme(themeMode: String, gameSeason: Int = -1, festival: String? = null, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.NIGHT -> true
        ThemeMode.DAY, ThemeMode.SEASON -> false
        else -> isSystemInDarkTheme()
    }
    val base = if (dark) NightColors else DayColors
    // 季节模式：节日 > 季节 > 日间（节日色只在 SEASON 模式下生效；非节日日/夜模式照常）
    val colors = when {
        themeMode == ThemeMode.SEASON && festival != null -> FestivalColors[festival] ?: (SeasonColors[gameSeason] ?: base)
        themeMode == ThemeMode.SEASON && gameSeason in 0..3 -> SeasonColors[gameSeason] ?: base
        else -> base
    }
    CompositionLocalProvider(LocalPixel provides colors) {
        content()
    }
}

/** 字号分级（v2.12）：10/12/16/24/28 五档层次，10sp 辅助说明/小标、12sp 正文主轴、16sp 标题、24/28sp 大数字展示。
 *  v2.59 起字缩放默认 1×、设置页可调 3 档（见 [FontScale]），系统字号仍不跟随；
 *  tnum 等宽数字让收益列扫读不抖动 */
object Px {
    val caption = TextStyle(fontFamily = PixelFont, fontSize = 10.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")
    val body = TextStyle(fontFamily = PixelFont, fontSize = 12.sp, lineHeight = 21.sp, fontFeatureSettings = "tnum")
    val title = TextStyle(fontFamily = PixelFont, fontSize = 16.sp, lineHeight = 24.sp, fontFeatureSettings = "tnum")
    val big = TextStyle(fontFamily = PixelFont, fontSize = 24.sp, lineHeight = 29.sp, fontFeatureSettings = "tnum")
    val display = TextStyle(fontFamily = PixelFont, fontSize = 28.sp, lineHeight = 34.sp, fontFeatureSettings = "tnum")
}

/**
 * v2.59 字号档位（群友反馈⑦：有人手机开了大字体，App 却锁死 1× 看不清）。
 *
 * ## 为什么是固定 3 档而不是跟随系统
 * Fusion Pixel 是像素风字体，非整数倍缩放会插值发糊；系统字号是任意连续值
 * （大屏可到 2.0），跟随它对像素锐利度和布局都是灾难。所以 App 只开 3 档：
 * 1.0（默认，与 v2.58 之前逐像素一致）/ 1.15 / 1.3——1.3 已把 12sp 正文放大到
 * ~15.6sp，「老花眼可读」目的达到；1.5 会让图鉴网格、计算页双列大面积截字。
 *
 * ## 存下标不存浮点
 * PrefsRepo 存的是档位**下标**（Int），倍率语义由这里的 SCALES 单源定义——
 * 将来调整倍率只改这一处数组，老存档（存的还是下标）不用迁移。
 * MainActivity 把它折算成 fontScale 注入 Density；切档即时全 App 生效。
 *
 * ## 不受影响的地方（别误以为是 bug）
 * 桌面小组件走 RemoteViews（独立于 Compose Density）；分享卡在 ShareEntry
 * 里自带 Density(scale) 覆盖，导出比例恒定。两者的字号都不随本档位变。
 */
object FontScale {
    /** 档位倍率，下标即 PrefsRepo 里落盘的值 */
    val SCALES = listOf(1f, 1.15f, 1.3f)
    val LABELS = listOf("标准", "大", "特大")
}

/**
 * v2.93.0 字体档（像素 / 系统），v2.95.0 加第三档「自定义」。落盘值单源，界面标签也单源。
 *
 * ## 三档各自的作用范围（**只影响 App 内**）
 * · 像素 —— 内置的 TutuPixel 子集（`R.font.fusion_pixel`）
 * · 系统 —— `FontFamily.Default`（跟系统走，含厂商定制字体）
 * · 自定义 —— 用户自己导入的 TTF/OTF（见 [com.stardew.assistant.data.CustomFont]）
 *
 * 桌面小组件、分享卡、农场报告长图**三档都不跟随**：
 * 小组件走 RemoteViews（只认编译期 `@font` 资源）、分享卡在 `ShareEntry.kt`
 * 显式钉回 `PixelFont`、报告长图走 Canvas + `ResourcesCompat.getFont`。
 * 这不是遗漏，是 v2.93.0 就定下的边界（用户 2026-10-25 拍板）。
 *
 * ## 为什么用 Pair 把「值」与「标签」捆在一起
 * 两个平行数组（VALUES / LABELS）迟早会改错位：加一档只改了一个数组，
 * 界面上就会显示别的档位的名字。Pair 让两者不可能分开。
 *
 * ## 落盘值用英文
 * 落盘值（pixel / system / custom）会写进备份文件，将来换界面文案不影响老备份；
 * 老备份里没有这个字段 ⇒ 读侧回落像素字体（见 PrefsRepo 的导入守卫）。
 *
 * ## 「自定义」档的额外约束
 * 落盘值是 `custom` **还不够**——本地必须真的有那个字体文件。
 * 换机导入备份时字体文件不在（字体文件不进 JSON 备份）⇒ 读侧回落像素字体，
 * 绝不让 App 停在一个「选中了一个不存在的字体」的状态上。
 */
object AppFont {
    const val PIXEL = "pixel"
    const val SYSTEM = "system"
    const val CUSTOM = "custom"
    /** 下标即落盘值；Pair 把「值」与「标签」捆在一起，不会改错位 */
    val OPTIONS = listOf(PIXEL to "像素", SYSTEM to "系统", CUSTOM to "自定义")

    /** 全部合法落盘值（读处/写处守卫都用它，不各处手写字符串） */
    val VALUES = listOf(PIXEL, SYSTEM, CUSTOM)

    /** 落盘值是否合法（**不含**「文件是否存在」的检查——那是另一层事） */
    fun isValid(v: String?): Boolean = v != null && v in VALUES
}

/**
 * v2.31：当前作用域是否把正文里的**物品名**切成可点链接（点一下跳图鉴）。
 *
 * ## 为什么是开关而不是给 [PixelText] 加参数
 * [PixelText] 全项目 336 处调用，逐个改签名不可能；给每个文本单独传一个
 * 「要不要链接」又会把这件事散成 336 份口径。CompositionLocal 是唯一能做到
 * 「一处开关、作用域生效」的形态。
 *
 * ## 默认必须是 false
 * 图鉴列表行本身就是可点的（点行开详情抽屉）。那里如果也切链接，
 * 行级手势与文本级手势会打架。设置 / 图鉴 / 今日 / 计算 / 进度 / 村民
 * 一律不开，只有攻略作用域包 `provides true`。
 *
 * 关闭时 [PixelText] 连词典都不碰，只多一次 CompositionLocal 读取 + remember 命中。
 */
val LocalGuideLinkify = staticCompositionLocalOf { false }

@Composable
fun PixelText(
    text: String,
    style: TextStyle = Px.body,
    color: Color = LocalPixel.current.ink,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
    shadow: Boolean = false,
    strike: Boolean = false,
    // v2.25：年历格子里日号要居中。默认 null = 保持 Text 原行为，不影响任何既有调用。
    textAlign: TextAlign? = null,
    // v2.33：图标网格里的条目名单行省略。默认 Clip = 与原行为一致，不影响任何既有调用。
    overflow: TextOverflow = TextOverflow.Clip,
) {
    // v2.93.0：字体切换的**唯一入口**。
    // 全项目所有文字都走 [PixelText]，所以只在这一处覆盖 fontFamily，336 处调用自动生效；
    // Px.* 五档样式保持顶层 val 不动（它们是编译期常量，改成 var 就把「动态字体」
    // 污染成了全局可变状态）。
    //
    // ⚠ remember 的 key **必须**含 appFont：TextStyle 是不可变对象，key 不含它的话，
    //   切字体后 style 引用没变，Text 会判定「没变」而跳过重组 ⇒ 切了没反应。
    val appFont = LocalAppFont.current
    val base = remember(style, appFont) { style.copy(fontFamily = appFont) }
    // 投影样式按 base 记忆化，避免每次重组 copy 出新 TextStyle 击穿 Text 的跳过优化
    val s = if (shadow) {
        val px = with(LocalDensity.current) { 1.dp.toPx() }
        remember(base) { base.copy(shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(px, px))) }
    } else base
    val c = LocalPixel.current
    // v2.31 攻略正文词典链接。两个 CompositionLocal 都读在**无条件路径**上——
    // 条件分支里读会让同一实例的组合结构随内容变化。
    val guideLinkify = LocalGuideLinkify.current
    val segs = if (guideLinkify && text.isNotBlank()) remember(text) { segmentGuideText(text) } else null
    // 一个物品名都没命中时 buildLinkedAnnotated 返回 null，下面直接走原路径
    val linked: AnnotatedString? = if (segs != null) {
        remember(text, segs, c.gold) { buildLinkedAnnotated(segs, c.gold) }
    } else null
    if (linked == null) {
        Text(
            text = text, style = s, color = color, maxLines = maxLines, modifier = modifier,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
            textAlign = textAlign ?: TextAlign.Unspecified,
            overflow = overflow,
        )
    } else {
        // material3 的 Text **只有 String 重载**，没有 AnnotatedString 版本。
        // 带链接的文本必须是 AnnotatedString + LinkAnnotation，所以这里只能换
        // foundation 的 BasicText（同一套 TextStyle，字号/行高与原路径一致）。
        //
        // ⚠ 颜色落在 style 上而不是 span 上：命中段在自己的 SpanStyle 里指定金色，
        //   未命中的段落没有 color，会回落到 style.color —— 正是传入的正文色。
        val linkedStyle = s.copy(
            color = color,
            textAlign = textAlign ?: TextAlign.Unspecified,
        )
        // strike 在这条路径上不生效（链接是物品名，不会被划掉），不额外处理。
        BasicText(
            text = linked,
            modifier = modifier,
            style = linkedStyle,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/** 木板条纹：每 8dp 一道 2dp 暗纹（DrawScope 本身即 Density，无需 composed） */
private val WoodGrainBand = Color.Black.copy(alpha = 0.08f)

fun Modifier.woodGrain(): Modifier = drawBehind {
    val step = 8.dp.toPx()
    val h = 2.dp.toPx()
    var y = step - h
    while (y < size.height) {
        drawRect(WoodGrainBand, Offset(0f, y), Size(size.width, h))
        y += step
    }
}

/** 槽位内斜边：左上高光 + 右下阴影（示意图 .slot/.sel/.box 的立体感） */
private val BevelHi = Color.White.copy(alpha = 0.35f)
private val BevelLo = Color.Black.copy(alpha = 0.16f)

fun Modifier.slotBevel(inset: Dp = 2.dp, width: Dp = 2.dp): Modifier = drawBehind {
    val i = inset.toPx(); val w = width.toPx()
    val W = size.width; val H = size.height
    drawRect(BevelHi, Offset(i, i), Size(W - 2 * i, w))
    drawRect(BevelHi, Offset(i, i), Size(w, H - 2 * i))
    drawRect(BevelLo, Offset(i, H - i - w), Size(W - 2 * i, w))
    drawRect(BevelLo, Offset(W - i - w, i), Size(w, H - 2 * i))
}

/** 可点行按压反馈：按下时轻微变暗 + 下沉 1dp（像素风协调，替代无感点击）。
 *  v2.18.1 性能：改为 @Composable 扩展——旧 composed{} 会给每个元素（村民行/图鉴行/生日行…）
 *  各建一个子组合，滚动中每新进一屏行就多一批子组合分配；状态收集挪进调用方组合，行为不变。 */
@Composable
fun Modifier.pressFeedback(interactionSource: MutableInteractionSource): Modifier {
    val pressed = interactionSource.collectIsPressedAsState()
    val shift = with(LocalDensity.current) { 1.dp.toPx() }
    return this
        .graphicsLayer { translationY = if (pressed.value) shift else 0f }
        .drawWithContent {
            drawContent()
            if (pressed.value) drawRect(Color.Black.copy(alpha = 0.12f))
        }
}
