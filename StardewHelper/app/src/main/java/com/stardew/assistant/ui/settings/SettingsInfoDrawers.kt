package com.stardew.assistant.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stardew.assistant.R
import com.stardew.assistant.ui.DetailDrawer
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.RowSep
import com.stardew.assistant.ui.STEAM_CLOUD_ATTRIBUTION

/**
 * c230：设置页的信息抽屉（数据来源与协议）从 SettingsPage.kt 原样搬来。
 *
 * ⚠ 2026-11-10（项目开源）：原来的另外两个抽屉（致谢名单 / 赞助名单）
 *   **整抽屉删除** —— 用户口径：「开源了，别人就能看到他们的 ID 等信息，
 *   这侵犯了别人的信息」。名单里的昵称是**群友的** ID：
 *   在群里致谢是一回事，把人写进公开仓库是另一回事。
 *   CREDITS / SAVE_TESTERS / SPONSORS 三张名单一并从代码里删掉
 *   （verify 157 节钉住它们不得复活）。
 */
@Composable
internal fun SettingsInfoDrawers(
    ctx: android.content.Context,
    showLicenseState: MutableState<Boolean>,
) {
    // 主文件的开关原样接回：抽屉体里的 visible/onDismiss 一行不用改
    var showLicense by showLicenseState
    val c = LocalPixel.current

    // v2.60.0 开源与数据来源：wiki 署名（BY）+ 数据协议（CC BY-NC-SA 3.0）+ 游戏素材声明
    // + 字体署名（SIL OFL 1.1）。链接一律 **tap-to-copy** 不走 ACTION_VIEW——关于页
    // 「不读取也不上传任何存档数据」的口径不能被自己打破（网络行为只有检查更新
    // 与用户手动开启的局域网接收，见 verify 49.1）。
    // v2.77.0：抽屉标题同样由「开源与数据来源」改为「数据来源与协议」——
    //   **对外分发的义务是「第三方归属 + 协议全文」，不是「本 App 开源」**。
    //   ⚠ 2026-11-10 更新：项目**已开源**（源码在 GitHub），但抽屉标题仍叫
    //   「数据来源与协议」而不是「开源」——义务是第三方归属 + 协议全文，
    //   源码归属由仓库的 LICENSE 与 commit 署名交代，不该在 App 里重复声明。
    DetailDrawer(visible = showLicense, title = "数据来源与协议", onDismiss = { showLicense = false }) {
        var oflOpen by remember { mutableStateOf(false) }
        var ccOpen by remember { mutableStateOf(false) }
        // v2.70.0 玻璃算法归属：协议全文走 res/raw（与 OFL/CC 同一惯例）
        var apacheOpen by remember { mutableStateOf(false) }
        val oflText = remember { readRawText(ctx, R.raw.ofl_fusion_pixel) }
        val ccText = remember { readRawText(ctx, R.raw.cc_by_nc_sa_3_0) }
        val apacheText = remember { readRawText(ctx, R.raw.apache_2_0_androidliquidglass) }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PixelCard(title = "📚 数据整理") {
                PixelText("本应用数据整理自 Stardew Valley Wiki（星露谷物语维基），内容遵循 CC BY-NC-SA 3.0 协议。")
                Spacer(Modifier.height(5.dp))
                CopyLinkRow("英文站：stardewvalleywiki.com", "https://stardewvalleywiki.com/", ctx)
                CopyLinkRow("官方简中站：zh.stardewvalleywiki.com", "https://zh.stardewvalleywiki.com/", ctx)
                CopyLinkRow("整理数据：发布仓 data/ 目录（CC BY-NC-SA 3.0）", "https://github.com/huututu939/xingluguzhushou/tree/main/data", ctx)
            }
            PixelCard(title = "🎮 游戏素材") {
                PixelText(
                    "《星露谷物语》(Stardew Valley) 的游戏素材、名称与商标归 ConcernedApe 所有。" +
                        "本应用为非官方粉丝工具，与 ConcernedApe 无关联、未获其背书；" +
                        "游戏图标仅作识别与展示用途。",
                    color = c.inkDim,
                )
            }
            // v2.90.0：Steam 云存档的归属声明。
            // 之前只有「游戏素材」那张卡说了 ConcernedApe，**没提 Valve**——
            // 但 App 里确实有 Steam 云存档功能（跳 Steam 官网下存档），那一段漏了。
            // v2.91.0 补回远端 2.90.0 的原文：光说商标不够，还得说清
            // **账号不经我们手**、**存档不上传也不再分发**（CC BY-NC-SA 的义务）。
            PixelCard(title = "☁️ Steam 云存档") {
                PixelText(
                    "Steam 是 Valve Corporation 的注册商标，Steam 云存档功能由 Valve 提供。" +
                        "本应用为非官方粉丝工具，与 Valve / ConcernedApe 均无关联、未获其背书。",
                    color = c.inkDim,
                )
                Spacer(Modifier.height(5.dp))
                PixelText(STEAM_CLOUD_ATTRIBUTION, color = c.inkDim)
            }
            // v2.72 Mod 扩展包：SVE 素材署名。Nexus Mods 允许使用其 Mod 素材，**前提是署名要随内容走**。
            // 包内 manifest.json 自带同一句 authorCredit，但那只在用户装了包之后才读得到；
            // 不装包的人也该知道这些头像从哪来，所以这里再写一份静态署名，两边互为兜底。
            PixelCard(title = "🧩 Mod 扩展包") {
                PixelText(
                    "SVE 扩展包基于 Stardew Valley Expanded（作者 FlashShifter），" +
                        "已获授权使用素材并署名。",
                )
                Spacer(Modifier.height(5.dp))
                CopyLinkRow(
                    "Nexus Mods：nexusmods.com/stardewvalley/mods/3753",
                    "https://www.nexusmods.com/stardewvalley/mods/3753",
                    ctx,
                )
                Spacer(Modifier.height(5.dp))
                PixelText(
                    "扩展包由用户自行在「村民 → 扩展」里下载，非本应用内置；" +
                        "本应用只读取不修改包内文件，关掉或删除即不再显示。" +
                        "礼物与生日数据按扩展包版本更新，Mod 侧改动以包内 manifest 为准。",
                    color = c.inkDim,
                )
            }
            PixelCard(title = "🔤 界面字体") {
                PixelText("缝合像素字体 Fusion Pixel Font（作者 TakWolf）的子集，以 SIL OFL 1.1 授权使用。")
                Spacer(Modifier.height(5.dp))
                PixelText(
                    "按许可第 3 条，子集（修改版）已更名为 TutuPixel，不再使用原保留名；" +
                        "版权与许可元数据原样保留，协议全文见下方。",
                    color = c.inkDim,
                )
            }
            // v2.145.0：局域网存档同步协议的归属声明（任务书第八节第 4 条：
            //   「在『开源与数据来源/致谢』页如实注明所参考的协议与开源项目」）。
            //
            // ⚠ **查证结论（逐条核过，不是印象）**：
            //   局域网这套协议**没有参考任何外部项目** —— 它是本项目**自己定的**：
            //   - 接收端 SaveReceiver.kt 与电脑端脚本 send_save.ps1/bat 是**同一份代码
            //     的两端**，一次落地（c106 / v2.67.0，2026-10-16），协议由这对自家
            //     实现互相定义，不是抄来的；
            //   - 存档自动同步（c231~c235）是在这套自有协议上**向后兼容地**加了两条
            //     请求头（X-Stardew-Device-Id / X-Stardew-Token），没有引入外部约定；
            //   - 整条链路的文件里**没有任何第三方归属声明痕迹**（Copyright / SPDX /
            //     "derived from" / "based on" 全部零命中），也没有抄用外部项目代码；
            //   - 依赖只有 AndroidX 官方库（Apache-2.0）与 SMAPI 的 ModBuildConfig
            //     （MIT，仅构建期，不进 APK）。
            //   所以这里**不需要标注任何外部项目** —— 但「参考了谁」是用户会问的问题，
            //   把「参考的是自家早期脚本、没参考外部项目」写清楚，比只留一张空卡更有用。
            //   ⚠ 宁可写「没有参考外部项目」，也不编一个开源项目名去挂靠：
            //   挂错归属比不写更糟（把别人的说成自己的，或把自己的说成别人的）。
            PixelCard(title = "📡 局域网存档同步") {
                PixelText(
                    "电脑端模组「存档自动推送」与本 App 的局域网接收，用的是本应用" +
                        "自己的上传约定：同网段 TCP 端口 38217~38236、UDP 广播发现、" +
                        "上传请求带配对码与协议版本头。",
                )
                Spacer(Modifier.height(5.dp))
                PixelText(
                    "这套约定由本项目自己的接收端与电脑端脚本（send_save.bat）" +
                        "配套定义，未参考其他开源项目，也未套用第三方协议。",
                    color = c.inkDim,
                )
                Spacer(Modifier.height(5.dp))
                PixelText(
                    "自动信任所需的设备号与令牌请求头，是在此之上新增的；" +
                        "旧脚本与手动配对码流程不受影响。",
                    color = c.inkDim,
                )
                Spacer(Modifier.height(5.dp))
                PixelText(
                    "全程仅在你自己的局域网内直连，不经任何外部服务器；" +
                        "令牌可在信任设备列表里随时撤销，撤销即刻生效。",
                    color = c.inkDim,
                )
            }
            // v2.70.0 底栏液态玻璃的算法归属（Apache-2.0 要求保留版权与许可声明）。
            // 措辞如实：shader 与效果链（vibrancy 已按观感移除）移植自该项目并做了修改，
            // 宿主层用 RenderNode 而非其 GraphicsLayer——差异细节在 PixelGlass.kt 头注释。
            PixelCard(title = "✨ 界面效果") {
                PixelText(
                    "底栏液态玻璃效果的算法移植自 AndroidLiquidGlass" +
                        "（作者 Kyant，GitHub: Kyant0/AndroidLiquidGlass），" +
                        "以 Apache License 2.0 授权使用；本项目对其着色器与效果链做了修改。",
                )
                Spacer(Modifier.height(5.dp))
                CopyLinkRow("开源地址：github.com/Kyant0/AndroidLiquidGlass", "https://github.com/Kyant0/AndroidLiquidGlass", ctx)
            }
            LicenseExpandCard("📜 SIL OFL 1.1 协议全文", oflText, oflOpen) { oflOpen = it }
            LicenseExpandCard("📜 CC BY-NC-SA 3.0 协议全文", ccText, ccOpen) { ccOpen = it }
            LicenseExpandCard("📜 Apache License 2.0 协议全文", apacheText, apacheOpen) { apacheOpen = it }
        }
    }
}
/**
 * 抽屉里的可复制行：点按复制完整内容并 Toast 提示（不跳浏览器，保持离线口径）。
 *
 * ⚠ v2.82.0 加 [toastText]：复制的不一定是链接——电脑脚本那行是**一条命令**，
 *   弹「已复制链接」会让用户以为复制到的是网址。
 */
