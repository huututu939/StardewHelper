package com.stardew.assistant.ui.villagers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import com.stardew.assistant.ui.glass.LocalGlassInset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.GameData
import com.stardew.assistant.ui.DetailDrawer
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.CollapsibleCard
import com.stardew.assistant.ui.FramedIcon
import com.stardew.assistant.ui.HeartBar
import com.stardew.assistant.ui.KVRow
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PageBar
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelSearchClear
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.ReportButton
import com.stardew.assistant.ui.codex.resolveItemJump
import com.stardew.assistant.ui.mods.VillagerAvatar
import com.stardew.assistant.ui.pressFeedback
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.slotBevel
import com.stardew.assistant.ui.SlotBox
import kotlinx.coroutines.launch
import com.stardew.assistant.ui.ParchmentStrip

private val mineralNames by lazy {
    GameData.codexGroups.firstOrNull { it.id == "museumMinerals" }?.items?.map { it.name }?.toSet() ?: emptySet()
}
private val fishNames by lazy { GameData.fishing.map { it.name }.toSet() }

/**
 * v2.73.1：行程地点里的季节后缀（「安迪农场(春)」）。
 *
 * ⚠ 半角括号（包里就是这么写的），不是全角「（春）」。
 * 匹配不上就返回 null，UI 按「全年常驻」处理——**猜不着宁可标宽**，
 * 把一个全年常驻地点标成「灰色 = 别去」是会把人坑到白跑一趟的错。
 */
private val SCHEDULE_SEASON_RE = Regex("""^(.+)\(([春夏秋冬])\)$""")

/**
 * 物品来源一句话提示（按图鉴归属推断，找不到则无）
 *
 * ⚠ v2.73.4：原作物的来源行读 [GameData.crops]，而它已是含 Mod 包的**计算属性**。
 *   这里**不能**用 `by lazy`（进程内只算一次）：装 SVE 包后这张表永远停在装包前，
 *   表现是「SVE 作物送出去不显示来源提示」。与计算页/今日页共用
 *   [GameData.modCropsKey] 这个单源缓存键。
 */
private fun buildSourceHint(): Map<String, String> {
    val m = mutableMapOf<String, String>()
    GameData.crops.forEach { c -> m[c.name] = "${c.seasons.joinToString("/")}自种" }
    fun put(gid: String, label: String) {
        // v2.73.12：读**运行时视图**（含 Mod 包的鱼/料理等）。
        //   静态组里没有 SVE 的鱼与料理 → 送它们时不显示「钓鱼 / 烹饪」来源提示。
        GameData.codexGroupsWithMods.firstOrNull { it.id == gid }?.items?.forEach { m.putIfAbsent(it.name, label) }
    }
    put("fishing", "钓鱼")
    put("museumMinerals", "矿洞")
    put("museumArtifacts", "挖矿·古物")
    put("recipes", "烹饪")
    put("crafting", "制作")
    put("fruitTrees", "果树")
    put("animals", "畜牧")
    put("tackle", "渔具鱼饵")
    return m
}

/**
 * [buildSourceHint] 的缓存，key = [GameData.modCropsKey]。
 *
 * 这个函数在顶层（非 composable），所以不能直接用 `remember`；改成
 * 「键变了就重算」。键本身是 GameData 的单源（(包数, Mod 作物数)），
 * 与计算页/今日页的 remember 键同源。
 */
@Volatile
private var sourceHintCacheKey: Pair<Int, Int>? = null
@Volatile
private var sourceHintCache: Map<String, String> = emptyMap()

private val sourceHint: Map<String, String>
    get() {
        val key = GameData.modCropsKey
        if (sourceHintCacheKey != key) {
            sourceHintCache = buildSourceHint()
            sourceHintCacheKey = key
        }
        return sourceHintCache
    }

/** 送礼规划：物品 → 获取途径一句话（itemSources 精确 join，回退 sourceHint 类别推断） */
private fun giftSource(item: String): String {
    GameData.itemSources[GameData.stripQuality(item)]?.let { s ->
        val place = s.places.substringBefore("｜").take(18)
        return if (s.season.isNotBlank()) "${s.season}·$place" else place
    }
    return sourceHint[item] ?: ""
}

/**
 * 物品类别 → 该类别在礼物表里的各种叫法。
 *
 * ## 为什么一张表
 * 同一类东西在不同来源的数据里叫法不一样，而礼物表是**精确匹配**：
 * - 原版 wiki 口径：`宝石与矿物`、`鱼与海鲜类`（[attitudeOf] 原有的两个）
 * - SVE 包口径：`所有矿物`、`所有鱼`、`所有蛋类`、`所有花`、`所有采集物`、`所有种子`……
 *
 * 只认前一种的话，SVE 村民的整类态度在反查里全部失效——打「石英」查不到「所有矿物」，
 * 用户看到的是「没人喜欢石英」，而真实答案可能是「7 个 SVE 村民觉得它最爱」。
 * 这是**漏报**，比报错更坏：用户据此决定不送这个礼物。
 *
 * ⚠ 顺序即优先级：前面的类别先匹配命中。矿物/鱼与「采集物」有交叠
 *   （石英既是矿物也可能被算采集物），所以矿物与鱼排前面，取更具体的那个。
 */
