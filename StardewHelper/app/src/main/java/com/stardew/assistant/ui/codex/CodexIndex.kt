package com.stardew.assistant.ui.codex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.stardew.assistant.data.CodexGroup
import com.stardew.assistant.data.GameData
import com.stardew.assistant.ui.nav.NavRequest
import com.stardew.assistant.ui.nav.navBus
import com.stardew.assistant.ui.search.SearchHit
import com.stardew.assistant.ui.search.buildSearchIndex

// ═══════════════════════════════════════════════════════════════════════
// v2.31 共享搜索索引（正文词典与跳到面板必须是同一份数据）
// ═══════════════════════════════════════════════════════════════════════

@Volatile private var cachedGroups: List<CodexGroup>? = null
@Volatile private var cachedIndex: List<SearchHit>? = null

/**
 * 缓存对应的**图鉴视图版本号**（见 [GameData.codexViewVersion]）。
 *
 * v2.73.12 改判的原因：原先用 `groups` 的**引用身份**判缓存（`===`），
 * 而 Mod 数据并进来后视图是 `codexGroupsWithMods` —— 它每次访问都新建对象，
 * 引用身份**永远不同**，缓存等于没有，每次重组都重建上千条索引
 * （v2.31 专门为这个性能问题做过优化，不能倒退回去）。
 * 改用版本号后：视图没变就命中缓存，变了就重建。
 */
@Volatile private var cachedVersion: Int = -1

/**
 * 批次 2 建的那份全分类索引，现在**全项目只从这里出**。
 *
 * 原来只有 `CodexPage` 里的 `remember { buildSearchIndex(groups) }` 一处，
 * v2.31 追加了「跳到面板」和「正文词典」两个消费方。三处各建一次的话，
 * 一旦某处改了过滤条件（比如空名规则），正文链接与搜索结果就会对不上——
 * 正是 v2.27「判定口径散落多处必然漂移」的同型。
 *
 * ⚠ 入参用**运行时视图** [GameData.codexGroupsWithMods]（含 Mod 包的作物/料理/鱼/
 *   商店/地点），否则那些条目在全局搜索里一个字都搜不到。
 *   缓存判据见 [cachedVersion]。
 */
fun codexIndex(groups: List<CodexGroup>, version: Int = GameData.codexViewVersion): List<SearchHit> {
    val cur = cachedIndex
    if (cur != null && cachedVersion == version) return cur
    val built = buildSearchIndex(groups)
    cachedGroups = groups
    cachedIndex = built
    cachedVersion = version
    return built
}

@Composable
fun rememberCodexIndex(): List<SearchHit> {
    // ⚠ groups 每次重组都是新对象（codexGroupsWithMods 的运行时视图），
    //   所以 remember 的键必须是**版本号**，不能是 groups 本身。
    val version = GameData.codexViewVersion
    val groups = GameData.codexGroupsWithMods
    return remember(version) { codexIndex(groups, version) }
}

// ═══════════════════════════════════════════════════════════════════════
// 正文词典切分
// ═══════════════════════════════════════════════════════════════════════

/** [nameToHits] 的缓存（按图鉴视图版本号判）。 */
@Volatile private var cachedNameToHits: Map<String, List<SearchHit>>? = null
@Volatile private var cachedNameToHitsVersion: Int = -1

/**
 * 物品名 → 全部命中。重名的名字会对应多条（实测 392 个名字跨分类重复）。
 *
 * ⚠ v2.73.12：从 `by lazy` 改成**按图鉴视图版本号**的缓存。
 *   `by lazy` 只算一次 —— 装上扩展包后新加的 SVE 作物/料理/鱼/商店/地点
 *   就永远进不了词典（搜索「虚空鳗」「鱼饺」都搜不到）。
 */
internal val nameToHits: Map<String, List<SearchHit>>
    get() {
        val v = GameData.codexViewVersion
        val cur = cachedNameToHits
        if (cur != null && cachedNameToHitsVersion == v) return cur
        val built = codexIndex(GameData.codexGroupsWithMods, v).groupBy { it.itemName }
        cachedNameToHits = built
        cachedNameToHitsVersion = v
        return built
    }

