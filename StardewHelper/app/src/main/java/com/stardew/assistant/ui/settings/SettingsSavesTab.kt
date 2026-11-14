package com.stardew.assistant.ui.settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.ui.PixelColors
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
/**
 * c230：设置页「存档」栏的卡片列表（从 SettingsPage.kt 原样搬来）。
 * 唯一机械差异：留在主文件的状态以 MutableState 下传，体内改 `.value`；
 * 文案与结构一字未动。
 */
fun LazyListScope.settingsSavesTab(
    c: com.stardew.assistant.ui.PixelColors,
    snap: PrefsRepo.Snapshot,
    repo: PrefsRepo,
    exportLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, android.net.Uri?>,
    importLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, android.net.Uri?>,
    saveImportLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, android.net.Uri?>,
    saveParsing: Boolean,
    confirmClearChecks: MutableState<Boolean>,
    receiveOpen: MutableState<Boolean>,
    steamGuideOpen: MutableState<Boolean>,
) {
            item {
                PixelCard(title = "💾 进度备份") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PixelButton("导出备份", onClick = { exportLauncher.launch("stardew_backup.json") })
                        PixelButton("导入备份", onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) })
                    }
                    Spacer(Modifier.padding(2.dp))
                    // c214：一键清空当前存档的图鉴打勾。放在备份区里——因为清空不可逆，
                    // 紧挨「导出备份」提示用户「要清空先备份」。只动当前存档的 checks，其他存档不动。
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PixelButton("清空勾选", onClick = { confirmClearChecks.value = true })
                    }
                    Spacer(Modifier.padding(2.dp))
                    PixelText("导出内容：全部存档的打勾记录、游戏日期、职业、主题、字体、字号。换手机不会自动同步，记得定期导出。", color = c.inkDim)
                    // c226 瘦身：清空语义与「先备份」提醒确认框里都有更完整版，这里只留一句。
                    PixelText("清空勾选：只清当前存档，建议先导出备份。", color = c.inkDim, style = Px.caption)
                }
            }
            item {
                // v2.65：从游戏存档反推图鉴进度。**只补不删**——存档里有的自动勾上，
                // 你之前手动勾的一条都不动。绝不写回游戏存档（见 SaveImport 的 KDoc）。
                PixelCard(title = "🎮 导入游戏存档") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PixelButton(
                            if (saveParsing) "解析中…" else "选择存档文件",
                            onClick = {
                                // PixelButton 没有 enabled 参数（十几处在用，不改签名），
                                // 用「解析中直接 return」代替禁用——反正一按就是这一下。
                                if (saveParsing) return@PixelButton
                                saveImportLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream", "*/*")
                                )
                            },
                        )
                        // v2.67.0：同一个 WiFi 下让电脑点一下推过来，不用手动压/传/找。
                        // ⚠ PixelButton 签名是 (text, onClick, active)，`active` 在第三位，
                        //   所以尾随 lambda 会落到 active 上（编译期报 Boolean 错），必须显式命名。
                        PixelButton("从电脑接收", onClick = { receiveOpen.value = true })
                    }
                    Spacer(Modifier.height(6.dp))
                    // v2.86.0：从 Steam 云获取（无需电脑）。
                    // 这一条与上面两条**并列**：都是「怎么把存档弄进来」的不同路子，
                    // 不是同一条路的两个入口。
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PixelButton(
                            "☁️ 从 Steam 云获取（无需电脑）",
                            active = true,
                            onClick = { steamGuideOpen.value = true },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    PixelText(
                        "把 Steam 存档文件夹压缩成 zip 传进来，自动勾上已完成的钓鱼、烹饪、制作与博物馆进度。",
                        color = c.inkDim,
                    )
                    PixelText(
                        // ⚠ 目录名是 `Saves`，不是 `Save_Game`（v2.67.0 改正）。
                        //   写错会让用户照着找半天——本机没装游戏，靠官方 wiki 核实：
                        //   英文 + 两个中文 wiki 都写 %AppData%\StardewValley\Saves\。
                        "存档位置：%APPDATA%\\StardewValley\\Saves\\<农场名>\\（压缩这个文件夹本身，不是上级目录）。",
                        color = c.inkDim,
                    )
                    PixelText(
                        // c226 瘦身：数字随数据漂移的写法保留（verify 66 节按映射表动态核对）。
                        "只读不改；可自动识别 368 条，另有 208 条手动勾。",
                        color = c.inkDim,
                    )
                    PixelText(
                        // c226 瘦身：rar/7z 的 Windows 打包教程过细，抽屉与 wiki 都能查到。
                        "只支持 zip，rar/7z 需先转成 zip。",
                        color = c.inkDim,
                    )
                }
            }
}