private val CATEGORY_ALIASES: List<Pair<Set<String>, List<String>>> = listOf(
    setOf("宝石与矿物", "所有矿物", "所有金属资源") to listOf("宝石与矿物", "所有矿物", "所有金属资源"),
    setOf("鱼与海鲜类", "所有鱼") to listOf("鱼与海鲜类", "所有鱼"),
    setOf("蛋类", "所有蛋类") to listOf("蛋类", "所有蛋类"),
    setOf("奶类", "所有奶类") to listOf("奶类", "所有奶类"),
    setOf("所有花", "花") to listOf("所有花", "花"),
    setOf("所有种子", "种子") to listOf("所有种子", "种子"),
    setOf("所有采集物", "采集物") to listOf("所有采集物", "采集物"),
    setOf("所有果实", "果实", "水果") to listOf("所有果实", "果实", "水果"),
    setOf("所有树产品", "树产品") to listOf("所有树产品", "树产品"),
    setOf("所有动物产品", "动物产品") to listOf("所有动物产品", "动物产品"),
    setOf("所有料理", "料理", "食物") to listOf("所有料理", "料理", "食物"),
    setOf("所有可制作物", "可制作物") to listOf("所有可制作物", "可制作物"),
    setOf("所有工匠制品", "工匠制品") to listOf("所有工匠制品", "工匠制品"),
    setOf("所有戒指", "戒指") to listOf("所有戒指", "戒指"),
)

/** [item] 属于哪个类别（返回该类别的全部别名，用于查礼物表）；不属于任何类别返回空串。 */
private fun categoryAliasesOf(item: String): List<String> =
    CATEGORY_ALIASES.firstOrNull { (members, _) -> item in members }?.second ?: emptyList()

/**
 * 物品落在哪一段礼物表里：最爱 / 喜欢 / 讨厌 / 憎恶 / 中立 / null。
 *
 * 匹配顺序固定：**精确 → 类别别名 → 单向子串**。
 * 精确优先于子串是因为「咖啡」不该被「咖啡豆」命中（CodexIndex 记着这个坑，
 * 方向必须是单向，不能双向 contains）。
 *
 * 五段之间的顺序**必须与引擎判定序一致**（c208 校正）：最爱 > 憎恶 > 喜欢 > 讨厌 > 中立。
 *   依据官方源码 NPC.cs getGiftTasteForThisItem 的个人段检查顺序
 *   （list2[0]最爱 → list2[3]憎恶 → list2[1]喜欢 → list2[2]讨厌 → list2[4]中立）——
 *   注意官方 raw 的段语义是 4,5=讨厌 / 6,7=憎恶 / 8,9=中立（Modding:Gift taste data），
 *   c206 曾把这三段装反、本函数段序也标错过，c208 一并纠正。
 *
 * ⚠ 官方 raw **允许**同一条物品出现在多段（例：黄水仙同时在乔迪的喜欢 seg3 与憎恶 seg7），
 *   引擎按上述优先级取先命中的一段。c208 起数据层已按引擎判定去重（每物品只挂一段），
 *   所以本函数的段序只影响「无精确命中时的子串兜底」——仍按引擎序排列，双保险。
 */
private fun tierOf(villager: com.stardew.assistant.data.GiftVillager, item: String): String? {
    if (item.isEmpty()) return null
    val tiers: List<Pair<List<String>, String>> = listOf(
        villager.loves to "最爱",
        villager.hates to "憎恶",
        villager.likes to "喜欢",
        villager.dislikes to "讨厌",
        villager.neutrals to "中立",
    )
    val aliases = categoryAliasesOf(item)
    for ((list, label) in tiers) {
        if (item in list) return label
        if (aliases.any { it in list }) return label
    }
    for ((list, label) in tiers) {
        if (list.any { it.contains(item) }) return label
        if (aliases.any { a -> list.any { it.contains(a) } }) return label
    }
    return null
}

/**
 * 物品对某村民的态度：最爱 / 喜欢 / 讨厌 / 憎恶 / 中立 / null。
 *
 * v2.73：返回**五段**（原来只返回最爱/喜欢两段）。
 *
 * ⚠ 消费方有两处，语义不同，别混：
 *  - 详情页的礼物分区直接读各段列表，不用这个函数；
 *  - 反查页「谁最爱这个东西」用的是前两段——**后三段不该混进「该送给谁」**，
 *    那是「送给他会掉好感」，与反查的用途相反。反查调用方自己只取前两段。
 */
fun attitudeOf(villager: com.stardew.assistant.data.GiftVillager, item: String): String? =
    tierOf(villager, item)

/**
 * 反查结果里的一列村民行。五张卡（最爱/喜欢/中立/避雷/详情入口）共用这一份。
 *
 * 抽出来不是为了少写几行，而是为了**行为一致**：v2.73 加「中立」段时，
 * 如果每张卡各抄一份行渲染，第三张卡漏抄 `pressFeedback` 或头像就会变成
 * 「只有中立那列点不动」——这种不一致截图上看不出来，只有用户点得到。
 */