/**
 * 首字分桶的匹配词典：`首字 → 以它开头的候选名（长度降序）`。
 *
 * ## 为什么要分桶
 * 朴素做法是「遍历 1000 多个名字逐个 `indexOf`」，一行 30 字就是 3 万次字符串比较，
 * 攻略页几百行 → 每次组合白付一遍（v2.17 E3 那类卡顿）。
 *
 * 分桶后每个文本位置只试「首字相同」的那一小撮，中文平均每字候选 ≈ 0.35，
 * 30 字一行降到约 30 次比较，快两个数量级。
 *
 * ## 为什么要长度降序
 * 取**最长匹配**：「硬木」必须优先于任何以「硬」开头但更短的候选，
 * 否则会切成「硬」+「木」两段（后者根本不在词典里，结果是漏匹配）。
 *
 * ## 为什么过滤掉 1 字名
 * 图鉴里有 14 个单字名（煤、盐之类）。中文里单字命中率极高，
 * 「头发」「弹簧」「花生」都会命中「发」「花」，观感是满屏误伤。
 * 长度 ≥2 是这条功能能用的前提，不是可调项。
 */
/** [bucketByFirst] 的缓存（按图鉴视图版本号判）。 */
@Volatile private var cachedBucket: Map<Char, List<String>>? = null
@Volatile private var cachedBucketVersion: Int = -1

/**
 * 首字分桶的匹配词典：`首字 → 以它开头的候选名（长度降序）`。
 *
 * ## 为什么要分桶
 * 朴素做法是「遍历 1000 多个名字逐个 `indexOf`」，一行 30 字就是 3 万次字符串比较，
 * 攻略页几百行 → 每次组合白付一遍（v2.17 E3 那类卡顿）。
 *
 * 分桶后每个文本位置只试「首字相同」的那一小撮，中文平均每字候选 ≈ 0.35，
 * 30 字一行降到约 30 次比较，快两个数量级。
 *
 * ## 为什么要长度降序
 * 取**最长匹配**：「硬木」必须优先于任何以「硬」开头但更短的候选，
 * 否则会切成「硬」+「木」两段（后者根本不在词典里，结果是漏匹配）。
 *
 * ## 为什么过滤掉 1 字名
 * 图鉴里有 14 个单字名（煤、盐之类）。中文里单字命中率极高，
 * 「头发」「弹簧」「花生」都会命中「发」「花」，观感是满屏误伤。
 * 长度 ≥2 是这条功能能用的前提，不是可调项。
 *
 * ⚠ v2.73.12：同 [nameToHits]，从 `by lazy` 改成**按图鉴视图版本号**的缓存
 *   ——否则装包后新增的 SVE 条目永远进不了正文词典。
 */
internal val bucketByFirst: Map<Char, List<String>>
    get() {
        val v = GameData.codexViewVersion
        val cur = cachedBucket
        if (cur != null && cachedBucketVersion == v) return cur
        val built = nameToHits.keys.asSequence()
            .filter { it.length >= 2 }
            .groupBy { it.first() }
            .mapValues { (_, names) -> names.sortedByDescending { it.length } }
        cachedBucket = built
        cachedBucketVersion = v
        return built
    }

internal sealed interface Seg {
    data class Plain(val text: String) : Seg
    data class Hit(val text: String) : Seg
}

/**
 * 把一段文本切成「普通段 / 物品名段」。
 *
 * 命中后**跳过整个名字的长度**再继续扫描，保证最长匹配且不会自重叠
 * （「石头」不会被切成「石」+「头」）。
 *
 * ⚠ 按 UTF-16 code unit 前进；emoji 占两格会被原样复制到 Plain 段里，
 *   最终 toString 时 surrogate pair 自动合回正确字符。
 *   词典里全是中文名，不会以 high surrogate 开头，所以 emoji 内部不会被误切。
 */
internal fun segmentGuideText(text: String): List<Seg> {
    if (text.isEmpty() || bucketByFirst.isEmpty()) return listOf(Seg.Plain(text))
    val out = ArrayList<Seg>(4)
    val buf = StringBuilder()
    var i = 0
    while (i < text.length) {
        var matched = false
        for (name in bucketByFirst[text[i]] ?: emptyList()) {
            if (text.startsWith(name, i)) {
                if (buf.isNotEmpty()) {
                    out.add(Seg.Plain(buf.toString()))
                    buf.setLength(0)
                }
                out.add(Seg.Hit(name))
                i += name.length
                matched = true
                break
            }
        }
        if (!matched) {
            buf.append(text[i])
            i++
        }
    }
    if (buf.isNotEmpty()) out.add(Seg.Plain(buf.toString()))
    return out
}

