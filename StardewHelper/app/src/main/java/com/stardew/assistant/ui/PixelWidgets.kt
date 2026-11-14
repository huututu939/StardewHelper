package com.stardew.assistant.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.GameData
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * 像素图标统一缓存：ConcurrentHashMap 线程安全、全量驻留（564 张 ≈10MB），
 * 支持启动后 IO 线程预热；未命中现解兜底。避免首次滚动列表时主线程集中解码掉帧。
 */
object IconCache {
    private val tab = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    private val ui = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    private val item = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    // c203：非图标静态图（打赏二维码）也进缓存——原来 raw 每次都重新解码。
    private val rawCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

    private     /**
     * 解码 assets 图标。v2.33 决策 3：按目标像素 downsample。
     *
     * 起因：图标网格让「显示 68px 却缓存 1653px 原图」的浪费从不显眼变显眼——
     * `BitmapFactory.decodeStream` 默认**全尺寸解码**，实测源图里有 1653×1074（单张 7.1MB）、
     * 1303×950（4.9MB）、847×737 这些大图，缓存全 823 个约 52MB。
     *
     * 做法：先 `inJustDecodeBounds` 只读尺寸（**不分配像素**），按 [maxPx] 算 2 的幂
     * `inSampleSize` 再解一次。只采样真正的大图——694 个 48×48 的主体图标 sample=1
     * 原样解，观感一个像素都不变。
     *
     * ⚠ 与 `FilterQuality.None`（**渲染时**不过滤）是两回事，这里是**解码时**降采样，
     *   两者不冲突；2 的幂下采样不引入额外插值模糊。
     * ⚠ [maxPx] 必须 ≥ 全项目最大图标显示尺寸，否则会被**放大**（FilterQuality.None 硬放大必糊）。
     *   实测最大显示入参是 200dp（= 525px @2.625dpi）——`ShareCards.kt:155` 分享卡的大图，
     *   那是**发出去给人看的成品**，糊了不可接受。故取 1080（525 的 2 倍余量）。
     * ⚠ 取 640 只能省 39%，但会把 5 张大图采到 413px（< 525px 显示）放大 1.27 倍——
     *   夏威夷宴会/复活节/农舍升级①②③ 在分享卡里会糊。省 9MB 不值得换分享卡糊掉。
     * ⚠ 不要改成「按显示尺寸分档缓存」：`IconCache.item` 的 key 只有名字，
     *   同一图标先以 26dp 解码（列表）再拿去发 200dp 分享卡会用错小图。统一 maxPx 才没有这个坑。
     */
    fun decode(
        ctx: android.content.Context,
        path: String,
        maxPx: Int = 1080,
    ): android.graphics.Bitmap? = runCatching {
        // 第一遍只读边界，不分配像素
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
        ctx.assets.open(path).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    fun tabIcon(ctx: android.content.Context, name: String): android.graphics.Bitmap? {
        tab[name]?.let { return it }
        return decode(ctx, "img/tab/$name.png")?.also { tab[name] = it }
    }

    /**
     * 任意 assets 路径的通用解码入口（v2.63 打赏二维码这类**非图标**静态图）。
     * decode 保持 private：外面只该按路径取图，不该碰降采样参数——
     * 统一走这里才不会有人绕开 maxPx=1080 的口径自己 decodeStream。
     *
     * ⚠ c203：原来这里**每次都重新解码**（没有缓存），而调用点在组合期同步调用
     *   ⇒ 打赏二维码（75KB PNG）在**主线程**解码。改成带缓存 + 异步入口。
     */
    fun raw(ctx: android.content.Context, path: String): android.graphics.Bitmap? {
        rawCache[path]?.let { return it }
        return decode(ctx, path)?.also { rawCache[path] = it }
    }

    /** 只查缓存不解码：打赏二维码这类非图标静态图的同步快路径 */
    fun peekRaw(path: String): android.graphics.Bitmap? = rawCache[path]

    fun uiIcon(ctx: android.content.Context, name: String): android.graphics.Bitmap? {
        ui[name]?.let { return it }
        return decode(ctx, "img/ui/$name.png")?.also { ui[name] = it }
    }

    fun itemIcon(ctx: android.content.Context, name: String): android.graphics.Bitmap? {
        item[name]?.let { return it }
        val path = GameData.icon(name) ?: return null
        return decode(ctx, path)?.also { item[name] = it }
    }

    /** 只查缓存不解码：AssetImage 同步快路径用（冷启动 miss 时返回 null 交给异步分支） */
    fun peekItemIcon(name: String): android.graphics.Bitmap? = item[name]

    /**
     * c203：页签 / 装饰图标的「只查缓存」入口。
     * 照 [peekItemIcon] 同款——组合期先查缓存，miss 才丢到 IO 线程解码。
     */
    fun peekTabIcon(name: String): android.graphics.Bitmap? = tab[name]
    fun peekUiIcon(name: String): android.graphics.Bitmap? = ui[name]

    /** 首屏前调用：预热页签图标 + 界面装饰图标 */
    fun warmBasics(ctx: android.content.Context) {
        listOf("img/tab" to tab, "img/ui" to ui).forEach { (dir, cache) ->
            ctx.assets.list(dir)?.forEach { f ->
                decode(ctx, "$dir/$f")?.let { cache[f.removeSuffix(".png")] = it }
            }
        }
    }

    /** 后台调用：预热全部物品图标（坏图跳过；未热到时走现解兜底） */
    fun warmItems(ctx: android.content.Context) {
        GameData.imageMap.forEach { (name, rel) ->
            if (!item.containsKey(name)) decode(ctx, "img/$rel")?.let { item[name] = it }
        }
    }
}

/** 底部页签图标：assets/img/tab/{name}.png，AI 生成的星露谷风像素图标 */
@Composable
fun TabIcon(name: String, size: Dp) {
    val ctx = LocalContext.current
    // c203：缓存 miss 时**不在主线程解码**。原来 `remember { IconCache.tabIcon(ctx, name) }`
    // 在组合期同步 decodeStream——v2.18.1 已在 AssetImage 上修过同一个坑
    // （主线程解码实测单帧 145ms、p99 450ms 掉帧），这里只是没跟上。
    // 现在照 AssetImage 的现成范式：先 peek 缓存，miss 才丢到 IO 线程。
    var bmp by remember(name) { mutableStateOf(IconCache.peekTabIcon(name)) }
    if (bmp == null) {
        LaunchedEffect(name) {
            val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                IconCache.tabIcon(ctx, name)
            }
            if (decoded != null) bmp = decoded
        }
    }
    val b = bmp ?: return
    Image(
        bitmap = b.asImageBitmap(), contentDescription = name,
        modifier = Modifier.size(size), contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}

/** 进度页等处的 UI 装饰图标：assets/img/ui/{name}.png，AI 生成的星露谷风像素图标 */
@Composable
fun UiIcon(name: String, size: Dp) {
    val ctx = LocalContext.current
    // c203：同 TabIcon——miss 走 IO 线程解码，不在组合期占主线程。
    var bmp by remember(name) { mutableStateOf(IconCache.peekUiIcon(name)) }
    if (bmp == null) {
        LaunchedEffect(name) {
            val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                IconCache.uiIcon(ctx, name)
            }
            if (decoded != null) bmp = decoded
        }
    }
    val b = bmp ?: return
    Image(
        bitmap = b.asImageBitmap(), contentDescription = name,
        modifier = Modifier.size(size), contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}

/** 便捷重载：直接传整数像素格数（会按 dp 处理） */
@Composable
fun AssetImage(name: String, size: Int, modifier: Modifier = Modifier) =
    AssetImage(name, size.dp, modifier)

@Composable
fun AssetImage(name: String, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = LocalPixel.current
    // v2.18.1 性能：缓存命中走同步（绝大多数情况）；miss（冷启动 warmItems 未完成的头几秒）
    // 改 IO 线程异步解码——旧实现在组合期主线程 decodeStream，冷启动快滑实测单帧 145ms、
    // p99 450ms 掉帧。miss 期间显示首字占位块，解码完成后自动换上图标。
    var bmp by remember(name) { mutableStateOf(IconCache.peekItemIcon(name)) }
    if (bmp == null && GameData.icon(name) != null) {
        LaunchedEffect(name) {
            val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                IconCache.itemIcon(ctx, name)
            }
            if (decoded != null) bmp = decoded
        }
    }
    if (bmp == null) {
        // 没有图标素材（或还在解码）：首字占位块
        Box(
            modifier
                .size(size)
                .background(c.slot),
            contentAlignment = Alignment.Center,
        ) { PixelText(name.take(1), color = c.inkDim) }
        return
    }
    val b = bmp ?: return
    Image(
        bitmap = b.asImageBitmap(), contentDescription = name,
        modifier = modifier.size(size), contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}

/** 带槽位边框的图标框（示意图 .crow>img / .avatar：槽底 + 内高光 + 深棕描边） */
@Composable
fun FramedIcon(name: String, size: Int, pad: Int = 2) {
    val c = LocalPixel.current
    Box(
        Modifier
            .background(c.slot)
            .slotBevel(inset = 2.dp, width = 1.dp)
            .border(2.dp, c.slotEdge)
            .padding(pad.dp),
    ) { AssetImage(name, size) }
}

/** 木框卡片：深棕描边 + 木纹底 + 米黄内衬，可选标题行（可带 UI 图标）
 *  v2.12 加 accent：左侧 5dp 色条 + 外框用 accent 色，用于 Today 卡片紧急程度区分。
 *  accent=null 时保持 v2.11 观感（外框 woodDark，无左侧条，layout 完全一致） */
@Composable
fun PixelCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleIcon: String? = null,
    accent: Color? = null,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val c = LocalPixel.current
    val borderColor = accent ?: c.woodDark
    Row(
        modifier
            .fillMaxWidth()
            .border(3.dp, borderColor)
            .background(c.wood)
            .woodGrain()
            .padding(5.dp),
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(5.dp)
                    .background(accent),
            )
        }
        Column(Modifier.weight(1f)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (titleIcon != null) {
                        UiIcon(titleIcon, 18.dp)
                        Spacer(Modifier.width(7.dp))
                    }
                    PixelText(title, color = c.cream, shadow = true)
                }
                Spacer(Modifier.height(8.dp))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(2.dp, c.woodDark)
                    .background(c.parchment)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) { content.invoke(ColumnScopeAlias) }
        }
    }
}

