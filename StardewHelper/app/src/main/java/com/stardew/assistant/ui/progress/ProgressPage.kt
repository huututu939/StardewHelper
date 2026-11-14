package com.stardew.assistant.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.GameData
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.data.ProgressStats
import com.stardew.assistant.data.bundleCheckKey
import com.stardew.assistant.data.bundleDoneCount
import com.stardew.assistant.data.bundleIsFull
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.codex.resolveItemJump
import com.stardew.assistant.ui.DetailDrawer
import com.stardew.assistant.ui.FramedIcon
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.nav.NavRequest
import com.stardew.assistant.ui.nav.navBus
import com.stardew.assistant.ui.PageBar
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelBar
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelCheckbox
import com.stardew.assistant.ui.PixelPips
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import androidx.compose.ui.unit.sp
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.UiIcon
import com.stardew.assistant.ui.slotBevel
import kotlinx.coroutines.launch
import com.stardew.assistant.ui.ParchmentStrip

/** 房间 → 卡头图标（assets/img/ui/） */
private fun roomIcon(room: String) = when {
    room.startsWith("工艺房") -> "room_craft"
    room.startsWith("储物间") -> "room_storage"
    room.startsWith("鱼缸") -> "room_fish"
    room.startsWith("锅炉房") -> "room_furnace"
    room.startsWith("布告栏") -> "room_bulletin"
    // v2.58 电影院：暂无专属 UI 图标，借用社区中心那张（它本来就是社区中心的后续建筑）
    room.startsWith("电影院") -> "community_center"
    else -> "community_center"
}

