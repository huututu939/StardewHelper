package com.stardew.assistant.ui.settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stardew.assistant.BuildConfig
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.data.UpdateRepo
import com.stardew.assistant.ui.CollapsibleCard
import com.stardew.assistant.ui.REPO_URL
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.UpdateNotes
import com.stardew.assistant.ui.slotBevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
/**
 * c230：设置页「其他」栏的卡片列表 + 版本号长按预览的注入数据（从 SettingsPage.kt
 * 原样搬来）。唯一机械差异：留在主文件的状态以 MutableState 下传，体内改 `.value`；
 * 文案与结构一字未动。
 */
fun LazyListScope.settingsAboutTab(
    snap: PrefsRepo.Snapshot,
    repo: PrefsRepo,
    scope: kotlinx.coroutines.CoroutineScope,
    c: com.stardew.assistant.ui.PixelColors,
    ctx: android.content.Context,
    checkingUpdate: MutableState<Boolean>,
    updateState: MutableState<UpdateRepo.Check?>,
    showLicense: MutableState<Boolean>,
) {
            item {
                // 桌面小组件设置：显示内容多选（长按桌面组件也可调整大小）
                // 勾选状态用 Compose state 记住（SharedPreferences 写入不触发重组，直接读会不重绘）
                val widgetShows = com.stardew.assistant.widget.WidgetPrefs.CONTENT
                    .map { (key, _) -> key to remember { mutableStateOf(com.stardew.assistant.widget.WidgetPrefs.isShown(ctx, key)) } }
                PixelCard(title = "🧩 桌面小组件") {
                    com.stardew.assistant.widget.WidgetPrefs.CONTENT.forEach { (key, label) ->
                        val state = widgetShows.first { it.first == key }.second
                        com.stardew.assistant.ui.PixelCheckbox(
                            checked = state.value,
                            text = label,
                            onChange = { v ->
                                state.value = v
                                com.stardew.assistant.widget.WidgetPrefs.setShow(ctx, key, v)
                                com.stardew.assistant.widget.WidgetUpdater.refresh(ctx)
                            },
                        )
                    }
                    Spacer(Modifier.padding(2.dp))
                    // c226 瘦身：三种规格添加小组件时系统选择器里看得一清二楚，
                    // 首启引导（Intro.kt）里也有一份；只留勾选语义与直达提示。
                    PixelText("勾选控制小组件显示哪些内容；点组件可直达对应页面。", color = c.inkDim)
                }
            }
            item {
                // v2.55.0 在线更新：全 App 唯一联网点，只在用户点按钮时发一个 GET（UpdateRepo.kt）
                PixelCard(title = "🔄 在线更新") {
                    // v2.61 进页静默查：用户主动进了设置页 = 已触达，这里查一次不算打扰。
                    // 与启动那次共用 UpdateRepo.shouldCheck（进程级去重），不会重复发请求
                    // ——同一次进 App 里启动已查过，进本页就不再查。
                    // 失败**不提示**（用户没主动点，不该因断网被教育）；只有手动点才显示 why。
                    LaunchedEffect(snap.updatePromptEnabled) {
                        if (!com.stardew.assistant.data.UpdateRepo.shouldCheck(
                                snap.updatePromptEnabled)) return@LaunchedEffect
                        val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            com.stardew.assistant.data.UpdateRepo.check(BuildConfig.VERSION_CODE)
                        }
                        repo.markUpdateChecked(System.currentTimeMillis())
                        if (r is com.stardew.assistant.data.UpdateRepo.Check.NewVersion) {
                                            updateState.value = r
                        }
                    }
                    // 「更新提示」开关（v2.61）：关掉后连每次开 App 的那次检查都不做，App 回到纯离线。
                    // 给介意联网的人一条明确的退路，否则横幅就是强推。
                    PixelSegmented(
                        options = listOf("开", "关"),
                        selected = if (snap.updatePromptEnabled) 0 else 1,
                        onSelect = { i -> scope.launch { repo.setUpdatePrompt(i == 0) } },
                    )
                    Spacer(Modifier.height(6.dp))
                    PixelButton(
                        text = if (checkingUpdate.value) "检查中…" else "立即检查更新",
                        onClick = {
                                            checkingUpdate.value = true
                            scope.launch {
                                // 手动检查**绕过节流**：用户明确要求了就该立刻给结果
                                updateState.value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    com.stardew.assistant.data.UpdateRepo
                                        .check(BuildConfig.VERSION_CODE)
                                }
                                repo.markUpdateChecked(System.currentTimeMillis())
                                                checkingUpdate.value = false
                            }
                        },
                    )
                    when (val st = updateState.value) {
                        is com.stardew.assistant.data.UpdateRepo.Check.UpToDate ->
                            PixelText("已是最新版本 v${BuildConfig.VERSION_NAME}", color = c.green)
                        is com.stardew.assistant.data.UpdateRepo.Check.Failed ->
                            PixelText(st.why, color = c.inkDim)
                        is com.stardew.assistant.data.UpdateRepo.Check.NewVersion -> {
                            PixelText(
                                "发现新版本 v${st.latest.versionName}" +
                                    (if (st.latest.date.isNotEmpty()) "（${st.latest.date}）" else ""),
                                color = c.gold,
                            )
                            // v2.61 起 notes 分层渲染（分类头金色、条目缩进），不再一坨平铺
                            UpdateNotes(st.latest.notes)
                            Spacer(Modifier.padding(2.dp))
                            PixelButton(text = "获取更新（浏览器下载）", active = true, onClick = {
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(st.latest.apkUrl),
                                        ),
                                    )
                                }.onFailure {
                                    android.widget.Toast.makeText(
                                        ctx, "没有可用的浏览器应用", android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            })
                        }
                        null -> Unit
                    }
                    Spacer(Modifier.padding(2.dp))
                    // c226 瘦身：联网与隐私口径以「关于」页为单源（那边更全、还回指本节），
                    // 这里原来 117 字与它逐句重复；只留本节独有的信息。
                    PixelText("下载后覆盖安装，存档不丢。", color = c.inkDim)
                }
            }
            // ⚠ 2026-11-10：这里原来是「🏆 致谢名单」卡（查看致谢名单 / 💖 赞助名单
            //   两个按钮）。项目开源后**整卡删除** ——
            //   名单里的昵称是**别人的** ID：源码一公开，任何人都能看到这些名字，
            //   当初在群里致谢是一回事，把人写进公开仓库是另一回事。
            //   （名单本身已从代码里删掉，见 SettingsInfoDrawers.kt 的注释。）
            item {
                PixelCard(title = "ℹ 关于") {
                    // v2.17 D10：版本号单源化，发版只改 gradle 一处
                    // v2.69.1：版本号这一行**长按 = 预览更新提示**（再长按一次清除）。
                    //   为什么要有这个：远端 version.json 比本机旧是**常态**（本机总是领先于
                    //   最后一次发布），于是 Check 永远是 UpToDate、pending 永远不写，横幅与
                    //   启动弹窗**一次都不会出现**——「更新提示到底长什么样」根本没法验。
                    //   要真验只能发一版到 gitee，但那会让内测群 25 个人一起收到更新提示，
                    //   而且每个版本号只能测一次（点过稍后/✕ 那一版就被你关掉了）。
                    //   这里改成注入假 pending：不联网、不发布、不影响真实更新逻辑、可反复测。
                    //   ⚠ 注入时必须**同时**清 updateSeenVersion 与 updateDialogVersion：
                    //   只写 pending 的话，你之前划掉过的那一版（若有）版本号更高，
                    //   MainActivity 里的「pending.versionCode > updateSeenVersion」就挡掉了横幅。
                    PixelText(
                        "星露小助手 v${BuildConfig.VERSION_NAME}",
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onLongPress = {
                                scope.launch {
                                    if (snap.updatePendingJson.contains(PREVIEW_MARK)) {
                                        repo.setUpdatePending("")
                                        repo.markUpdateSeen(0)
                                        repo.markUpdateDialogShown(0)
                                        android.widget.Toast.makeText(
                                            ctx, "已清除更新提示预览", android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        repo.markUpdateSeen(0)
                                        repo.markUpdateDialogShown(0)
                                        repo.setUpdatePending(
                                            com.stardew.assistant.data.UpdateRepo
                                                .encodeLatest(previewLatest()),
                                        )
                                        android.widget.Toast.makeText(
                                            ctx, "已注入预览，去今日页看横幅与弹窗（再长按这里清除）",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            })
                        },
                    )
                    // v2.55.0：加了 INTERNET 后旧的「零权限」承诺不再属实，口径同步改诚实
                    // v2.61：加了每日静默检查后，「仅按钮才联网」同样不再属实，再次改诚实。
                    // v2.73.3：检查频率改为每次打开 App 一次，「每天最多一次」同样过期。
                    // 三次都是同一个原则：承诺跟着实现走，实现变了承诺就得跟着变，
                    // 否则「诚实口径」只是当初写顺手了而已。
                    PixelText(
                        // v2.67.0：局域网接收是**第二个**网络行为，所以这里不再说「完全离线」。
                        //   它只在用户手动开窗口时监听同网段、收一个包，不往外发任何东西——
                        //   但把它算进「完全离线」就是撒谎了。
                        // v2.68.0：下载电脑脚本是**第三个**，同样要点一下才发生，也照样得写出来。
                        // v2.72.0：装 Mod 扩展包是**第四个**——在「🧩 Mod 扩展」页点下载/刷新清单
                        //   才联网，一次一页，装完存本地就离线可用了。少写这一句就成了撒谎。
                        // v2.73.3：更新检查从「每天最多一次」改为**每次打开 App 一次**——
                        //   承诺跟着实现走，这句是用户在「关于」里能看到的联网事实。
                        // v2.142.0：加了这句之后，「只有你手动开『从电脑接收』时才会连一次
                        //   同一个 WiFi」就**过期**了——配过信任设备后，App 一打开就自动
                        //   监听同网段（省得每次手动开窗）。承诺跟着实现走，这里必须改，
                        //   否则「诚实口径」只是当初写顺手了而已。
                        // v2.145.0：同理，「存档只在你主动导入时读」也得改——自动同步时
                        //   是电脑推过来就导入，不是你点的。
                        // ⚠ 2026-11-10：原文案是「数据整理 by 胡图图」，
                        //   开源后**去掉了个人署名**——作者是谁不该由 App 替谁声明，
                        //   仓库的 LICENSE / commit 署名已经把归属讲清楚了。
                        "本应用为非官方开源项目 · 存档不上传任何服务器；" +
                            "每次打开 App 会联网检查一次更新（可在「在线更新」里关掉）；" +
                            "点「下载电脑脚本」时会联网取一次那个文件；" +
                            "在「Mod 扩展」页点下载或刷新清单时会联网；" +
                            "配过信任设备后，App 打开时会在同一个 WiFi 内自动等电脑推存档" +
                            "（可在信任设备列表撤销）；" +
                            "「从 Steam 云获取存档」可选开「所有文件访问」权限，只用来读你下载的存档文件",
                        color = c.inkDim,
                    )
                    // v2.70.0 玻璃效果的算法归属（Apache-2.0 要求保留归属声明，
                    // 许可证全文在 assets/licenses/Apache-2.0-AndroidLiquidGlass.txt）。
                    PixelText(
                        "底栏玻璃效果算法源自 AndroidLiquidGlass（Apache-2.0），已做修改",
                        style = Px.caption, color = c.inkDim,
                    )
                    Spacer(Modifier.padding(2.dp))
                    // ⚠ 2026-11-10：这里原来是一整块「反馈 QQ 群 + 群主头像」的复制行，
                    //   开源后**整块删掉**（群号与头像都是个人信息）。
                    //   现在只留一个指向 GitHub 仓库的入口 —— 反馈改到 Issue 区，
                    //   那是长期可寻址的地方，也不依赖某个群还活着。
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("repo_url", REPO_URL))
                                android.widget.Toast.makeText(ctx, "仓库地址已复制", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // ⚠ 2026-11-10：这里原来放的是群主头像（个人照片），
                        //   开源后**头像也一并去掉** —— 个人照片不该随公开仓库分发。
                        //   改成纯文本两行，信息不减。
                        Column {
                            PixelText("源码已开源，欢迎提 Issue", color = c.blue)
                            PixelText("点按复制仓库地址", color = c.inkDim)
                        }
                    }
                    // v2.60.0：对外分发后 CC BY-NC-SA 3.0 / SIL OFL 1.1 的署名与随包义务生效，
                    // 署名、游戏素材声明、字体归属、协议全文都收进下方 showLicense 抽屉
                    // v2.77.0：入口文案由「开源与数据来源」改为「数据来源与协议」——
                    //   本 App **不开源自己的源码**（用户 2026-10-20 定调），挂着「开源」
                    //   字样会被读成「本应用开源」，属于误导。抽屉里要交代的其实是
                    //   **第三方归属 + 协议全文**（wiki 数据 / 字体 / 玻璃算法），
                    //   与发布仓 README 的「数据来源与协议」小节同名，两边口径一致。
                    Spacer(Modifier.padding(2.dp))
                    PixelText(
                        "📜 数据来源与协议",
                        color = c.blue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLicense.value = true }
                            .padding(vertical = 5.dp),
                    )
                }
            }
            item {
                // ⚠ 2026-11-10：这里原来是一整张「☕ 支持开发者」打赏卡
                //   （微信 + 支付宝两张收款码）。项目开源后**整卡删除**：
                //   ① 收款码是个人收款账户，不该随一个公开仓库分发；
                //   ② 打赏要维护（上榜联系），开源后没必要留着；
                //   ③ 留着反而会让 forks 的人以为「作者还在收钱、还会更新」。
                //   ⛔ 协议红线**没有因此放松**：CC BY-NC-SA 3.0 的 NC 判定看的是
                //   「使用的主要目的」，删掉打赏只会让分发更安全，不会让它更危险。
                //   若 fork 者要加自己的收款方式，那是**他们**的账号与责任，
                //   但同样得守住 NC：捐赠必须自愿、无对价。
                //
                //   ⚠ 文案口径（用户 2026-11-10 定调）：**不说「不再维护」**——
                //   万一以后又维护了呢？只说「更新频率放缓」，留有余地。
                PixelCard(title = "☕ 开源说明") {
                    PixelText(
                        "本应用源码已公开，欢迎自行 fork、修改、二次分发。",
                        color = c.inkDim,
                    )
                    Spacer(Modifier.height(4.dp))
                    PixelText(
                        "项目更新频率已放缓；遇到问题欢迎提 Issue 或自行修改。",
                        color = c.inkDim,
                    )
                    Spacer(Modifier.height(4.dp))
                    PixelText(
                        "协议与数据来源见下方「📜 数据来源与协议」。",
                        color = c.inkDim,
                    )
                }
            }
}
/**
 * v2.69.1 预览标记。长按版本号注入的假版本名里带这个串，
 * 再次长按时靠它判断「当前是不是预览态」——放进 `versionName` 而不是给
 * `UpdateRepo.encodeLatest` 加参数，是为了**一行数据层都不动**：
 * 加参数就得改 encodeLatest 的签名，而那个函数是 59.1 钉着的。
 * 顺带好处是横幅上明晃晃写着「预览」，测试时不会跟真更新混淆。
 */