/** 占位以便 PixelCard 内容用 column 作用域语义 */
object ColumnScopeAlias {
    @Composable
    operator fun invoke(content: @Composable () -> Unit) = content()
}

/**
 * 可折叠卡片（v2.62）：标题行可点，展开 / 收起内容。
 *
 * ## 折叠有两种动机，阈值完全不同，别混着用
 * ① **省首屏空间**（CodexPage 的属性长表）：行数少就该平铺，折叠反而多一次点击 ——
 *    那边定的阈值是 5 行，超过才折。
 * ② **防剧透**（村民页心事件）：**与行数无关，多少条都折**。实测 34 位村民里
 *    19 位只有 1~2 条心事件（12 位只有 1 条），按 ① 的阈值 they'd 整整齐齐地
 *    照常展开，防剧透等于什么都没做。
 * 本组件**不做任何行数判断**——要不要折由调用方决定，这里只提供交互。
 *
 * 为什么不改 [PixelCard] 的 title 让它可点：它有两百多处调用，加参数等于让
 * 「哪些卡可点」这件事散落到每个调用点，那正是 v2.27「判定口径散落必然漂移」的老坑。
 * 独立一个组件，想折的地方显式换成它，行为一眼可查。
 */
@Composable
fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    collapsedHint: String? = null,
    accent: Color? = null,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val c = LocalPixel.current
    // title 传 null：标题行由本组件自己画（PixelCard 的标题不可点）
    PixelCard(accent = accent) {
        val isrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Column(
            Modifier
                .fillMaxWidth()
                .pressFeedback(isrc)
                .clickable(interactionSource = isrc, indication = null, onClick = onToggle),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelText(if (expanded) "▾" else "▸", color = c.gold, style = Px.title)
                Spacer(Modifier.width(7.dp))
                PixelText(title, color = c.cream, shadow = true, style = Px.title, modifier = Modifier.weight(1f))
            }
            if (!expanded && collapsedHint != null) {
                PixelText(collapsedHint, color = c.inkDim, style = Px.caption, modifier = Modifier.padding(start = 26.dp))
            }
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            RowSep()
            Spacer(Modifier.height(6.dp))
            content.invoke(ColumnScopeAlias)
        }
    }
}