@Composable
private fun VillagerHitList(
    list: List<com.stardew.assistant.data.GiftVillager>,
    c: com.stardew.assistant.ui.PixelColors,
    onPick: (String) -> Unit,
) {
    list.forEach { v ->
        val isrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Row(
            Modifier
                .fillMaxWidth()
                .pressFeedback(isrc)
                .clickable(interactionSource = isrc, indication = null) { onPick(v.name) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VillagerAvatar(v.name, 30)
            Spacer(Modifier.width(8.dp))
            PixelText(v.name)
            if (v.fromMod) {
                Spacer(Modifier.width(5.dp))
                Chip(GameData.modTagOf(v) ?: "扩展", border = c.gold)
            }
        }
    }
}

@Composable
fun VillagersPage(
    snap: com.stardew.assistant.data.PrefsRepo.Snapshot,
    repo: com.stardew.assistant.data.PrefsRepo,
    visible: Boolean,
    // v2.73.14：「🧩 扩展」入口已挪到**进度页**（那边总览段下面有实测约 1000px 的空白，
    //   而「总进度 X/279」的分母会随扩展包变化 —— 数字不对的那一页就是装包的那一页）。
    //   这里保留空实现是为了让**已有调用方**（今日页的生日卡复用本文件的数据，但没接线）
    //   不必改也能编译；MainActivity 那处已改到进度页。
    onOpenMods: () -> Unit = {},
    // v2.94.0：「🗺 行程」入口——进独立的 NPC 行程表页。
    //   默认空实现 = 没接线的调用方不显示入口（今日页复用本文件的数据，但没接线）。
    onOpenSchedule: () -> Unit = {},
) {
    var reverse by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 详情走页内抽屉：列表页不被销毁，返回后滚动位置 / 反查关键词原样保留
    var target by remember { mutableStateOf<String?>(null) }
    val c = LocalPixel.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val queryFocus = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    com.stardew.assistant.ui.ScrollToTopEffect(com.stardew.assistant.Screen.Villagers, listState)
    val showReverseInput = reverse && target == null
    // 进反查模式自动聚焦弹键盘，少点一下
    androidx.compose.runtime.LaunchedEffect(reverse, target) {
        if (showReverseInput) {
            kotlinx.coroutines.delay(120)
            runCatching { queryFocus.requestFocus() }
            keyboard?.show()
        }
    }

    // v2.31「跳到…」面板：外部（长按底 tab → JumpPanel）请求直接打开某人详情。
    // 名字对不上（数据包删除 / 改名）就**只当没这回事**——宁可不开抽屉，
    // 也不能开一个空白或错人的抽屉。消费后立刻置 null，不重复触发。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.stardew.assistant.ui.nav.villagerTarget.collect { n ->
            val name = n ?: return@collect
            if (GameData.gifts.any { it.name == name }) target = name
            com.stardew.assistant.ui.nav.villagerTarget.value = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageBar("村民") {
            // v2.73.14：「🧩 扩展」入口**从村民页挪走**了 —— 扩展包拆成 6 个分片后，
            //   5/6 的内容（作物/料理/鱼/商店/地点）都显示在图鉴，进度页也因为
            //   「总进度 X/279」的分母会随扩展包变化而天然相关。
            //   村民页现在只留自己的特色功能「🔍 反查」。
            PixelButton(if (reverse) "查村民" else "🔍 反查", onClick = { reverse = !reverse }, active = reverse)
            // v2.94.0：行程表入口。与「反查」并列——两者都是「拿村民当索引」的玩法。
            Spacer(Modifier.width(6.dp))
            PixelButton("🗺 行程", onClick = onOpenSchedule)
        }
        if (reverse) {
            // 反查模式：输入物品 → 全村庄态度
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .background(c.slot)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    if (query.isEmpty()) PixelText("输入手上的物品，看该送给谁…", color = c.ink)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = Px.body.copy(color = c.ink),
                        cursorBrush = SolidColor(c.ink),
                        modifier = Modifier.fillMaxWidth().focusRequester(queryFocus),
                    )
                    if (query.isNotEmpty()) {
                        PixelSearchClear(
                            onClear = { query = "" },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
                val q = query.trim()
                if (q.isNotEmpty()) {
                    // v2.73：分五段（原来只有最爱/喜欢两段）。
                    // 判据用 [tierOf] 的标签而不是 `attitudeOf(...) == "最爱"` 的字符串比较——
                    // 后者一旦漏改就会把「讨厌这个」的人列进「该送给谁」，那比漏报严重得多。
                    // c191 性能：这五个 filter 包 remember(q)——它们在 BasicTextField 的
                    // onValueChange 驱动的重组里直接跑，此前每敲一个字就把 34 个村民全量
                    // 态度判定五遍（每遍 tierOf 内含 5 层列表精确+包含匹配 + 别名展开）。
                    // attitudeOf/categoryAliasesOf 均为纯函数，q 是唯一数据键，缓存安全；
                    // filter 保序，五段内容与顺序与无缓存版逐字一致。
                    val loved = remember(q) { GameData.gifts.filter { attitudeOf(it, q) == "最爱" } }
                    val liked = remember(q) { GameData.gifts.filter { attitudeOf(it, q) == "喜欢" } }
                    val disliked = remember(q) { GameData.gifts.filter { attitudeOf(it, q) == "讨厌" } }
                    val hated = remember(q) { GameData.gifts.filter { attitudeOf(it, q) == "憎恶" } }
                    val neutral = remember(q) { GameData.gifts.filter { attitudeOf(it, q) == "中立" } }
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + LocalGlassInset.current), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // ⚠ 只把「值得送」的人放前面（最爱/喜欢），中性在后、讨厌/憎恶垫底。
                        //   反查的用途是「这东西该送给谁」，把该避雷的人混在第一屏等于答错问题。
                        item {
                            PixelCard(title = "🎁 最爱（${loved.size} 人）") {
                                if (loved.isEmpty()) PixelText("没有人把它当最爱 ☹", color = c.inkDim)
                                VillagerHitList(loved, c, onPick = { target = it })
                            }
                        }
                        item {
                            PixelCard(title = "👍 喜欢（${liked.size} 人）") {
                                if (liked.isEmpty()) PixelText("无人喜欢 ☹", color = c.inkDim)
                                VillagerHitList(liked, c, onPick = { target = it })
                            }
                        }
                        if (neutral.isNotEmpty()) {
                            item {
                                PixelCard(title = "😐 中立（${neutral.size} 人）") {
                                    VillagerHitList(neutral, c, onPick = { target = it })
                                    Spacer(Modifier.height(4.dp))
                                    PixelText("送这些不加分也不减分，可以随便送。", color = c.inkDim)
                                }
                            }
                        }
                        if (disliked.isNotEmpty() || hated.isNotEmpty()) {
                            item {
                                // c188：避雷段原来把 List<GiftVillager> 直接 joinToString ——
                                //   data class 落 toString 就成了「GiftVillager(name=乔迪, …)」
                                //   一整串原始文本（真机截图报障：搜黄水仙如此，搜任何有
                                //   讨厌/憎恶的物品都如此——病根在渲染不在数据）。
                                //   213 行注释早点名「五段行为一致」的教训，这是第五段没走：
                                //   现在同样走 VillagerHitList（头像 + 名字 + 可点进详情）。
                                PixelCard(title = "⚠ 避雷（${disliked.size + hated.size} 人）") {
                                    if (hated.isNotEmpty()) {
                                        PixelText("憎恶（效果 −80）：", color = c.red)
                                        VillagerHitList(hated, c, onPick = { target = it })
                                    }
                                    if (disliked.isNotEmpty()) {
                                        if (hated.isNotEmpty()) Spacer(Modifier.height(6.dp))
                                        PixelText("讨厌（效果 −45）：", color = c.inkDim)
                                        VillagerHitList(disliked, c, onPick = { target = it })
                                    }
                                }
                            }
                        }
                        item {
        // v2.17 D9：张数从数据动态取（旧写死 51 与 museum.json 的 53 长期不符；v2.17 后古物口径 42）
        val minN = com.stardew.assistant.data.GameData.codexGroups.firstOrNull { it.id == "museumMinerals" }?.items?.size ?: 0
        val fishN = com.stardew.assistant.data.GameData.fishing.size
        // c223：inkDim 裸压屏幕底最低 1.09 → 上米黄纸条
        ParchmentStrip {
            PixelText("口径：类别推断按「所有矿物 / 所有鱼 / 所有蛋类」等类别词匹配；装了扩展包就连 SVE 村民一起算", color = c.inkDim)
        }
    }
                    }
                }
            }
        } else {
            // v2.18.1：排序按数据 remember——旧写法每次重组（开抽屉/收抽屉）都重排 34 个村民
            // v2.71：特别关心的排最前（组内仍按生日序）。挂 snap.followed 做 key——
            // 点 ★ 后名单变化必须触发重排，否则新标的人要切页才挪到最前。
            val sorted = remember(GameData.gifts, snap.followed) {
                val byBirthday = compareBy<com.stardew.assistant.data.GiftVillager>(
                    { it.birthdaySeason?.let { s -> listOf("春", "夏", "秋", "冬").indexOf(s) } ?: 9 },
                    { it.birthdayDay ?: 0 },
                )
                GameData.gifts.sortedWith(
                    compareBy<com.stardew.assistant.data.GiftVillager> { it.name !in snap.followed }
                        .then(byBirthday),
                )
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + LocalGlassInset.current), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                // c191 性能：稳定 key——村民名全局唯一（GameData 撞名时给 Mod 加后缀，
                // 源码 GameData.kt「重名的处理」注释明示）。点 ★ 后 followed 变化触发重排，
                // 带名 key 让既有行按身份复用而不是按位置错位重建。
                items(sorted.size, key = { sorted[it].name }) { i ->
                    val v = sorted[i]
                    val starred = v.name in snap.followed
                    val isrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(c.parchment)
                            .border(2.dp, c.woodDark)
                            .pressFeedback(isrc)
                            .clickable(interactionSource = isrc, indication = null) { target = v.name }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VillagerAvatar(v.name, 36)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // c201 B2：姓名单行截断。PixelText 默认 maxLines=Int.MAX_VALUE
                                //   且 overflow=Clip（既不换行也不省略）——Mod 村民的显示名可能很长，
                                //   不截断会把这一行顶高，整列节奏就乱了。
                                PixelText(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // v2.73：扩展包村民在名字后挂一枚来源标签。
                                //   标签取包自己声明的短名（[GameData.modTagOf]，这里显示「SVE」）而不是
                                //   泛泛的「扩展包」——用户是按 Mod 名认人的，说「扩展包」等于没说。
                                //   放名字右侧而不是副行：副行要给生日，让给标签会让生日看不见。
                                if (v.fromMod) {
                                    Spacer(Modifier.width(5.dp))
                                    Chip(
                                        GameData.modTagOf(v) ?: "扩展",
                                        border = c.gold,
                                    )
                                }
                            }
                            PixelText(
                                when {
                                    // c204：吉尔/冈瑟/马龙——写「不可送礼」太含糊，
                                    //   直说「不接受友谊礼物」用户才知道是游戏设定不是 App 缺数据。
                                    !v.acceptsGifts -> "不接受友谊礼物"
                                    v.birthdaySeason != null ->
                                        "生日 ${v.birthdaySeason} ${v.birthdayDay}"
                                    v.fromMod -> "扩展包 · 无生日"
                                    else -> "不可送礼"
                                },
                                color = c.inkDim,
                            )
                        }
                        Chip(
                            // v2.73：五段齐全的包（v2.2 起）显示两段计数；老包只有最爱时
                            //   仍然只显示最爱——硬显示「喜欢 0」像功能坏了。
                            when {
                                // c204：不收礼物的人没有「最爱 0 · 喜欢 0」这种计数
                                !v.acceptsGifts -> "不接受礼物"
                                !v.fromMod -> "最爱 ${v.loves.size} · 喜欢 ${v.likes.size}"
                                GameData.giftTiersComplete(v) -> "最爱 ${v.loves.size} · 喜欢 ${v.likes.size}"
                                else -> "最爱 ${v.loves.size}"
                            },
                        )
                        // v2.71 特别关心开关。放在行尾、行内最后一个可点元素——
                        // 它自己的 clickable 会先吃掉手势，不会穿透触发整行开详情。
                        Box(
                            Modifier
                                .padding(start = 8.dp)
                                .clickable {
                                    scope.launch { repo.toggleFollowed(v.name) }
                                }
                                // c201 B1：★ 的可点区从 ~22dp 提到 ~30dp。
                                //   原来 6dp 内边距 + 一个十来 dp 的字，手指要点中很难——
                                //   而「点了没反应」会被当成 App 坏了。
                                .padding(10.dp),
                        ) {
                            PixelText(
                                if (starred) "★" else "☆",
                                color = if (starred) c.gold else c.inkDim,
                            )
                        }
                    }
                }
                item {
                    // c223：inkDim 裸压屏幕底最低 1.09 → 上米黄纸条
                    ParchmentStrip {
                        PixelText("每周限送 2 件，生日当天可多送 1 件且效果 ×8", color = c.inkDim)
                    }
                }
            }
        }
    }
    val t = target
    // v2.40：本页是 keep-alive 常驻页签，而 DetailDrawer 是 ModalBottomSheet
    // （独立 Dialog 窗口）——外层 Box 的 alpha = 0 管不到它。不按可见性门控的话，
    // 从村民页跳去图鉴后，这张抽屉会**浮在图鉴上面**（今天/计算/进度页同理）。
    //
    // 只 gate 渲染、**不清 target**：返回锚点把用户送回本页时，
    // 同一个人的详情抽屉原样还在，这才叫「返回还在村民页」。
    if (t != null && visible) {
        DetailDrawer(
            visible = true,
            title = t,
            onDismiss = { target = null },
        ) {
            VillagerDetailBody(
                t,
                Modifier.fillMaxSize(),
                followed = t in snap.followed,
                onToggleFollow = { scope.launch { repo.toggleFollowed(t) } },
                // v2.73.1：传当季中文名，行程卡对季节后缀地点做当季标注。
                season = listOf("春", "夏", "秋", "冬").getOrNull(snap.season),
            )
        }
    }
}

