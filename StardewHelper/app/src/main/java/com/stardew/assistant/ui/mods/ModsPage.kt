package com.stardew.assistant.ui.mods

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stardew.assistant.BuildConfig
import com.stardew.assistant.data.GameData
import com.stardew.assistant.data.ModRepo
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.ui.BackBar
import com.stardew.assistant.ui.Chip
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelConfirmDialog
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.glass.LocalGlassInset
import com.stardew.assistant.ui.slotBevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 清单的三种形态：加载中 / 有了 / 取不到。取不到时把原因原样显示，不静默变空列表。 */
private sealed class IndexUi {
    object Loading : IndexUi()
    data class Ok(val packs: List<ModRepo.PackMeta>) : IndexUi()
    data class Err(val why: String) : IndexUi()
}

/**
 * v2.72「Mod 扩展包」页（第一版：下载 / 启用 / 停用 / 删除，不写回）。
 *
 * ## 为什么单独一页而不是塞进设置
 * 设置页是「App 本身怎么用」，这里是「往 App 里加内容」——用户想装东西时
 * 在设置里翻三层找不到它，就会去 QQ 群问「怎么装 SVE 的人物」。故挂在**村民页**顶栏。
 *
 * ## 状态只有一份
 * 已装状态以**盘上的目录**为准（[ModRepo.refreshInstalled]），
 * 偏好里的 `mod_installed` 只是它的镜像：进页面/装完/删完都重扫一次，
 * 避免「目录被系统清掉了但开关还开着」→ 一堆点不开的幽灵村民。
 *
 * ## 网络
 * 本页所有网络动作都是**点一下才发一次**：拉清单、下载包。
 * 装好之后数据全在本地，离线可用；关掉开关只是不再合并，不删文件。
 */