/** 内衬里的行分隔线（示意图 .row border-bottom 2px parchment-2） */
@Composable
fun RowSep() {
    Box(Modifier.fillMaxWidth().height(2.dp).background(LocalPixel.current.parchment2))
}

/**
 * c201 B1：搜索框的「清除」按钮（✕）。
 *
 * ## 为什么要抽成组件
 * 原来这段「✕ + 4dp padding」在**七个地方各写一遍**（今日页全局搜索 / 图鉴 / 村民反查 /
 * 跳板 / 攻略战斗册 / 攻略商店册 / 行程表），样式与手感已经漂移，而且**每处都太小**：
 * 4dp 内边距 + 一个十来 dp 的字 ⇒ 实际可点区不到 16dp，手指要点中很难——
 * 而「点了没反应」会被当成 App 坏了。
 * 抽成一处：内边距在这里改一次，七处一起生效。
 *
 * @param modifier 供调用方定位（通常 `.align(Alignment.CenterEnd)`）。
 */
@Composable
fun PixelSearchClear(
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPixel.current
    Box(
        modifier
            .clickable(onClick = onClear)
            // 8dp：可点区从 ~16dp 提到 ~26dp（像素风里够手指点中，
            // 又不至于把搜索框那一行撑高）。
            .padding(8.dp),
    ) { PixelText("✕", color = c.inkDim) }
}