/** 村民详情主体 —— 整屏页与底部抽屉共用；整体可滚动 */
@OptIn(ExperimentalLayoutApi::class)   // v2.73.10：红心事件节点用 FlowRow 排
@Composable
fun VillagerDetailBody(
    name: String,
    modifier: Modifier = Modifier,
    // v2.40：最爱格子可点 → 跳图鉴看这件物品（临时查看，返回回本页）。
    // 默认走图鉴统一的 resolveItemJump，与攻略正文物品名同一套解析口径；
    // 今日页的生日卡抽屉也用这个主体，同一个默认值两边行为一致。
    onOpenItem: (String) -> Unit = { resolveItemJump(it) },
    // v2.71 特别关心开关（null = 调用方没接线，不显示★）。列表行已有同款开关，
    // 详情头部再放一个是因为「点开详情 → 顺手标记」是更自然的动线，两处状态同源。
    followed: Boolean = false,
    onToggleFollow: (() -> Unit)? = null,
    // v2.73.1：当前存档季节（「春」…「冬」）。行程地点里的季节后缀（如「安迪农场(春)」）
    // 用它做当季标注。null = 调用方不传（今日页等复用处不接线也能编译，只是不高亮）。
    season: String? = null,
) {
    val v = GameData.gifts.firstOrNull { it.name == name } ?: return
    val c = LocalPixel.current
    // v2.94.0：原版 NPC 行程表（完整时段表）。扩展包村民没有这份数据（数据只覆盖原版 34 位）。
    val vanilla = com.stardew.assistant.data.NpcSchedules.byName(name)
    val events = GameData.heartEvents.firstOrNull { it.villager == name }?.events ?: emptyList()
    // v2.73.10：扩展包的红心事件链概要（Mod 包给的「2/4/6/8/10 心各在哪触发」）。
    // ⚠ 与上面的原版 `events` 是**两回事**：原版是逐条剧情（hearts/place/event/options），
    //   包里只给一段概要文字。两者并存，标清楚哪个是哪个。
    val modHeart = GameData.modHeartEventOf(v.srcId, v.name)
    val movie = GameData.codexGroups.firstOrNull { it.id == "movies" }?.items?.firstOrNull { it.name == name }
    val spouse = GameData.marriageSpouses.firstOrNull { it.first == name }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                PixelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VillagerAvatar(v.name, 64)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // c201 B2：同上，详情里的姓名也单行截断
                                PixelText(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // v2.73：来源标签移到名字右侧。出生日期/可结婚已在下面独占一行，
                                //   这里让位给标签，标签字数少（「SVE」）挤不掉什么。
                                if (v.fromMod) {
                                    Spacer(Modifier.width(6.dp))
                                    Chip(GameData.modTagOf(v) ?: "扩展", border = c.gold)
                                }
                            }
                            // v2.72：名字下面留空，生日/可结婚标签换到下面独占一行。
                            //   原来标签跟在名字右边同处一行，而这一行的右边是
                            //   「报错」+「☆特别关心」两个按钮——可结婚的人有两枚标签时
                            //   （原版 8 人 + v2.72 的 Mod 包 7 人）红章被按钮压掉半截，
                            //   实测截图里「可结婚」只剩一个「可」字。独占一行就没得挤了。
                            Spacer(Modifier.height(3.dp))
                        }
                        ReportButton(
                            "村民", v.name,
                            // c204：吉尔/冈瑟/马龙不接受友谊礼物——副标题别写「最爱：」加一个空串，
                            //   那看着像 App 没数据；直说「不接受友谊礼物」才是真相。
                            if (v.acceptsGifts) "最爱：${v.loves.joinToString("、")}"
                            else "不接受友谊礼物",
                        )
                        if (onToggleFollow != null) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .background(if (followed) c.gold else c.slot)
                                    .slotBevel(inset = 2.dp, width = 1.dp)
                                    .border(2.dp, c.slotEdge)
                                    .clickable { onToggleFollow() }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                PixelText(
                                    if (followed) "★ 已关心" else "☆ 特别关心",
                                    style = Px.caption,
                                    // c223：夜间浅金底白字 1.68 → 文字按底亮度切（GlassLadder 单源，
                                    //   与 c186 庆祝条同判据）；日间深金底白字保留
                                    color = if (followed) {
                                        if (com.stardew.assistant.ui.glass.GlassLadder.isLightSurface(c.gold)) c.woodDark
                                        else Color.White
                                    } else c.ink,
                                )
                            }
                        }
                    }
                    // v2.72：生日 / 可结婚 独占一行（理由见上面那段注释）。
                    // 可结婚有两个来源——原版看 marriage.json，扩展包只能看包里的
                    // marriageable 标记（那张表里没有它们），所以是「或」不是「和」。
                    if (v.birthdaySeason != null || spouse != null || v.marriageable == true) {
                        Row {
                            if (v.birthdaySeason != null) Chip("生日 ${v.birthdaySeason} ${v.birthdayDay}")
                            if (spouse != null || v.marriageable == true) {
                                Spacer(Modifier.width(6.dp))
                                Chip("可结婚", border = c.red, content = c.red)
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    if (v.acceptsGifts) {
                        PixelText("生日当天送礼效果 ×8 · 每周限送 2 件", color = c.inkDim)
                    } else {
                        // c204：吉尔/冈瑟/马龙没有好感度系统，「生日当天 ×8」对他们不成立，
                        //   照写就是**说了一句假话**。改成说清楚为什么不收。
                        PixelText(
                            v.giftNote ?: "1.6 起列入村民名册，但不接受友谊礼物，也没有好感度系统。",
                            color = c.inkDim,
                        )
                    }
                    // v2.72：扩展包才有的两行（地点 / 来源）。原版村民这两个字段为 null，不渲染。
                    if (v.location != null) {
                        PixelText("📍 ${v.location}", color = c.inkDim)
                    }
                    if (v.fromMod) {
                        PixelText(
                            "来自${GameData.modTagOf(v) ?: "扩展"}扩展包" +
                                (v.srcId?.let { "（游戏内 $it）" } ?: "") + " · 关掉扩展包即消失",
                            color = c.inkDim,
                        )
                    }
                }
            }
            // c204：送礼规划卡。**不接受友谊礼物**的村民（吉尔/冈瑟/马龙）不渲染这张卡——
            //   五段礼物全空，铺一张空网格 + 两行「没有标注的说明工具暂缺来源数据」，
            //   看着像 App 没数据；直说「不收」才是真相。
            if (v.acceptsGifts) {
                item {
                    // 送礼规划（v2.5）：每件最爱标获取途径（itemSources join）
                    PixelCard(title = "🎁 最爱 · 送礼规划") {
                        v.loves.chunked(4).forEachIndexed { ri, rowItems ->
                            if (ri > 0) Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                                // v2.40：格子可点了。onClick 传的是外层 onOpenItem，
                                // 调用方要换行为时只改一处（默认即图鉴解析）。
                                rowItems.forEach { itemName ->
                                    SlotBox(
                                        name = itemName,
                                        sub = giftSource(itemName),
                                        onClick = { onOpenItem(itemName) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        PixelText("格子下是获取途径；点格子看该物品的图鉴详情。", color = c.inkDim)
                        PixelText("未标注的格子表示暂缺该物品的来源数据。", color = c.inkDim)
                    }
                }
            } else {
                item {
                    PixelCard(title = "🚫 不接受友谊礼物") {
                        PixelText(
                            v.giftNote ?: "1.6 起列入村民名册，但不接受友谊礼物，也没有好感度系统。",
                        )
                        Spacer(Modifier.height(4.dp))
                        PixelText("所以没有最爱/喜欢/避雷清单——这不是 App 缺数据，是游戏里的设定。", color = c.inkDim)
                    }
                }
            }
            // v2.94.0：原版 NPC 行程表（完整时段）。
            //   放在「最爱规划」之后、扩展包概要之前——动线是「送什么 → 他今天在哪 → 别送什么」。
            // ⚠ 默认折叠：这个表按季/按变体有十几张，铺开会把详情页撑成长卷；
            //   而「送什么」才是用户进详情页最常做的事，行程是第二需求。
            if (vanilla != null) {
                item(key = "vanillaSchedule") {
                    var expanded by remember(name) { mutableStateOf(false) }
                    CollapsibleCard(
                        title = "🗺 行程（${vanilla.variants.count { !it.container }} 张表）",
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                        collapsedHint = "点按展开（按季切换查看逐条时段）",
                    ) {
                        com.stardew.assistant.ui.schedule.ScheduleBrowseCard(vanilla)
                    }
                }
            }
            // v2.73.1：行程（v2.3 扩展包新数据）。放在最爱规划之后、避雷之前——
            //   动线是「送什么 → 去哪找他 → 别送什么」。
            // ⚠ 这是**概要版**：包里只有 summary + 常去地点，没有按时段的表，
            //   所以文案必须写「概要」，别暗示能精确到小时（数据里没有的东西不能许诺）。
            if (v.scheduleSummary != null) {
                item {
                    PixelCard(title = "🗺 行程（概要）") {
                        PixelText(v.scheduleSummary)
                        if (v.scheduleLocations.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(v.scheduleLocations.size) { i ->
                                    // 地点条目可能带季节后缀（「安迪农场(春)」）。三种着色：
                                    //   ① 无后缀 → 全年常驻 → 金色边（常去）；
                                    //   ② 后缀匹配当前存档季 → 金色边（当季可去）；
                                    //   ③ 后缀不匹配当前季 → 暗色边（那个季节才去，现在别白跑）。
                                    // season 为 null（调用方没接线）时全亮——宁可不标注，不可标错。
                                    val m = SCHEDULE_SEASON_RE.find(v.scheduleLocations[i])
                                    val locSeason = m?.groupValues?.get(2)
                                    val onNow = locSeason == null || season == null || locSeason == season
                                    Chip(
                                        v.scheduleLocations[i],
                                        border = if (onNow) c.gold else c.inkDim,
                                        content = if (onNow) c.gold else c.inkDim,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            PixelText("金色 = 当前季节（${season ?: "未读取存档"}）可去或全年常驻；灰色 = 其它季节才去。", color = c.inkDim)
                        }
                        Spacer(Modifier.padding(2.dp))
                        // c226 瘦身：「mod 日程注释」是实现细节，玩家不关心数据来自代码注释。
                        PixelText("概要行程：按扩展包资料整理的大致去向，不是精确时段表。", color = c.inkDim)
                    }
                }
            }
            if (v.dislikes.isNotEmpty() || v.hates.isNotEmpty()) {
                item {
                    // 避雷（v2.5 新数据：30 位村民 dislikes/hates）
                    PixelCard(title = "⚠ 送礼避雷") {
                        if (v.hates.isNotEmpty()) {
                            PixelText("憎恶（效果 −80）：", color = c.red)
                            Spacer(Modifier.height(3.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(v.hates.size) { Chip(v.hates[it], border = c.red, content = c.red) }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        if (v.dislikes.isNotEmpty()) {
                            PixelText("讨厌（效果 −45）：", color = c.inkDim)
                            Spacer(Modifier.height(3.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(v.dislikes.size) { Chip(v.dislikes[it], border = c.inkDim, content = c.inkDim) }
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        // ⚠ 来源口径**分两路**，不能共用一句话：
                        //   原版村民这批数据 v2.118 起是官方 1.6.15 数据集
                        //   （Data/NPCGiftTastes）逐条解析出来的，要认领官方；
                        //   扩展包村民是包内 npcs.json 直接给的，套上「来自官方」就是**假出处**——
                        //   而出处假了，出了错用户照着官方排查会查不到点上。
                        PixelText(
                            if (v.fromMod) "数据来自 ${GameData.modTagOf(v) ?: "扩展包"}扩展包"
                            else "数据来自官方礼物偏好表。",
                            color = c.inkDim,
                        )
                    }
                }
            }
            // c204：不喜欢收礼物的村民不渲染这张空卡（理由同上）
            if (v.acceptsGifts) {
                item {
                    PixelCard(title = "👍 喜欢") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(v.likes.size) { Chip(v.likes[it]) }
                        }
                        Spacer(Modifier.padding(2.dp))
                        PixelText("「宝石与矿物」等类别可到图鉴查看成员清单", color = c.inkDim)
                    }
                }
            }
            // v2.73：**中立**段。放最后——它是五段里信息量最低的一段（送不吃亏也不加分），
            //   排在「最爱/喜欢」后面不抢注意力；类别条目（「所有鱼」等）在此才有解释的机会。
            if (v.neutrals.isNotEmpty()) {
                item {
                    PixelCard(title = "😐 中立（效果 +45）") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(v.neutrals.size) { Chip(v.neutrals[it]) }
                        }
                        Spacer(Modifier.padding(2.dp))
                        PixelText("送这些不加分也不减分；「所有鱼」等类别代表整类物品。", color = c.inkDim)
                    }
                }
            }
            if (events.isNotEmpty()) {
                item(key = "heartEvents") {
                    // v2.62 心事件默认折叠：内容是**剧情**，展开即剧透。
                    // ⚠ 刻意不设「行数少就不折」的阈值（CodexPage 属性表那种）：
                    //    实测 34 位村民里 19 位只有 1~2 条（12 位仅 1 条），
                    //    套用那条阈值会导致这 22 位的事件照样全部展开，等于没做。
                    //    两种折叠动机（省空间 / 防剧透）阈值本就不同，见 CollapsibleCard 的注释。
                    // ⚠ 折叠态按 name 记忆：换一位村民自动收起，否则看过 A 的展开态
                    //    会带到 B 身上，一进详情就是展开的。
                    var expanded by remember(name) { mutableStateOf(false) }
                    CollapsibleCard(
                        title = "💗 心事件（${events.size} 条）",
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                        collapsedHint = "点按展开（含剧情，可能剧透）",
                        accent = c.red,
                    ) {
                        events.forEachIndexed { ei, e ->
                            if (ei > 0) RowSep()
                            Row(Modifier.padding(vertical = 5.dp)) {
                                PixelText(e.hearts, color = c.red)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    PixelText("${e.place} · ${e.event}")
                                    if (e.options.isNotBlank() && e.options != "无选项") {
                                        PixelText("选项：${e.options}", color = c.inkDim)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (modHeart != null) {
                item(key = "modHeartEvents") {
                    // v2.73.10：扩展包红心事件链**概要**。
                    // ⚠ 不用 CollapsibleCard（原版那套是「防剧透」用的）：这里是概要文字，
                    //   不是剧情，折叠起来用户反而看不到「在哪触发」这个最有用的信息。
                    // ⚠ **不高亮「下一个」**：App 不读存档，没有玩家当前好感这个数。
                    //   把「不知道」标成「还没到」是误导——宁可只列节点，不假装知道进度。
                    //   （用户 2026-10-19 拍板：不接数据源，只列 2/4/6/8/10 节点。）
                    PixelCard(title = "💗 红心事件链（扩展）") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            modHeart.hearts.forEach { h ->
                                // 红色 = 红心事件节点，与原版心事件的红色一致
                                Chip("$h 心", border = c.red, content = c.red)
                            }
                        }
                        if (modHeart.triggers.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            PixelText("触发条件：${modHeart.triggers}")
                        }
                        if (GameData.modPostMarriage.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            PixelText("婚后：${GameData.modPostMarriage}", color = c.inkDim)
                        }
                        Spacer(Modifier.height(4.dp))
                        PixelText(
                            "节点为星露谷标准 2/4/6/8/10 心；具体剧情以游戏内为准。",
                            color = c.inkDim,
                        )
                    }
                }
            }
            if (movie != null && movie.rows.isNotEmpty()) {
                item {
                    PixelCard(title = "🎬 电影院") {
                        movie.rows.forEach { (k, value) -> KVRow(k, value) }
                    }
                }
            }
            if (spouse != null) {
                item {
                    PixelCard(title = "💍 婚后") {
                        PixelText(spouse.second.first)
                        Spacer(Modifier.padding(2.dp))
                        PixelText(spouse.second.second, color = c.inkDim)
                    }
                }
            }
            item { Spacer(Modifier.padding(2.dp)) }
        }
}