@Composable
fun ModsPage(
    snap: PrefsRepo.Snapshot,
    repo: PrefsRepo,
    onBack: () -> Unit,
) {
    val c = LocalPixel.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(ModRepo.installed()) }
    var indexUi by remember { mutableStateOf<IndexUi>(IndexUi.Loading) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var urlOpen by remember { mutableStateOf(false) }
    // c201 B5：待确认删除的包（null = 没在等确认）。删除不可撤销，要先问一句。
    var pendingRemove by remember { mutableStateOf<ModRepo.Installed?>(null) }
    var urlDraft by remember(snap.modIndexUrl) { mutableStateOf(snap.modIndexUrl) }

    val loadIndex: (String) -> Unit = { url ->
        scope.launch {
            indexUi = IndexUi.Loading
            indexUi = when (val r = withContext(Dispatchers.IO) { ModRepo.fetchIndex(url) }) {
                is ModRepo.Fetch.Ok -> IndexUi.Ok(r.packs)
                is ModRepo.Fetch.Failed -> IndexUi.Err(r.why)
            }
        }
    }

    val download: (ModRepo.PackMeta) -> Unit = { pack ->
        scope.launch {
            busyId = pack.modId
            progress = 0
            notice = null
            val r = withContext(Dispatchers.IO) {
                ModRepo.install(ctx, pack) { pct -> progress = pct }
            }
            // 先刷盘再落偏好：偏好记的是「盘上有什么」，顺序反了就等于记了个还不存在的目录。
            installed = ModRepo.refreshInstalled(ctx)
            repo.setModInstalled(installed.map { it.modId }.toSet())
            when (r) {
                is ModRepo.Install.Ok -> {
                    val enabled = snap.modEnabled + r.pack.modId
                    repo.setModEnabled(enabled)
                    // ⚠ **必须无条件主动重算一次合并**，不能只靠偏好流。
                    // 病根（2026-10-19 实测）：更新同一个包（2.2 → 2.3）时 enabled 与
                    // installed 两个集合**前后完全相同**（都是 {sve}），DataStore 对相同值
                    // 不发新快照 → MainActivity 里那次 applyEnabled 不会被调用 →
                    // GameData.gifts 里还是**旧包解析的对象**（新包加的字段全是空）。
                    // 表现为「明明更新了 2.3、盘上 schedules.json 也在，详情页就是没有行程卡」，
                    // 而且重启 App 才好——用户不该为这种事重启。
                    // toggle/remove 那两条路径的 enabled 集合一定变，偏好流会触发，不在此列。
                    ModRepo.applyEnabled(enabled)
                    // v2.73.13：提示语按包里**有没有 NPC**分两种说法。
                    //   分片包（商店/作物/鱼…）本来就没有 NPC，说「0 个 NPC」像装坏了。
                    notice = if (r.pack.npcs.isNotEmpty()) {
                        "已装 ${r.pack.name}：${r.pack.npcs.size} 个 NPC，其中可结婚 ${r.pack.marriageableCount} 个"
                    } else {
                        "已装 ${r.pack.name}"
                    }
                }
                is ModRepo.Install.Failed -> notice = r.why
            }
            busyId = null
            progress = 0
        }
    }

    val toggle: (ModRepo.Installed) -> Unit = { inst ->
        scope.launch {
            val on = inst.modId !in snap.modEnabled
            repo.setModEnabled(if (on) snap.modEnabled + inst.modId else snap.modEnabled - inst.modId)
            notice = if (on) "已启用 ${inst.name}" else "已停用 ${inst.name}（文件还在，下次启用不用重下）"
        }
    }

    val remove: (ModRepo.Installed) -> Unit = { inst ->
        scope.launch {
            withContext(Dispatchers.IO) { ModRepo.uninstall(ctx, inst.modId) }
            installed = ModRepo.refreshInstalled(ctx)
            repo.setModInstalled(installed.map { it.modId }.toSet())
            val enabled = snap.modEnabled - inst.modId
            repo.setModEnabled(enabled)
            // 与 download 同款保险：删一个**未启用**的包时 enabled 集合前后相同，
            //   偏好流不发快照 → GameData 还留着已删目录里的数据。删都删了还在读，是幽灵。
            ModRepo.applyEnabled(enabled)
            notice = "已删除 ${inst.name}"
        }
    }

    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) { ModRepo.refreshInstalled(ctx) }
        repo.setModInstalled(installed.map { it.modId }.toSet())
        loadIndex(snap.modIndexUrl)
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("🧩 Mod 扩展", onBack) {
            PixelButton(if (urlOpen) "收起地址" else "仓库地址", onClick = { urlOpen = !urlOpen }, active = urlOpen)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp, top = 10.dp, end = 12.dp,
                bottom = 12.dp + LocalGlassInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                PixelCard(title = "📦 扩展包") {
                    val onCount = snap.modEnabled.size
                    PixelText(
                        "已装 ${installed.size} 个 · 启用 $onCount 个 · 村民页多出 ${GameData.modVillagerCount} 位",
                        color = c.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    PixelText(
                        "装好的数据存在手机里，之后离线也能看；关掉开关只是不再显示，不会重新下载。" +
                            "本页点「刷新清单」或「下载」时才会联网一次。",
                        style = Px.caption, color = c.inkDim,
                    )
                }
            }

            if (urlOpen) {
                item {
                    PixelCard(title = "🌐 仓库地址") {
                        PixelText("填 index.json 地址可挂多个包；填以 .zip 结尾的直链则只装那一个。", style = Px.caption, color = c.inkDim)
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(c.slot)
                                .border(2.dp, c.slotEdge)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            BasicTextField(
                                value = urlDraft,
                                onValueChange = { urlDraft = it },
                                singleLine = true,
                                textStyle = Px.caption.copy(color = c.ink),
                                cursorBrush = SolidColor(c.ink),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        Row {
                            PixelButton("保存并刷新", onClick = {
                                scope.launch { repo.setModIndexUrl(urlDraft) }
                                loadIndex(urlDraft)
                                urlOpen = false
                            })
                            Spacer(Modifier.width(7.dp))
                            PixelButton("还原默认", onClick = {
                                urlDraft = ModRepo.DEFAULT_INDEX_URL
                                scope.launch { repo.setModIndexUrl(ModRepo.DEFAULT_INDEX_URL) }
                                loadIndex(ModRepo.DEFAULT_INDEX_URL)
                                urlOpen = false
                            })
                        }
                        Spacer(Modifier.height(6.dp))
                        PixelText("当前：${snap.modIndexUrl}", style = Px.caption, color = c.inkDim)
                    }
                }
            }

            item {
                PixelCard(title = "🗂 已安装") {
                    if (installed.isEmpty()) {
                        PixelText("还没装任何扩展包。下面「可安装」里点一下就行。", color = c.inkDim)
                    }
                    installed.forEachIndexed { i, inst ->
                        if (i > 0) {
                            Spacer(Modifier.height(8.dp))
                            RowSep()
                            Spacer(Modifier.height(8.dp))
                        }
                        val on = inst.modId in snap.modEnabled
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelText(inst.name, color = c.ink)
                            Spacer(Modifier.width(6.dp))
                            // v2.73：**两个号分开印，且都写清是哪个**。
                            //   只印一个 "v1.15.11" 会让人以为扩展包停在一月——那是上游 Mod 的号；
                            //   而「有没有新版」比的是包版本 2.2。印混了的直接后果是
                            //   用户看到「已是最新」却以为版本号很小，怀疑没更新成功。
                            PixelText(
                                "包 ${inst.pkgVersion.ifBlank { "—" }}",
                                style = Px.caption,
                                color = c.gold,
                            )
                            Spacer(Modifier.width(5.dp))
                            PixelText(
                                "含 Mod v${inst.version}",
                                style = Px.caption,
                                color = c.inkDim,
                            )
                            Spacer(Modifier.weight(1f))
                            if (on) {
                                Box(
                                    Modifier
                                        .background(c.green)
                                        .slotBevel(inset = 2.dp, width = 1.dp)
                                        .border(2.dp, c.slotEdge)
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                ) { PixelText("已启用", style = Px.caption, color = c.cream) }
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        PixelText(
                            "${inst.author.ifBlank { "未署名" }} · 更新 ${inst.updatedAt.ifBlank { "—" }}",
                            style = Px.caption, color = c.inkDim,
                        )
                        Spacer(Modifier.height(5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            // ⚠ v2.73.13：NPC 相关的 chip **只在这个包真有 NPC 时**才显示。
                            //   扩展包拆成分片后，「商店包」「作物包」压根没有 NPC ——
                            //   显示「NPC 0」「礼物 0/5 段」并提示「这个包只给了 0 段礼物数据」
                            //   会让用户以为装坏了（商店包根本没礼物这回事）。
                            if (inst.npcs.isNotEmpty()) {
                                Chip("NPC ${inst.npcs.size}")
                                Chip("可结婚 ${inst.marriageableCount}")
                                if (inst.hiddenCount > 0) Chip("隐藏 ${inst.hiddenCount}", content = c.inkDim)
                                Chip("头像 ${inst.portraitCount}")
                                // v2.73：礼物段数。v2.0 的包只有「最爱」一段，写成「礼物 1/5 段」
                                //   能让用户立刻知道自己该更新扩展包，而不是以为 App 显示不全。
                                Chip(
                                    "礼物 ${inst.giftTierCount}/5 段",
                                    content = if (inst.giftTierCount >= 5) c.green else c.inkDim,
                                )
                            } else {
                                // 没有 NPC 的分片：改说这个包**管什么**，别用 NPC 计数
                                Chip("无村民数据", content = c.inkDim)
                            }
                        }
                        if (inst.npcs.isNotEmpty() && inst.giftTierCount < 5) {
                            Spacer(Modifier.height(4.dp))
                            PixelText(
                                "这个包只给了 ${inst.giftTierCount} 段礼物数据，其余段留空；" +
                                    "更新扩展包可拿到完整五段。",
                                style = Px.caption, color = c.inkDim,
                            )
                        }
                        if (inst.hiddenCount > 0) {
                            Spacer(Modifier.height(4.dp))
                            PixelText(
                                "另有 ${inst.hiddenCount} 位隐藏角色（后期解锁或小型生物），不单列成村民。",
                                style = Px.caption, color = c.inkDim,
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        Row {
                            PixelButton(if (on) "停用" else "启用", { toggle(inst) }, active = on)
                            Spacer(Modifier.width(7.dp))
                            PixelButton("删除", { pendingRemove = inst })
                        }
                    }
                }
            }

            item {
                PixelCard(title = "🛒 可安装") {
                    when (val ui = indexUi) {
                        is IndexUi.Loading -> PixelText("正在取清单…", color = c.inkDim)
                        is IndexUi.Err -> Column {
                            PixelText(ui.why, color = c.inkDim)
                            Spacer(Modifier.height(7.dp))
                            Row {
                                PixelButton("重试", onClick = { loadIndex(snap.modIndexUrl) })
                                Spacer(Modifier.width(7.dp))
                                PixelButton("改地址", onClick = { urlOpen = true })
                            }
                        }
                        is IndexUi.Ok -> Column {
                            PixelButton("刷新清单", onClick = { loadIndex(snap.modIndexUrl) })
                            Spacer(Modifier.height(6.dp))
                            ui.packs.forEachIndexed { i, pack ->
                                if (i > 0) {
                                    Spacer(Modifier.height(9.dp))
                                    RowSep()
                                    Spacer(Modifier.height(9.dp))
                                }
                                val inst = installed.firstOrNull { it.modId == pack.modId }
                                val busy = busyId == pack.modId
                                val newer = inst != null && inst.pkgVersion.isNotEmpty() &&
                                    pack.version.isNotEmpty() &&
                                    ModRepo.compareVersion(pack.version, inst.pkgVersion) > 0
                                val tooNew = pack.minAppVersion.isNotEmpty() &&
                                    ModRepo.compareVersion(BuildConfig.VERSION_NAME, pack.minAppVersion) < 0
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PixelText(pack.name)
                                    Spacer(Modifier.width(6.dp))
                                    // v2.73：这里的 version 是**扩展包版本**（= manifest.packageVersion），
                                    //   措辞上写「包」而不是裸 "v"，与上面已装区的两个号区分开。
                                    if (pack.version.isNotEmpty()) {
                                        PixelText("包 ${pack.version}", style = Px.caption, color = c.gold)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if (newer) Chip("有新版本", border = c.gold)
                                }
                                if (pack.author.isNotBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    PixelText(
                                        "${pack.author}${if (pack.updatedAt.isNotBlank()) " · ${pack.updatedAt}" else ""}" +
                                            (inst?.let { " · 已装包 ${it.pkgVersion.ifBlank { "—" }}（含 Mod v${it.version}）" } ?: ""),
                                        style = Px.caption, color = c.inkDim,
                                    )
                                }
                                if (pack.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    PixelText(pack.description, color = c.ink)
                                }
                                if (tooNew) {
                                    Spacer(Modifier.height(4.dp))
                                    PixelText(
                                        "需要 App ${pack.minAppVersion} 以上，当前 ${BuildConfig.VERSION_NAME}，先更新 App",
                                        style = Px.caption, color = c.red,
                                    )
                                }
                                Spacer(Modifier.height(7.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val label = when {
                                        busy -> "下载中 $progress%"
                                        inst == null -> "下载并启用"
                                        newer -> "更新到包 ${pack.version}"
                                        else -> "重新下载"
                                    }
                                    PixelButton(label, { download(pack) }, active = busy)
                                    Spacer(Modifier.width(8.dp))
                                    if (busy) {
                                        PixelText("首次下载约几百 KB，很快", style = Px.caption, color = c.inkDim)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    PixelText(
                        "包里的内容由包作者提供，App 只读取不修改；" +
                            "头像素材版权归原作者，署名见包内 manifest。",
                        style = Px.caption, color = c.inkDim,
                    )
                }
            }

            val note = notice
            if (note != null) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(c.cream)
                            .border(2.dp, c.slotEdge)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) { PixelText(note, color = Color(0xFF3A2A18)) }
                }
            }
        }

        // c202：删除扩展包要**二次确认**。
        // 删除是不可撤销的（包文件从盘上移除，要重新下载才能拿回来），
        // 原来点一下就删 —— 手滑一次就没了，用户还以为是 App 抽风。
        // c202 改用**居中确认框**而不是底部抽屉：确认只有两行字，
        // 弹一个占 78% 屏高的抽屉太占地方（用户 2026-11-01 反馈）。
        val toRemove = pendingRemove
        PixelConfirmDialog(
            visible = toRemove != null,
            title = "删除扩展包",
            message = "确定要删除「${toRemove?.name ?: ""}」吗？\n" +
                "包里的内容会一起删掉，下次要用得重新下载。",
            onConfirm = {
                val inst = pendingRemove
                pendingRemove = null
                if (inst != null) remove(inst)
            },
            onDismiss = { pendingRemove = null },
            confirmText = "确定删除",
        )
    }
}