private const val PREVIEW_MARK = "预览"

/**
 * v2.69.1：造一个假的「已发现新版本」快照，供长按版本号注入。
 *
 * 刻意做成**和真检查结果同形状**：走的是同一条 encodeLatest → 落盘 → MainActivity
 * 恢复 → 横幅/弹窗的链路，不是在 UI 层直接画一个假的组件。
 * 那样测出来的行为才是真的——包括「点稍后横幅还在」「点 ✕ 不再提示」这些语义。
 *
 * [apkUrl] 留空：横幅的下载按钮会如实提示「这一版没给出下载地址」，
 * 而不是把用户拉去浏览器下载一个假地址。测交互足够了，顺带避免误触。
 */
private fun previewLatest(): com.stardew.assistant.data.UpdateRepo.Latest =
    com.stardew.assistant.data.UpdateRepo.Latest(
        // 比当前版本大 1：正好满足 MainActivity 里的 pending.versionCode > VERSION_CODE
        versionCode = BuildConfig.VERSION_CODE + 1,
        versionName = "${BuildConfig.VERSION_NAME}（$PREVIEW_MARK）",
        date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(java.util.Date()),
        notes = listOf(
            "这是本地注入的预览，不是真更新",
            "新增怪物掉落物图鉴分组",
            "修正 7 处最爱礼物名",
        ),
        apkUrl = "",
        size = 4444069L,
        md5 = "",
    )