@Composable
fun Chip(
    text: String,
    border: Color = LocalPixel.current.slotEdge,
    content: Color = LocalPixel.current.ink,
    fill: Color = LocalPixel.current.slot,
) {
    Box(
        Modifier
            .border(2.dp, border)
            .background(fill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) { PixelText(text, color = content, maxLines = 1) }
}

/**
 * c219：米黄纸小条 —— 给「没有卡片、直接压在木壳 / 玻璃 / 屏幕底上」的一行字一个可读底。
 *
 * ## 为什么不直接套 [PixelCard]
 * 卡片 = 3dp 外框 + 5dp 木纹 + 12/10dp 内衬，一行小标题套上去比正文还重；
 * 这里只要羊皮纸底 + 4/8dp 内边距，视觉上接近「段落标签条」。
 *
 * ## 对比度不是拍脑袋
 * 裸压壳底的字在本项目实测过：日间 inkDim/wood 只有 2.04:1、冬季 1.06:1、
 * 展览会 1.03:1；而 ink/inkDim 压 parchment 已由 verify 46.1 在 16 套配色上
 * 钉死 ≥4.5:1。所以「读不清的字」统一往这张米黄底上放，而不是逐套调字色。
 */
@Composable
fun ParchmentStrip(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(LocalPixel.current.parchment)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) { content() }
}

/**
 * 槽位：图标 + 名称（+ 副标题）。
 *
 * v2.40 加 [onClick]：非 null 时整格可点（村民详情的「最爱」用它跳图鉴）。
 * 默认 null = 纯展示，既有调用点的行为与视觉一字未变。
 *
 * ⚠ 用具名参数传 `onClick`——本函数尾部有多个带默认值的参数，
 *   `SlotBox("x") { … }` 会把尾随 lambda 绑到 [width] 上（Kotlin 尾随 lambda
 *   只绑最后一个形参），编译报类型不匹配或静默错位。
 */
@Composable
fun SlotBox(
    name: String,
    sub: String = "",
    size: Dp = 32.dp,
    width: Dp = 74.dp,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalPixel.current
    val isrc = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            // 按下反馈用与列表行同一套（轻微变暗 + 下沉 1dp），不做水波纹
            .then(
                if (onClick != null) {
                    Modifier
                        .pressFeedback(isrc)
                        .clickable(interactionSource = isrc, indication = null) { onClick() }
                } else Modifier
            )
            .background(c.slot)
            .slotBevel(inset = 2.dp, width = 2.dp)
            .border(2.dp, c.slotEdge)
            .padding(horizontal = 3.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AssetImage(name, size)
        Spacer(Modifier.height(2.dp))
        PixelText(name, maxLines = 2)
        if (sub.isNotEmpty()) PixelText(sub, color = c.inkDim, maxLines = 2)
    }
}

@Composable
fun KVRow(key: String, value: String) {
    val c = LocalPixel.current
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
            PixelText(key, color = c.inkDim, modifier = Modifier.width(92.dp))
            PixelText(value, modifier = Modifier.weight(1f))
        }
        RowSep()
    }
}