@Composable
internal fun CopyLinkRow(
    label: String,
    text: String,
    ctx: android.content.Context,
    toastText: String = "已复制链接",
) {
    val c = LocalPixel.current
    PixelText(
        label,
        color = c.blue,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
                android.widget.Toast.makeText(ctx, toastText, android.widget.Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 3.dp),
    )
}

/** 协议全文卡：默认折叠（2 万字的 legalcode 直接摊开会把抽屉冲爆），点标题展开/收起 */
@Composable
private fun LicenseExpandCard(title: String, body: String, open: Boolean, onToggle: (Boolean) -> Unit) {
    val c = LocalPixel.current
    PixelCard {
        PixelText(
            title,
            color = c.ink,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(!open) }
                .padding(vertical = 3.dp),
        )
        if (open) {
            Spacer(Modifier.height(6.dp))
            RowSep()
            PixelText(body, color = c.inkDim, style = Px.caption)
        }
    }
}

/** 读 res/raw 文本。读失败返回一句占位——协议文本缺失不该让整个设置页崩掉。 */
private fun readRawText(ctx: android.content.Context, resId: Int): String =
    runCatching { ctx.resources.openRawResource(resId).bufferedReader().use { it.readText() } }
        .getOrDefault("（协议文本加载失败，请见发布页 LICENSE 文件）")

/** ⚠ 2026-11-10（项目开源）：CREDITS / SAVE_TESTERS / SPONSORS 三张名单已整表删除。
 *
 * 用户口径：「开源了，别人就能看到他们的 ID 等信息，这侵犯了别人的信息。」
 * 名单里的昵称是**群友的** ID——在群里致谢是一回事，把人写进公开仓库是另一回事。
 * 对贡献者的感谢改在**提交信息**里（git log 就是台账，且不上任何公开仓）。
 * verify 157 节钉住这三个标识符不得复活。
 */

// ⚠ 2026-11-10：原来的 AUTHOR_EMAIL（赞助上榜联系邮箱）随「联系作者上榜」
//   一起删掉了 —— 项目停更、作者不再受理上榜申请，留一个死邮箱没意义。
