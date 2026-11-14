package com.stardew.assistant.ui.report

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.stardew.assistant.data.FarmReport
import com.stardew.assistant.ui.LocalPixel
import com.stardew.assistant.ui.PixelButton
import com.stardew.assistant.ui.PixelCard
import com.stardew.assistant.ui.PixelText
import com.stardew.assistant.ui.Px
import com.stardew.assistant.ui.share.ShareHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.74.0「农场年度报告」的入口卡与预览。
 *
 * ## 数据从哪来
 * 报告数据在**导入存档**时顺手解析并落盘（见 `SaveImport` / `PrefsRepo.setReport`），
 * 所以这里**不再让用户选文件**——这是需求里「数据来自已解析存档，不重新选文件」的字面实现。
 * 换存档位就能看别的存档的报告（存档位切换在进度页）。
 *
 * ## 生成时机
 * 点「生成本期农场报告」才画图。画一张 1080×2000 的图要几十毫秒，
 * 放后台线程（`Dispatchers.IO`），画完再显示。
 */
@Composable
fun FarmReportCard(report: FarmReport?, profileName: String) {
    val c = LocalPixel.current
    var open by remember { mutableStateOf(false) }

    PixelCard(title = "🌾 农场年度报告") {
        if (report == null) {
            PixelText("这个存档还没有报告数据。", color = c.ink)
            Spacer(Modifier.height(4.dp))
            PixelText(
                "在上面「导入游戏存档」里导入一次就好，之后随时能在这里生成长图，不用再选文件。",
                color = c.inkDim,
            )
        } else {
            val who = report.farmerName.ifBlank { "这位农场主" }
            val farm = report.farmName.ifBlank { "未命名农场" }
            PixelText("$who · $farm", color = c.ink)
            val sub = listOfNotNull(
                report.dateText().takeIf { it.isNotBlank() },
                report.farmTypeName().takeIf { it.isNotBlank() },
                "存档位「$profileName」",
            ).joinToString(" · ")
            if (sub.isNotBlank()) PixelText(sub, color = c.inkDim)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(text = "生成本期农场报告", active = true, onClick = { open = true })
            }
            Spacer(Modifier.height(4.dp))
            PixelText(
                "整张图在本机生成，不联网、不上传存档。换存档位可以看别的存档的报告。",
                color = c.inkDim,
            )
        }
    }

    if (open && report != null) {
        ReportPreviewDialog(report = report, onDismiss = { open = false })
    }
}

/**
 * 预览长图 → 保存相册 / 分享。
 *
 * 预览**必须留**（和单物品分享卡同一条理由）：分享出去的图别人删不掉，
 * 得让人先看见；而且画完的位图就在手上，导出用的是同一张，不会出现「预览与导出不一致」。
 *
 * v2.87.0：**对外可见**了 —— 导入完存档后不用跳到别的页面，
 * 「查看农场报告」直接把它拉起来看长图。
 */
@Composable
fun ReportPreviewDialog(report: FarmReport, onDismiss: () -> Unit) {
    val c = LocalPixel.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(report) {
        val r = withContext(Dispatchers.IO) {
            runCatching { ReportCanvas.render(ctx, report, reportFacts(ctx, report)) }
        }
        r.onSuccess { bmp = it }
            .onFailure { failed = it.message ?: "生成失败" }
    }

    // Android 8/9 存公共「图片」目录要运行时权限；10+ 走 MediaStore，一个权限都不用。
    val saveNow: suspend (Bitmap) -> Unit = { b ->
        val res = withContext(Dispatchers.IO) { ShareHelper.saveToGallery(ctx, b, "农场年度报告") }
        res.onSuccess {
            Toast.makeText(ctx, "已存到$it", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(ctx, it.message ?: "保存失败", Toast.LENGTH_LONG).show()
        }
    }
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val b = bmp
        if (granted && b != null) {
            scope.launch { saveNow(b) }
        } else {
            Toast.makeText(
                ctx,
                "没拿到存储权限（Android 9 及以下才需要），图片存不进相册",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // ⚠ 底部安全区：**对话框窗口里两个 inset 都报 0**（实测 emulator-5554），
        //   所以不能只靠 WindowInsets。取「inset 与 48dp 手势区」的较大值当硬下限：
        //   48dp 是系统手势区的标准高度，点那里手势会吞掉点击。
        //   没有这一层时按钮落在 2374~2400，文字被挤出屏幕、点不到。
        val density = LocalDensity.current
        val safeBottom = with(density) {
            maxOf(
                WindowInsets.navigationBars.getBottom(density).toDp(),
                WindowInsets.systemGestures.getBottom(density).toDp(),
                48.dp,
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(c.screen)
                .padding(bottom = safeBottom),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PixelText("农场年度报告", style = Px.title, color = c.ink)
                Spacer(Modifier.weight(1f))
                PixelButton(text = "关闭", onClick = onDismiss)
            }

            // 图片区自己滚；标题与按钮固定（滚到最底才找按钮是难用的）
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                when {
                    failed != null -> PixelText(
                        "生成失败：$failed",
                        color = c.red,
                        modifier = Modifier.padding(16.dp),
                    )
                    bmp == null -> PixelText(
                        "正在生成…",
                        color = c.inkDim,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // ⚠ 别让图被放大：长图是 1080 **物理像素**宽。
                        //   竖屏手机（360dp @ 3x）正好 1:1，像素格子是齐的；
                        //   横屏/平板上 fillMaxWidth 会把它拉宽两倍多 → 像素风直接糊掉。
                        //   所以显示宽度封顶 1080 物理像素，多出来的空间留白居中。
                        val density = LocalDensity.current
                        val maxPx = with(density) { maxWidth.toPx() }
                        val imgW = if (maxPx > ReportCanvas.W.toFloat()) {
                            with(density) { (ReportCanvas.W / density.density).dp }
                        } else {
                            maxWidth
                        }
                        Image(
                            bitmap = bmp!!.asImageBitmap(),
                            contentDescription = "农场年度报告长图",
                            modifier = Modifier.width(imgW),
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PixelButton(
                    text = "保存到相册",
                    active = true,
                    enabled = bmp != null && !busy,
                    onClick = {
                        val b = bmp ?: return@PixelButton
                        val needPerm = Build.VERSION.SDK_INT < 29 &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                            PackageManager.PERMISSION_GRANTED
                        if (needPerm) {
                            legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            busy = true
                            scope.launch {
                                saveNow(b)
                                busy = false
                            }
                        }
                    },
                )
                PixelButton(
                    text = "分享这张",
                    enabled = bmp != null && !busy,
                    onClick = {
                        val b = bmp ?: return@PixelButton
                        busy = true
                        scope.launch {
                            // recycle = false：分享完预览还在，图不能被回收
                            val res = ShareHelper.shareBitmap(ctx, b, "农场年度报告", recycle = false)
                            busy = false
                            res.onFailure {
                                Toast.makeText(ctx, it.message ?: "分享失败", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