/**
 * 反馈联系方式：报错模板与关于页共用。
 *
 * ⚠ 2026-11-10：项目开源后**不再有 QQ 群**，改成指向 GitHub 仓库的 Issue 区。
 *   理由：群号是个人联系方式，且群迟早会散；开源仓库的 Issue 是长期可寻址的。
 */
const val FEEDBACK_PLACE = "GitHub Issues（星露小助手仓库）"

/**
 * 源码仓库地址（关于页点按复制、协议抽屉里都引它）。
 * 单点定义：仓库改名/搬家只改这一处。
 */
const val REPO_URL = "https://github.com/huututu939/StardewHelper"

/** 通用复制到剪贴板 + Toast 反馈（v2.10 提取，ReportButton 与待办长按共用） */
fun copyToClipboard(ctx: android.content.Context, label: String, text: String) {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    android.widget.Toast.makeText(ctx, "已复制，发群里或贴帖子下即可", android.widget.Toast.LENGTH_SHORT).show()
}

/** 一键报错：复制报错模板到剪贴板（零权限），发现数据错误粘贴到群里/帖子下即可 */
@Composable
fun ReportButton(page: String, entry: String, detail: String) {
    val ctx = LocalContext.current
    PixelButton("📋 报错", onClick = {
        val text = "【星露小助手 数据报错】\n" +
            "页面：$page\n条目：$entry\n当前显示：$detail\n" +
            "应为：＿＿＿＿（请补充正确数据，最好附游戏截图）\n" +
            "反馈至 $FEEDBACK_PLACE（提 Issue 或贴到社区帖下均可）"
        copyToClipboard(ctx, "stardew_report", text)
    })
}

/**
 * c214：一次短促的触觉震动（系统 Vibrator）。
 *
 * 为什么不用 Compose 的 `LocalHapticFeedback`：它在大量真机（尤其国产 ROM、或系统
 * 关闭触觉时）**静默不生效**（c212 装机实测勾选/按钮无震动）。系统 Vibrator 只要
 * 设备有震动器就触发（与系统/其它 App 主动震动一致）。
 *
 * ⚠ **不读 `Settings.HAPTIC_FEEDBACK_ENABLED` 做总闸** —— 华为等设备该键可为 0
 * （系统「触摸反馈」关）但 App 主动震动不受它管控：实测 `haptic_feedback_enabled=0`
 * 的华为上 launcher/nova 照样震。拿它当闸会把 App 震动整个拦掉（c213 装机实测
 * 一次都不震），故整段拦截已移除。只要 `hasVibrator()` 就尽力震。
 *
 * SDK 31+ 走 VibratorManager，31 以下走 Vibrator。
 *
 * [ms] 单次震动时长（毫秒）。勾选 18ms、按钮 10ms，都是短促轻震，不油腻。
 */
fun vibrateTap(ctx: android.content.Context, ms: Long) {
    runCatching {
        // ⚠ 不读系统开关（Settings.System.HAPTIC_FEEDBACK_ENABLED）做总闸：
        //   华为等设备上这个键可能是 0（系统「触摸反馈」关闭），但 App 主动震动
        //   并不受它管控 —— 实测别的 App（launcher/nova）在 `haptic_feedback_enabled=0`
        //   的华为上照样震，而我们的 App 被这个检查拦掉后一次都没震（用户反馈 c213 仍无震动）。
        //   → 去掉拦截，只要设备有震动器就尽力震（与其他 App 行为一致）。
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
            val mgr = ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                as? android.os.VibratorManager ?: return
            mgr.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
                ?: return
        }
        if (!vibrator.hasVibrator()) return
        // amplitude 128（最大 255 一半偏上）：给足振幅才够感知。
        val effect = android.os.VibrationEffect.createOneShot(ms, 128)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // ⚠ 用 USAGE_ALARM 而非默认/USAGE_TOUCH：华为等设备对 TOUCH 震动按
            // 「触摸反馈强度」缩放，系统触觉关（haptic_feedback_enabled=0）时可能缩到 0
            //   （c213 实测就是这样被拦/被缩）。ALARM 语义不随触摸设置缩放，保证能震。
            //   API 29+ 才有 VibrationAttributes；26-28 退回默认。短促（ms<=28）无闹铃副作用。
            vibrator.vibrate(
                effect,
                android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_ALARM)
                    .build(),
            )
        } else {
            vibrator.vibrate(effect)
        }
    }
}