@Composable
fun ProgressPage(
    snap: PrefsRepo.Snapshot,
    repo: PrefsRepo,
    visible: Boolean,
    // v2.73.14：「🧩 扩展」入口从村民页挪到这里（总览段末尾那块空白）。
    onOpenMods: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val c = LocalPixel.current
    var hideDone by remember { mutableStateOf(false) }
    var section by remember { mutableIntStateOf(0) }
    var showProfiles by remember { mutableStateOf(false) }
    val openBundles = remember { mutableStateListOf<String>() }

    // v2.25：计数搬进 data/ProgressCalc.kt，供进度页与「进度成就卡」共用同一份口径
    // ⚠ **c200 A3：必须 remember**。calcProgress 会遍历 GameData.bundleRooms 的
    //   **每一个收集包的每一个条目**（ProgressCalc.kt:133-178）—— 裸调的话，
    //   进度页滚动一下、勾一个框，整份收集包就重算一遍。
    //   key 与今日页同源口径（TodayPage.kt:207）：checks 变了才重算，
    //   加上 codexViewVersion（Mod 包增删会换掉 GameData 的运行时视图）。
    val st = remember(snap.checks, GameData.codexViewVersion) {
        com.stardew.assistant.data.calcProgress(snap)
    }
    val bundleChecked = st.bundleChecked
    val museumDone = st.museumDone
    val achDone = st.achDone
    val walnutDone = st.walnutDone
    val totalItems = st.totalAll

    // c200 A4：收集包段的空态判断。
    // 「只看未完成」打开后，已勾完的包被过滤掉 —— 若**所有**包都被过滤光，
    // 屏幕上只剩一排空房间卡（每个房间一句「本房间已全部完成」），用户看不出
    // 「这是全勾完了」还是「App 坏了」。这里预先算好「每个包可见否」，
    // 房间级：一个包都看不见 ⇒ 整张房间卡不画；全局：一个包都看不见 ⇒ 画整段空态。
    // ⚠ 用 remember 固化：bundleIsFull 要遍历包内条目，全量算每次重组都做一遍就白付了。
    val bundleVisible = remember(snap.checks, GameData.codexViewVersion, hideDone) {
        GameData.bundleRooms.mapIndexed { ri, room ->
            room.bundles.mapIndexed { bi, b ->
                !hideDone || !bundleIsFull(b, ri, bi, snap.checks)
            }
        }
    }
    val anyBundleVisible = bundleVisible.any { it.any { v -> v } }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    com.stardew.assistant.ui.ScrollToTopEffect(com.stardew.assistant.Screen.Progress, listState)

    Column(Modifier.fillMaxSize()) {
        PageBar("进度") {
            // v2.26：分享收敛为「今日概览」与「图鉴条目」两处，进度页不再有分享入口
            Chip("总进度 ${bundleChecked + museumDone + achDone + walnutDone}/$totalItems")
            Spacer(Modifier.width(6.dp))
            Box(Modifier.clickable { showProfiles = true }) { Chip("👤 ${snap.activeProfile} ▾") }
        }

        // ── 分段切换 + 全局开关（固定，不随内容滚动）──
        Column(Modifier.padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(10.dp))
            PixelSegmented(
                options = listOf("总览", "收集包", "解锁"),
                selected = section,
                onSelect = { section = it },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelButton("只看未完成", onClick = { hideDone = !hideDone }, active = hideDone)
                Spacer(Modifier.width(8.dp))
                if (hideDone) {
                    // c223：inkDim 裸压屏幕底最低 1.09 → Row 里用行内米黄纸块
                    Box(
                        Modifier
                            .background(c.parchment)
                            .border(1.dp, c.slotEdge)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        PixelText("已隐藏勾完的条目", color = c.inkDim)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + LocalGlassInset.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // c200 A4：全部勾完 ⇒ 整段空态（庆祝），不再画一排空房间卡
            // （放在 when **外面**：when 的分支结构保持原样，不动它的括号配平）
            if (section == 1 && !anyBundleVisible) {
                item(key = "allDone") {
                    PixelCard(accent = c.green) {
                        PixelText("🎉 收集包全部勾完了", color = c.green)
                        Spacer(Modifier.height(4.dp))
                        PixelText(
                            "关掉上方的「只看未完成」就能看到完整清单。",
                            style = Px.caption, color = c.inkDim,
                        )
                    }
                }
            }
            when (section) {
                1 -> GameData.bundleRooms.forEachIndexed { ri, room ->
                    item {
                    // 房间内一个包都看不见 ⇒ 整张卡不画（空房间卡只会让人以为出错了）
                    if (!bundleVisible[ri].any { it }) return@item
                    val roomFull = room.bundles.withIndex().count { (bi, b) ->
                        bundleIsFull(b, ri, bi, snap.checks)
                    }
                    PixelCard(title = "${room.room}（$roomFull/${room.bundles.size} 包）", titleIcon = roomIcon(room.room)) {
                        // v2.58：解锁说明。电影院这类「修完社区中心才出现」的地点，
                        // 光列包不给前置条件，玩家会以为是整修社区中心的第 27 个包。
                        if (room.note.isNotBlank()) {
                            room.note.split('\n').filter { it.isNotBlank() }.forEach { line ->
                                PixelText(line, color = c.inkDim)
                                Spacer(Modifier.height(2.dp))
                            }
                            Spacer(Modifier.height(3.dp))
                        }
                        if (roomFull == room.bundles.size) {
                            PixelText("本房间已全部完成 🎉", color = c.green)
                            Spacer(Modifier.height(3.dp))
                        }
                        var shown = 0
                        room.bundles.forEachIndexed { bi, b ->
                            val names = b.items.map { it.name }
                            val done = bundleDoneCount(b, ri, bi, snap.checks)
                            // v2.27：认「选 N」（冒险家 4选2 / 蟹笼 10选5），不再一律要求全交
                            val full = bundleIsFull(b, ri, bi, snap.checks)
                            if (hideDone && full) return@forEachIndexed
                            if (shown > 0) RowSep()
                            shown++
                            // v2.100.0 / c182：展开/收起的键是**纯界面状态**（不落盘），
                            //   前缀与「勾选键」分开（勾选键的模板只有 ProgressCalc.kt 能拼）。
                            val key = "bo:$ri:$bi"
                            val open = key in openBundles
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (open) openBundles.remove(key) else openBundles.add(key)
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PixelText(if (open) "▼ " else "▶ ", color = c.inkDim)
                                if (full) PixelText("✔ ", color = c.green)
                                // c201 B2：包名单行截断。PixelText 默认 maxLines=Int.MAX_VALUE
                                //   且 overflow=Clip——长包名会换行把这一行顶高，整张卡节奏乱掉。
                                PixelText(
                                    b.name,
                                    modifier = Modifier.weight(1f),
                                    color = if (full) c.green else c.ink,
                                    strike = full,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                PixelPips(done, names.size)
                                Spacer(Modifier.width(6.dp))
                                if (b.choose > 0) {
                                    PixelText("任选${b.choose}", color = c.inkDim)
                                    Spacer(Modifier.width(4.dp))
                                }
                                PixelText("${done}/${names.size}", color = if (full) c.green else c.inkDim)
                            }
                            if (open) {
                                Column(Modifier.padding(start = 14.dp)) {
                                    var shownItems = 0
                                    b.items.forEach { ic ->
                                        // v2.100.0 / c182：勾选键改调 bundleCheckKey（键模板单源）。
                                        //   产出的键与原来内联拼的**逐字节相同** ⇒ 老用户已勾的条目照旧生效。
                                        val itemKey = bundleCheckKey(ri, bi, ic.name)
                                        if (hideDone && itemKey in snap.checks) return@forEach
                                        if (shownItems > 0) RowSep()
                                        shownItems++
                                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            // v2.96：点物品图标直接跳图鉴。
                                            //   复用 resolveItemJump（名字唯一→直达详情，重名→退化成搜索页），
                                            //   **不新建第二套跳转逻辑**——跳转口径全项目只有那一处。
                                            //   旁边补一个金色「›」当可点提示，与本页「去勾 ›」同一套说法。
                                            Box(Modifier.clickable { resolveItemJump(ic.name) }) {
                                                FramedIcon(ic.name, 26)
                                            }
                                            PixelText("›", style = Px.caption, color = c.gold)
                                            Spacer(Modifier.width(8.dp))
                                            Column(Modifier.weight(1f)) {
                                                PixelCheckbox(
                                                    itemKey in snap.checks,
                                                    "${ic.name}${if (ic.count > 1) " ×${ic.count}" else ""}",
                                                ) { scope.launch { repo.toggleCheck(itemKey) } }
                                                // v2.58：数据里直接写的获取途径（官方 wiki 逐项原文）。
                                                // 与上面 usagesOf 那条「📍 获取途径」不重复：
                                                // 那条是 forage/mineral/artifact 三表 join，
                                                // 这里兜住「果酒 / 上古水果 / 虚空鲑鱼」这类三表查不到的。
                                                if (ic.src.isNotBlank()) {
                                                    PixelText(
                                                        "　${ic.src}", color = c.inkDim, maxLines = 3,
                                                        style = Px.body.copy(fontSize = 10.sp, lineHeight = 15.sp),
                                                        modifier = Modifier.padding(start = 24.dp),
                                                    )
                                                }
                                                // 收集包对照：来源速查（v2.99.0 / c180 起由既有数据源派生：
                                                // 商店 / 加工 / 钓点 / 采集·矿物·古物 / 怪物掉落 / 养殖 / 作物 / 菜谱「获取」行）
                                                // v2.18.1 性能：usagesOf 是全表扫描（bundleRooms+村民+烹饪 join），
                                                // 旧实现未缓存——滚动时每个新进视口的条目都重算一遍，改 remember 固化
                                                val src = remember(ic.name) {
                                                    GameData.usagesOf(ic.name).firstOrNull { it.first == "📍 获取途径" }
                                                }
                                                src?.let { (_, s) ->
                                                    PixelText(
                                                        "　$s", color = c.inkDim, maxLines = 3,
                                                        style = Px.body.copy(fontSize = 10.sp, lineHeight = 15.sp),
                                                        modifier = Modifier.padding(start = 24.dp),
                                                    )
                                                }
                                                // v2.99.0 / c180：查不到来源就**明说查不到**——
                                                //   不拿「农场种植」这类泛泛话术充数（缺件溯源的第一条纪律）。
                                                if (src == null && ic.src.isBlank()) {
                                                    PixelText(
                                                        "　查不到来源（本 App 的数据里没有这一条，以游戏内为准）",
                                                        color = c.inkDim, maxLines = 3,
                                                        style = Px.body.copy(fontSize = 10.sp, lineHeight = 15.sp),
                                                        modifier = Modifier.padding(start = 24.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

                2 -> item {
                // ── 解锁进度卡（盘点 §5.2）：条件/花费/达成后 + 自动打勾判定 ──
                PixelCard(title = "🔓 解锁进度") {
                    // v2.25：解锁达成判定搬进 ui/progress/UnlockDone.kt，年历页复用同一份口径
                    val autoDone: (String) -> Boolean = { key -> unlockReached(key, st, snap) }
                    var shown = 0
                    GameData.unlockCards.forEach { card ->
                        val auto = autoDone(card.checkKey)
                        val done = auto || "unlock:${card.id}" in snap.checks
                        if (hideDone && done) return@forEach
                        if (shown > 0) RowSep()
                        shown++
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            PixelCheckbox(
                                checked = done,
                                text = if (auto) "${card.name}（已达成 ✔）" else card.name,
                            ) { scope.launch { repo.toggleCheck("unlock:${card.id}") } }
                        }
                        Column(Modifier.padding(start = 24.dp, bottom = 5.dp)) {
                            PixelText("前置：${card.req}", color = c.inkDim)
                            if (card.cost.isNotBlank()) PixelText("花费：${card.cost}", color = c.inkDim)
                            if (card.after.isNotBlank()) PixelText("达成后：${card.after}", color = c.inkDim)
                        }
                    }
                    if (shown == 0) PixelText("全部解锁达成 🎉", color = c.green)
                    Spacer(Modifier.height(5.dp))
                    // c226 瘦身：写死的「60 件」会随数据漂移说谎，去掉；三类自动打勾保留。
                    PixelText("收集包、博物馆藏品与浮标自动打勾；其余手动勾选记在当前存档。", color = c.inkDim)
                }
            }

                // 风险 1：section 取值域 0..2（本批不持久化它，正常路径只会是 0/1/2）。
                // 但若日后改成持久化，旧值 3~5 会越界——显式兜回总览，
                // 别让 when 静默什么都不画（那看着像「进度页空了」）。
                else -> {
                    item { OverviewCard(st) }
                    // v2.73.14：Mod 扩展包入口（从村民页挪来）。
                    //   放在总览段末尾：实测那块有约 1000px 空白（内容 y=1104 结束、
                    //   内容区到 y≈2100），不挤任何顶栏、不浪费已有布局。
                    //   ⚠ 只在**总览段**可见（另外两段内容本来就满，不缺位置）。
                    item { ModsEntryCard(snap, onOpenMods) }
                }
            }
        }
    }

    // v2.40：visible && showProfiles —— keep-alive 页签切走后抽屉是独立 Dialog 窗口，
    // 不门控就会浮在别的页签上面（showProfiles 状态不清，返回时抽屉原样还在）
    ProfileDrawer(visible = visible && showProfiles, snap = snap, repo = repo, onDismiss = { showProfiles = false })
}

/** 存档切换 / 管理抽屉 */
@Composable
private fun ProfileDrawer(visible: Boolean, snap: PrefsRepo.Snapshot, repo: PrefsRepo, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val c = LocalPixel.current
    var editing by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    DetailDrawer(visible = visible, title = "切换存档", onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PixelCard(title = "👤 存档") {
                snap.profiles.forEachIndexed { i, name ->
                    if (i > 0) RowSep()
                    val active = name == snap.activeProfile
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (editing == name) {
                            Box(
                                Modifier.weight(1f)
                                    .background(c.parchment2)
                                    .border(2.dp, c.slotEdge)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                BasicTextField(
                                    value = editName,
                                    onValueChange = { editName = it.take(12) },
                                    singleLine = true,
                                    textStyle = Px.body.copy(color = c.ink),
                                    cursorBrush = SolidColor(c.ink),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            PixelButton("改名", onClick = {
                                scope.launch { repo.renameProfile(name, editName); editing = null }
                            })
                        } else {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .background(if (active) c.green else c.slot)
                                    .slotBevel(inset = 2.dp, width = 1.dp)
                                    .border(2.dp, c.slotEdge),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(
                                Modifier.weight(1f).clickable {
                                    scope.launch { repo.switchProfile(name); onDismiss() }
                                },
                            ) {
                                PixelText(name, color = if (active) c.ink else c.inkDim)
                            }
                            if (confirmDelete == name) {
                                PixelButton("确认删除", onClick = {
                                    scope.launch { repo.deleteProfile(name); confirmDelete = null }
                                })
                            } else {
                                PixelButton("改名", onClick = { editing = name; editName = name })
                                if (!active && snap.profiles.size > 1) {
                                    Spacer(Modifier.width(6.dp))
                                    PixelButton("删除", onClick = { confirmDelete = name })
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (snap.profiles.size < PrefsRepo.MAX_PROFILES) {
                    PixelButton("＋ 新建存档", onClick = { scope.launch { repo.addProfile() } })
                }
                Spacer(Modifier.height(5.dp))
                PixelText("每个存档各自记录日期 / 职业 / 打勾进度，切换后全 app 跟着变；联机各记各的。", color = c.inkDim)
            }
        }
    }
}

/**
 * v2.73.14：Mod 扩展包入口卡（放在进度页总览段末尾）。
 *
 * ## 为什么在进度页
 * - 扩展包拆成 6 个分片后，**5/6 的内容都显示在图鉴**（作物/料理/鱼/商店/地点），
 *   而这一页顶栏的「总进度 X/279」**分母会随扩展包变化**（装了 SVE 鱼 → 浮标数变；
 *   装了作物/料理 → 图鉴条目变）。数字不对的那一页，就是装包能解决的那一页。
 * - 位置不挤：实测总览段内容 y=1104 就结束，内容区到 y≈2100，中间约 1000px 空白。
 * - 独立一张卡而不塞进「收集总览」：那张卡每行都是「图标 + 进度条 + 计数」，
 *   扩展包没有进度条，混进去会破坏节奏。
 */
@Composable
private fun ModsEntryCard(
    snap: PrefsRepo.Snapshot,
    onOpen: () -> Unit,
) {
    val c = LocalPixel.current
    val installed = com.stardew.assistant.data.GameData.modPacks.size
    val enabled = snap.modEnabled.size
    PixelCard(title = "🧩 Mod 扩展包") {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelText("已装 $installed 个 · 启用 $enabled 个")
            Spacer(Modifier.weight(1f))
            PixelText("打开 ›", color = c.gold)
        }
        Spacer(Modifier.height(4.dp))
        PixelText(
            "装上后图鉴会多出扩展包的村民、作物、料理、鱼、商店与地点；" +
                "本页「总进度」的分母也会跟着变。",
            color = c.inkDim,
        )
    }
}

/**
 * v2.32：总览仪表卡（section 0）。
 *
 * 博物馆 / 成就 / 金核桃的勾选入口已统一收到图鉴，这里只留「看」的聚合——
 * 用户拍板「新勾选不计入总进度」，所以四条进度条与 279 分母口径一字不动。
 * 每行尾部补一条「去勾 ›」通路：砍掉入口却不说去哪勾，等于把功能藏没了。
 */
@Composable
private fun OverviewCard(st: ProgressStats) {
    val c = LocalPixel.current
    PixelCard(title = "收集总览") {
        listOf(
            Triple("community_center", "社区中心包", Triple(st.fullBundles, st.totalBundles, "包")),
            Triple("museum", "博物馆藏品", Triple(st.museumDone, st.museumTotal, "件")),
            Triple("trophy", "成就", Triple(st.achDone, st.achTotal, "个")),
            Triple("walnut", "金核桃兑换", Triple(st.walnutDone, st.walnutTotal, "项")),
            // v2.41 浣熊商店。与鹦鹉兑换同形态（交材料换东西），但**只单开一行看**，
            // 不进 totalDone/totalAll —— 用户拍板过「新勾选不计入总进度」。
            Triple("raccoon", "浣熊兑换", Triple(st.raccoonDone, st.raccoonTotal, "项")),
            // v2.58 电影院。同款口径：它是社区中心修好之后才出现的独立建筑，
            // 不是整修前置的 26 包之一，所以单开一行、不进社区中心包与总进度分母。
            Triple("community_center", "后续房间", Triple(st.laterDone, st.laterTotal, "包")),
        ).forEachIndexed { i, (icon, label, row) ->
            val (done, total, unit) = row
            if (i > 0) RowSep()
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                UiIcon(icon, 22.dp)
                Spacer(Modifier.width(9.dp))
                PixelText(label, modifier = Modifier.width(88.dp))
                PixelBar(done, total, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                PixelText("$done/$total $unit", color = c.inkDim)
                // 社区中心包的勾选就在本页「收集包」段，不用跳；后三行跳图鉴
                when (label) {
                    "博物馆藏品" -> GoCheckLink("museumMinerals")
                    "成就" -> GoCheckLink("achievements")
                    "金核桃兑换" -> GoCheckLink("parrotShop")
                    "浣熊兑换" -> GoCheckLink("raccoonShop")
                }
            }
        }
    }
}

/**
 * v2.32：总览行尾的「去勾 ›」。
 *
 * 进度页的博物馆 / 成就 / 金核桃三段勾选入口已统一收到图鉴，但砍掉入口后用户会
 * 找不到在哪勾——所以每行补一条直达图鉴对应分类的通路。跳转走 [navBus]（v2.31 全局
 * 导航总线），清栈 + 切页签只有 AppRoot 有权做。
 *
 * 博物馆只落「博物馆·矿物」：NavRequest.Codex 只带单个 groupId，古物在相邻分类，
 * 用户手动切一下即可，不值得为这个给 NavRequest 加字段。
 */
@Composable
private fun GoCheckLink(targetGroup: String) {
    val c = LocalPixel.current
    Spacer(Modifier.width(6.dp))
    Box(Modifier.clickable { navBus.value = NavRequest.Codex(groupId = targetGroup) }) {
        PixelText("去勾 ›", style = Px.caption, color = c.gold)
    }
}