/**
 * 名字 → 跳转指令（**纯函数**，可单测）。
 *
 * **唯一命中才直达**：实测 392 个名字跨分类重复（「木材」22 次、「韭葱」4 次）。
 * 名字唯一就直接开详情；其余一律退化成「跳到该词的跨分类搜索结果」，
 * 让用户自己挑那条。宁可多一次点击，也不要点开一个看起来对、其实错的条目
 * （与 v2.30.0「最近查看存名字不存下标」同一条纪律）。
 *
 * v2.40：**零命中也去搜索页**，不再静默返回。村民页「最爱」把送礼项全做成
 * 可点后实测：有一批图鉴确实查无此物（「仙女盒」「青蛙蛋」这类 1.6 新物，
 * 以及「蛋类」「蘑菇类」这种**品类名**——图鉴按具体物品索引，本来就查不到）。
 * 这些格子以前点了毫无反应，
 * 现在落到图鉴搜索页，空结果会明写「没有匹配「X」的条目」——
 * 把「没收录」变成看得见的答案，而不是让人以为 App 卡了。
 *
 * v2.96：**零命中时多回落一次**——按品质前缀剥掉再查（详见函数体里的注释）。
 *   与「不做模糊匹配」不冲突：回落用的是**精确名字**（剥前缀后的本体名），
 *   不是包含 / 前缀猜测，剥不出就还是落搜索页。
 *
 * ⚠ 查无分两种，别混为一谈（v2.68.2 拆开的）：
 *   ① **真数据缺口**——图鉴里没有这个物品，靠补数据；
 *   ② **译名不一致**——图鉴里**有**，只是礼物名写成了别名
 *      （「太阳能精华」实为「太阳精华」、「黑莓脆饼」实为「黑莓脆皮饼」），
 *      于是同一件东西两个叫法，点了就落空。这类必须**改名对齐图鉴**，
 *      而不是补数据。当前清单与逐条证据见 tools/verify_v214.py 第 58.30 节。
 *
 * ⚠ 这里**不写死条目数**：数字随数据变，写死就是给下一次改数据的人留一个
 *   「改了数没改注释」的失实记录（本文件的 161/26 就是这么烂掉的）。
 *   数字以 verify 58.30 的实测 warn 为准。
 *
 * ⚠ 故意**不做**模糊 / 包含匹配（量过：双向包含会把「咖啡」送进「咖啡豆」、
 *   「鱿鱼墨汁」送进「鱿鱼」，是错的条目）。缺口靠改数据补，
 *   不靠猜一个跳转目标。
 *
 * ## 回落顺序
 * ① **唯一命中** → 直达详情；
 * ② **零命中** → 品质前缀回落（「金星南瓜」→「南瓜」）→ 别名回落（「果酒」→「酒」）；
 * ③ **重名（≥2）** → 退化成搜索结果（**不硬跳**）。
 *
 * ## ⚠ v2.100.0 / c182：别名回落的门是 `hits.size == 0`，不是「前一步没命中」
 *
 * 原来写成 `if (alt.isEmpty())`（`alt` 是品质前缀回落的结果）：
 * `alt` 只在零命中时才非空 ⇒ **重名时 `alt` 也是空 list** ⇒ 重名也会走进别名回落分支
 * ⇒ 若「别名名本身在图鉴里有 ≥2 条」且「规范名恰好 1 条」，
 * 就会**硬跳**到规范名那条 —— **绕过「重名不硬跳」这条不变式**。
 * （当前数据触发不了：20 条别名里别名侧在图鉴中出现的 3 条都只有 1 条。）
 * 现在把门写成显式的 `hits.size == 0`，别名回落只可能发生在零命中时。
 */
