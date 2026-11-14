package com.stardew.assistant.ui.settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.CustomFont
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.ui.AppFont
import com.stardew.assistant.ui.FontScale
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelSegmented
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.ThemeMode
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
/**
 * c230：设置页「外观」栏的卡片列表（从 SettingsPage.kt 原样搬来）。
 * 唯一机械差异：留在主文件的状态以 MutableState 下传，体内改 `.value`；
 * 文案与结构一字未动。
 */
fun LazyListScope.settingsAppearanceTab(
    snap: PrefsRepo.Snapshot,
    repo: PrefsRepo,
    scope: kotlinx.coroutines.CoroutineScope,
    c: com.stardew.assistant.ui.PixelColors,
    ctx: android.content.Context,
    fontPicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, android.net.Uri?>,
    customMeta: CustomFont.Meta,
    customId: String,
    confirmDeleteFont: MutableState<Boolean>,
) {
            item {
                PixelCard(title = "🎨 主题") {
                    PixelSegmented(
                        // c201 B4：标签与落盘值来自 ThemeMode.OPTIONS（Pair 捆在一起），
                        // 不再靠下标映射——加一档只改一处，不会显示别的档位的名字。
                        options = ThemeMode.OPTIONS.map { it.second },
                        selected = ThemeMode.OPTIONS.indexOfFirst { it.first == snap.theme }
                            .takeIf { it >= 0 } ?: 0,
                        onSelect = { i ->
                            val picked = ThemeMode.OPTIONS.getOrNull(i)?.first ?: ThemeMode.SYSTEM
                            scope.launch { repo.setTheme(picked) }
                        },
                    )
                    Spacer(Modifier.height(5.dp))
                    if (snap.theme == ThemeMode.SEASON) {
                        PixelText("季节配色：跟随当前存档日期，春夏秋冬各换一套色板（换存档或改日期即时生效）。", color = c.inkDim)
                    }
                }
            }
            item {
                // v2.93.0 字体档（像素 / 系统）/ v2.95.0 加第三档「自定义」（用户导入 TTF）。
                // 三档**都只影响 App 内**——桌面小组件、分享卡、农场报告长图仍是像素字体
                // （那三处走独立渲染通道：RemoteViews / Canvas / 分享卡自己钉回 PixelFont）。
                // 即点即生效——setFontFamily 落盘 → snapshot 流推新值 → MainActivity 换 CompositionLocal。
                PixelCard(title = "🔠 字体") {
                    PixelSegmented(
                        options = AppFont.OPTIONS.map { it.second },
                        selected = AppFont.OPTIONS.indexOfFirst { it.first == snap.fontFamily }
                            .coerceAtLeast(0),
                        onSelect = { i ->
                            val v = AppFont.OPTIONS[i].first
                            if (v == AppFont.CUSTOM && customId.isBlank()) {
                                // 还没导入过 ⇒ 直接弹文件选择器（选了自动切过去）
                                fontPicker.launch(FONT_MIME_TYPES)
                            } else {
                                scope.launch { repo.setFontFamily(v) }
                            }
                        },
                    )
                    Spacer(Modifier.height(5.dp))
                    PixelText(
                        "系统字体更清晰，只影响 App 内；桌面小组件、分享图与农场报告仍是像素字体。",
                        color = c.inkDim,
                    )

                    // ── v2.95.0：自定义字体的信息块 ──────────────────────
                    // 已导入 ⇒ 显示字体**自己声明**的版权/许可原文 + 一条固定免责声明。
                    // 免责声明**独立于字体内容、永远显示**：导入的字体是用户自己的文件，
                    // 本 App 只作展示、不参与分发，授权与合规由用户自负其责。
                    if (!customId.isBlank()) {
                        Spacer(Modifier.height(6.dp))
                        PixelCard(title = "📄 字体信息") {
                            customMetaRow("名称", customMeta.fullName.ifBlank { customMeta.family })
                            customMetaRow("版本", customMeta.version)
                            customMetaRow("版权", customMeta.copyright)
                            customMetaRow("许可", customMeta.license)
                            customMetaRow("许可链接", customMeta.licenseUrl)
                            Spacer(Modifier.height(4.dp))
                            PixelText(
                                "以上信息由字体文件自身声明。字体由你自行导入，" +
                                    "本 App 仅作展示，不参与其分发；其授权与合规由你自行负责，与本 App 无关。",
                                color = c.inkDim,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PixelButton("更换字体", onClick = { fontPicker.launch(FONT_MIME_TYPES) })
                                PixelButton("删除字体", onClick = { confirmDeleteFont.value = true })
                            }
                        }
                    }
                }
            }
            item {
                // v2.59 字号（群友反馈⑦）：App 此前锁死 1×，系统开了大字体也看不见。
                // 即点即生效——setFontScale 落盘 → snapshot 流推新值 → MainActivity 重组 Density。
                PixelCard(title = "🔤 字号") {
                    PixelSegmented(
                        options = FontScale.LABELS,
                        selected = snap.fontScaleIdx.coerceIn(0, FontScale.LABELS.lastIndex),
                        onSelect = { i -> scope.launch { repo.setFontScale(i) } },
                    )
                    Spacer(Modifier.height(5.dp))
                    PixelText(
                        "像素字体非整数倍放大略有发糊，属正常；个别密集页面在「特大」下会截字。",
                        color = c.inkDim,
                    )
                }
            }
            // v2.70.0 底栏样式开关。**只在 33+ 显示**：低版本本来就没有玻璃能力，
            // 给一个拨了没反应的开关比不给更糟（开关承诺的行为必须真实存在）。
            if (com.stardew.assistant.ui.glass.GlassLadder.enabled) {
                item {
                    PixelCard(title = "🫧 底栏样式") {
                        PixelSegmented(
                            options = listOf("液态玻璃", "经典木纹"),
                            selected = if (snap.glassBar) 0 else 1,
                            onSelect = { i -> scope.launch { repo.setGlassBar(i == 0) } },
                        )
                        Spacer(Modifier.height(5.dp))
                        PixelText(
                            "液态玻璃：底部导航悬浮胶囊，透出页面内容（Android 13+ 特性）。",
                            color = c.inkDim,
                        )
                    }
                }
                // c228 玻璃浓度：轻=更透、标准=原样、浓=更实的磨砂。
                //   同样只在 33+ 且玻璃开着时显示——木纹模式下拨它没东西可变。
                //   默认「标准」，与 c227 前的观感一致；档位翻译成参数只在
                //   GlassLadder 单源做（veilScale/blurScale/refractScale）。
                if (snap.glassBar) {
                    item {
                        PixelCard(title = "🧪 玻璃浓度") {
                            PixelSegmented(
                                options = listOf("轻", "标准", "浓"),
                                selected = snap.glassLevel,
                                onSelect = { i -> scope.launch { repo.setGlassLevel(i) } },
                            )
                            Spacer(Modifier.height(5.dp))
                            PixelText(
                                "轻档玻璃更透，浓档磨砂更实，改动立刻生效。",
                                color = c.inkDim,
                            )
                        }
                    }
                }
            }
            // c215 音效开关：控制勾选叮咚/完成琶音/翻页滑音/错误 buzz/点击咔哒这五类 UI 音效。
            //   触觉（震动）不受它影响——想连震动一起关，用系统的「触觉反馈」开关。
            item {
                PixelCard(title = "🔊 音效") {
                    PixelSegmented(
                        options = listOf("开", "关"),
                        selected = if (snap.soundEnabled) 0 else 1,
                        onSelect = { i -> scope.launch { repo.setSoundEnabled(i == 0) } },
                    )
                    Spacer(Modifier.height(5.dp))
                    PixelText(
                        "关掉后勾选、完成、翻页等提示音不播（媒体音量仍受手机控制）；触觉震动不受影响。",
                        color = c.inkDim,
                    )
                }
            }
            // c203：外观类偏好一键恢复默认。
            // 原来改乱了主题/字体/字号只能一项项改回去；这里给一个出口。
            // ⚠ **只恢复外观**：游戏进度、待办、勾选、存档一概不碰（那是不可逆的损失）；
            //   自定义字体**文件也留着**，只把档位切回像素（用户想用还能重新选中）。
            item {
                PixelCard(title = "🧹 恢复默认") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PixelButton("恢复默认外观", onClick = {
                            scope.launch {
                                repo.resetAppearance()
                                android.widget.Toast.makeText(
                                    ctx,
                                    "主题、字体、字号已恢复默认（进度与待办不受影响）",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        })
                    }
                    Spacer(Modifier.padding(2.dp))
                    // c226 瘦身：与点击后的 Toast 重复（「进度与待办不受影响」Toast 已说），一行装下。
                    PixelText(
                        "只恢复主题、字体、字号；进度待办不受影响，字体文件会保留。",
                        color = c.inkDim,
                    )
                }
            }
}
/**
 * v2.95.0：SAF 选字体文件时用的 MIME 白名单。
 *
 * `ActivityResultContracts.OpenDocument` 的 `input` 是**非空** `Array<String>`，
 * 传 null 编译不过（v2.95.0 首次编译就撞在这）。
 *
 * ## ⚠ 这里为什么**不能**用通配符
 * `OpenDocument` 会把 input 同时写进 `EXTRA_MIME_TYPES`，并把 intent 的 type 设成通配。
 * DocumentsUI 拿到 `EXTRA_MIME_TYPES` 后会**逐项按字面量**去匹配文件，
 * 而通配符在那个列表里**不是**通配 —— 结果就是：
 * **所有文件（含 .ttf）全变成灰色不可点**（2026-10-26 模拟器实测）。
 *
 * ## 那为什么 App 里不行、独立 intent 里却行
 * DocumentsUI 只有在**能拿到调用方包名**时才会按 extra 过滤。
 * `adb shell am start` 起的 intent 没有调用方 ⇒ 不过滤 ⇒ 看着一切正常。
 * ⇒ **这个坑只在真 App 里暴露，独立 intent 测不出来** —— 别再用 am start 验证 SAF 行为。
 *
 * ## 正确的做法
 * extra 里只放**真实的字体 MIME**（含 Android 15 的 `font/ttf` 与 `font/otf`）。
 * 少了任何一项，命中那一项的字体文件就会变灰 —— 而用户根本不知道为什么。
 * 所以这一列要**尽量全**：宁可多列几个，也不要让某个 .ttf 变灰。
 *
 * ⚠ 注释里**不能**写那个通配的字面量（它含 `*` 加 `/`，KDoc 会被提前闭合，
 *   报一串「Expecting a top level declaration」—— v2.95.0 连续踩了两次）。
 */
private val FONT_MIME_TYPES = arrayOf(
    "font/ttf",                     // Android 15 实测：MediaStore 给 .ttf 的就是它
    "font/otf",
    "font/woff",
    "font/woff2",
    "application/x-font-ttf",       // 各家文件管理器/旧版 Android 的常见映射
    "application/x-font-truetype",
    "application/x-font-opentype",
    "application/vnd.ms-opentype",
    "application/font-sfnt",
    "application/octet-stream",     // 认不出扩展名时的兜底
)

/**
 * v2.95.0：自定义字体信息的一行（标签 + 内容）。
 *
 * ## 缺项怎么显示
 * 字体文件**自己没声明**这一项时显示「该字体文件未声明此项」——
 * **不编造**、不拿别的字段顶替（比如拿家族名当版权行显示就是撒谎）。
 */
@Composable
private fun customMetaRow(label: String, value: String) {
    val c = LocalPixel.current
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        PixelText("$label：", color = c.inkDim, style = Px.caption)
        PixelText(
            if (value.isBlank()) "该字体文件未声明此项" else value,
            color = if (value.isBlank()) c.inkDim else c.ink,
            style = Px.caption,
        )
    }
}