@Composable
fun PixelCheckbox(checked: Boolean, text: String, onChange: (Boolean) -> Unit) {
    val c = LocalPixel.current
    val sounds = rememberSounds()
    val ctx = LocalContext.current
    // c214 触觉：勾上瞬间震一下确认（与音效同口径，只勾上不取消；静音模式下体感仍可感知）。
    // ⚠ 用系统 Vibrator 而非 LocalHapticFeedback——后者在大量真机（尤其国产 ROM / 关系统触觉）
    //   上静默不生效（实测本版装机无震动）。系统 Vibrator 只要开着触觉就必然有效。
    //   尊重系统「触觉反馈」开关：关了就跳过，不硬震。
    val onChangeHaptic: (Boolean) -> Unit = { toChecked ->
        if (toChecked) vibrateTap(ctx, 28)
        onChange(toChecked)
    }
    // v2.13 勾选 pop：勾上瞬间 ✔ 从 0 弹到 1.3 再回 1（keyedProgress 简化为两次 animateFloatAsState）
    val popScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = androidx.compose.animation.core.keyframes {
            durationMillis = 220
            0f at 0
            1.3f at 110
            1f at 220
        },
        label = "checkPop",
    )
    // v2.13 勾选瞬间：叮咚音效（只在 false→true 触发，取消勾不响）
    var wasChecked by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) {
        if (checked && !wasChecked) sounds.check()
        wasChecked = checked
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChangeHaptic(!checked) }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(if (checked) c.green else c.slot)
                .slotBevel(inset = 2.dp, width = 1.dp)
                .border(2.dp, c.slotEdge),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                PixelText(
                    "✔", color = Color.White,
                    modifier = Modifier.graphicsLayer {
                        scaleX = popScale
                        scaleY = popScale
                    },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        PixelText(text, color = if (checked) c.inkDim else c.ink, strike = checked)
        // 勾选瞬间：右侧 4 粒像素星迸发（300ms 淡出，取消勾不触发）
        SparkleBurst(trigger = checked)
    }
}

/** 勾选迸发：4 粒像素星从勾选框向斜四角飞散 300ms 淡出（v2.13 小确幸）。
 *  trigger 由 false→true 时启动一轮动画；动画结束后星星隐藏。 */
@Composable
private fun SparkleBurst(trigger: Boolean) {
    var burst by remember { mutableStateOf(false) }
    var prevTrigger by remember { mutableStateOf(trigger) }
    LaunchedEffect(trigger) {
        if (trigger && !prevTrigger) {
            burst = true
            kotlinx.coroutines.delay(320)
            burst = false
        }
        prevTrigger = trigger
    }
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (burst) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "burst",
    )
    if (burst && progress < 1f) {
        val c = LocalPixel.current
        androidx.compose.foundation.layout.Box {
            listOf(
                -1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f,
            ).forEach { (dx, dy) ->
                PixelText(
                    "✦",
                    color = c.gold,
                    modifier = Modifier
                        .offset(x = (dx * progress * 14).dp, y = (dy * progress * 14).dp)
                        .graphicsLayer { alpha = 1f - progress },
                )
            }
        }
    }
}

/**
 * 像素按钮。
 *
 * v2.74.0 加 `enabled`：报告预览里「还没画好时不能点保存/分享」需要禁用态。
 * 默认 true，所以**既有调用点行为不变**。
 */
@Composable
fun PixelButton(text: String, onClick: () -> Unit, active: Boolean = false, enabled: Boolean = true) {
    val c = LocalPixel.current
    val ctx = LocalContext.current
    Box(
        Modifier
            .background(if (active && enabled) c.green else c.slot)
            .slotBevel(inset = 2.dp, width = 1.dp)
            .border(2.dp, c.slotEdge)
            .clickable(
                enabled = enabled,
                // c214 触觉：点击给轻震反馈（系统 Vibrator，真机必生效；尊重系统触觉开关）
                onClick = {
                    vibrateTap(ctx, 14)
                    onClick()
                },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        PixelText(
            text,
            color = if (active && enabled) Color.White else if (enabled) c.ink else c.inkDim,
        )
    }
}

@Composable
fun HeartBar(filled: Int, total: Int = 10) {
    val c = LocalPixel.current
    PixelText(
        "♥".repeat(filled.coerceIn(0, total)) + "♡".repeat((total - filled).coerceAtLeast(0)),
        color = c.red,
    )
}

/** 分格进度条：done 格亮金色，其余暗槽（社区中心包 / 房间汇总用） */
@Composable
fun PixelPips(done: Int, total: Int, size: Dp = 12.dp) {
    val c = LocalPixel.current
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            val filled = i < done
            Box(
                Modifier
                    .size(size)
                    .background(if (filled) c.gold else c.slot)
                    .slotBevel(inset = 2.dp, width = 1.dp)
                    .border(2.dp, c.slotEdge),
            )
        }
    }
}