internal fun resolveJumpRequest(
    name: String,
    hitsOf: (String) -> List<SearchHit>,
): NavRequest {
    val hits = hitsOf(name)
    if (hits.size == 1) {
        val h = hits.first()
        return NavRequest.Codex(groupId = h.groupId, targetIndex = h.index)
    }
    // ⚠ 下面两段落回（品质前缀 / 别名）**只在零命中时**允许 ——
    //   重名一律退化成搜索结果（宁多点一次，也不要点开一个看起来对、其实错的条目）。
    if (hits.size == 0) {
        // v2.96：零命中时按**品质前缀**回落一次（「金星南瓜」→「南瓜」）。
        //   收集包里的高品质作物**全带前缀**（茶水间「高品质作物」4 项全是「金星X」），
        //   而图鉴按**本体名**收录 —— 不回落就会落到「没有匹配「金星南瓜」的条目」的
        //   空搜索页，点图标像坏了。
        //   剥前缀后本体名唯一 → 直达详情；本体名也重名 → 退化成**本体名**的搜索结果。
        //   ⚠ 复用 GameData.stripQuality（与「获取途径」查来源同一套剥前缀口径），不另写规则。
        val stripped = GameData.stripQuality(name)
        if (stripped != name) {
            val alt = hitsOf(stripped)
            if (alt.size == 1) {
                val h = alt.first()
                return NavRequest.Codex(groupId = h.groupId, targetIndex = h.index)
            }
            if (alt.isNotEmpty()) {
                return NavRequest.Codex(openSearch = stripped)
            }
        }
        // v2.99.0 / c180：再按**别名表**回落一次（葡萄酒 → 酒、动物毛 → 羊毛）。
        //   同一条纪律：**同一个东西的另一个名字**，归一后按规范名再查一次。
        //   · 规范名唯一命中 → 直达详情；
        //   · 规范名重名 → 退化成规范名的搜索结果（仍守「重名不硬跳」）；
        //   · 规范名也查无 → 落回原名的搜索页（什么都没查到，如实落到空结果页）。
        //   ⚠ 仍复用 GameData.canonicalName（全 App 唯一归一实现），不另写别名规则。
        val canon = GameData.canonicalName(name)
        if (canon != name) {
            val ch = hitsOf(canon)
            if (ch.size == 1) {
                val h = ch.first()
                return NavRequest.Codex(groupId = h.groupId, targetIndex = h.index)
            }
            if (ch.isNotEmpty()) {
                return NavRequest.Codex(openSearch = canon)
            }
        }
        return NavRequest.Codex(openSearch = name)
    }
    // 重名（≥2 条）：不硬跳，落回原名的搜索结果，让用户自己挑那一条。
    return NavRequest.Codex(openSearch = name)
}

/**
 * c221：只在**零命中**时走一遍品质/别名归一回落的候选命中（不含 NavRequest 包装）。
 *
 * [resolveJumpRequest] 里那段「剥品质前缀 → 查别名」的判定，对收集包「就地翻页」
 * 也要用——但那里要的是「回落后**唯一可翻**的 SearchHit」，不是 NavRequest。
 * 把回落逻辑抽成这一个纯函数，两处共用同一套归一口径（星金南瓜/大壶牛奶这类
 * 12 个别名侧名字在图鉴里可以就地翻，而不是被当成「查不到」跳去搜索）。
 *
 * 返回：依序尝试「原名 → 剥品质前缀 → 规范名」，第一个非空命中即返回。
 * 命中多条也原样返回（由调用方决定是唯一还是重名），零命中返回空。
 */
internal fun codexFallbackHits(
    name: String,
    hitsOf: (String) -> List<SearchHit>,
): List<SearchHit> {
    hitsOf(name).let { if (it.isNotEmpty()) return it }
    val stripped = GameData.stripQuality(name)
    if (stripped != name) {
        hitsOf(stripped).let { if (it.isNotEmpty()) return it }
    }
    val canon = GameData.canonicalName(name)
    if (canon != name) {
        hitsOf(canon).let { if (it.isNotEmpty()) return it }
    }
    return emptyList()
}

/**
 * 名字 → 跳转（把指令发到全局导航总线）。
 *
 * 判定逻辑全在 [resolveJumpRequest]（纯函数，单测直接调它）。
 */
fun resolveItemJump(name: String) {
    navBus.value = resolveJumpRequest(name) { nameToHits[it].orEmpty() }
}

/**
 * 切分 + 生成带链接的 [AnnotatedString]；**一个物品名都没命中时返回 null**。
 *
 * 返回 null 是有意义的：`PixelText` 拿到 null 就走原来的 `Text(text = ...)` 路径，
 * 连 AnnotatedString 都不构造，非攻略作用域下这一步是纯短路。
 *
 * 非 @Composable —— `PixelText` 在 remember 的 lambda 里调它，
 * @Composable 函数不能在 remember 块里调用（会报 "@Composable invocations can only
 * happen from the context of a @Composable function"）。颜色由调用方传进来。
 */
internal fun buildLinkedAnnotated(segs: List<Seg>, linkColor: Color): AnnotatedString? {
    if (segs.none { it is Seg.Hit }) return null
    val sb = AnnotatedString.Builder()
    for (s in segs) {
        when (s) {
            is Seg.Plain -> sb.append(s.text)
            is Seg.Hit -> sb.withLink(
                LinkAnnotation.Clickable(
                    tag = s.text,
                    styles = TextLinkStyles(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    ),
                    linkInteractionListener = { resolveItemJump(s.text) },
                )
            ) { sb.append(s.text) }
        }
    }
    return sb.toAnnotatedString()
}