/** 连续像素进度条：适合大总数（博物馆 51 件这种，分格会溢出） */
@Composable
fun PixelBar(done: Int, total: Int, height: Dp = 12.dp, modifier: Modifier = Modifier) {
    val c = LocalPixel.current
    val frac = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    Box(
        modifier
            .height(height)
            .background(c.slot)
            .border(2.dp, c.slotEdge),
    ) {
        if (done > 0) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .padding(2.dp)
                    .fillMaxWidth(frac)
                    .background(c.gold),
            )
        }
    }
}

/** 分段控件：整条深棕描边、格内米黄底、选中格绿底白字（示意图 .seg 样式） */
@Composable
fun PixelSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPixel.current
    Row(
        modifier
            .border(2.dp, c.slotEdge)
            .background(c.parchment2),
    ) {
        options.forEachIndexed { i, opt ->
            val active = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (active) c.green else c.slot)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                PixelText(opt, color = if (active) Color.White else c.inkDim, maxLines = 1)
            }
        }
    }
}

private val WheelItemHeight = 40.dp

/** 滚轮选择器：可视 5 格、中央高亮、吸附滚动；onSettled 上报居中项下标；点击某值 = 选中该值（onTap） */
@Composable
fun PixelWheel(
    options: List<String>,
    initial: Int,
    onSettled: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleCount: Int = 5,
    onTap: ((Int) -> Unit)? = null,
) {
    val c = LocalPixel.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val itemHpx = with(density) { WheelItemHeight.toPx() }
    val state = rememberLazyListState()
    val fling = rememberSnapFlingBehavior(lazyListState = state, snapPosition = SnapPosition.Center)

    // 视觉中心 = 高亮框所在格：找最贴近视口中心的可见项
    val centerIdx by remember { derivedStateOf {
        val info = state.layoutInfo
        if (info.visibleItemsInfo.isEmpty()) return@derivedStateOf initial.coerceIn(0, options.lastIndex)
        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - center) }?.index ?: initial
    } }

    LaunchedEffect(Unit) {
        // 首次落位（打开时的初始值）不震动，之后每换一格轻震一次
        var first = true
        snapshotFlow { centerIdx }.distinctUntilChanged().collect {
            if (first) first = false
            else haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSettled(it)
        }
    }
    // 打开时把初始值滚到中心：先粗定位，再按实际布局测量校正
    LaunchedEffect(initial, options) {
        val target = initial.coerceIn(0, options.lastIndex)
        state.scrollToItem(target)
        val li = state.layoutInfo
        val center = (li.viewportStartOffset + li.viewportEndOffset) / 2f
        val item = li.visibleItemsInfo.firstOrNull { it.index == target }
        if (item != null) {
            val delta = (item.offset + item.size / 2f - center).roundToInt()
            if (delta != 0) state.scroll { scrollBy(delta.toFloat()) }
        }
    }

    Box(
        modifier
            .height(WheelItemHeight * visibleCount)
            .clipToBounds(),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(WheelItemHeight)
                .background(c.slot)
                .slotBevel(inset = 2.dp, width = 1.dp)
                .border(2.dp, c.gold),
        )
        LazyColumn(
            state = state,
            flingBehavior = fling,
            modifier = Modifier.fillMaxWidth().height(WheelItemHeight * visibleCount),
            contentPadding = PaddingValues(vertical = WheelItemHeight * (visibleCount / 2)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(options.size) { i ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(WheelItemHeight)
                        .then(
                            if (onTap != null) Modifier.clickable {
                                onSettled(i)
                                onTap?.invoke(i)
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    PixelText(options[i], color = if (i == centerIdx) c.ink else c.inkDim)
                }
            }
        }
    }
}

@Composable
fun RowSpacer() = Spacer(Modifier.height(12.dp))

