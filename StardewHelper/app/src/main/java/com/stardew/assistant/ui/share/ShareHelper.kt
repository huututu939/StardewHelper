package com.stardew.assistant.ui.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * v2.25 分享导出：Bitmap → PNG → FileProvider uri → 系统分享 chooser。
 *
 * 三条硬约束：
 * 1. 任何一步失败都**不崩**，返回 Result 交 UI 显示红字/Toast（沿用 GuideLoadFail 的口径）。
 * 2. PNG 压缩必须在 IO 线程（1080×1620 约 80~200ms，主线程做直接掉帧）。
 * 3. 「截取当前画面」用 PixelCopy 拿窗口内容，**只能拿到当前可见部分**——
 *    完整长图需要逐页离屏或滚动拼接，本版不做，UI 文案已如实标注。
 */
object ShareHelper {

    /**
     * 分享图底部水印文案。
     *
     * ⚠ 2026-11-10：原文案带 QQ 群号，开源后**去掉了群号** ——
     *   群号是个人联系方式，而分享图会被转发到不认识的人手里。
     *   现在只留应用名与「开源」这一事实。
     */
    fun watermark(): String = "星露小助手 · 开源项目"

    /** 内存里的卡片 → PNG → 分享 */
    suspend fun shareBitmap(ctx: Context, bmp: Bitmap, label: String, recycle: Boolean = true): Result<Unit> =
        withContext(Dispatchers.IO) {
            val file = runCatching { writePng(ctx, bmp, label) }.getOrElse {
                if (recycle && !bmp.isRecycled) bmp.recycle()
                return@withContext Result.failure(it)
            }
            val res = runCatching { launchChooser(ctx, file, label) }
            if (recycle && !bmp.isRecycled) bmp.recycle()
            res.map { }
        }

    /**
     * 存到手机相册。
     *
     * - Android 10+（API 29）走 MediaStore，**一个权限都不用**；
     * - Android 8/9 走公共「图片」目录，调用前必须已拿到 WRITE_EXTERNAL_STORAGE
     *   （manifest 里那条权限限了 `maxSdkVersion="28"`，与 [UpdateRepo] 存脚本同一条）。
     *
     * @return 存到了哪（给用户看的路径文案）
     */
    fun saveToGallery(ctx: Context, bmp: Bitmap, label: String): Result<String> = runCatching {
        val name = fileName(label)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val vs = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/星露小助手",
                )
                // 先挂起再写：写一半的文件不该出现在相册里
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vs,
            ) ?: error("相册拒绝创建条目")
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) error("图片压缩失败")
                } ?: error("相册打不开输出流")
            } catch (t: Throwable) {
                // 失败必须把占位条目删掉，否则相册里会留一张 0 字节的坏图
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                throw t
            }
            ctx.contentResolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                },
                null, null,
            )
            "相册 · 星露小助手/$name"
        } else {
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES,
                ),
                "星露小助手",
            )
            if (!dir.isDirectory && !dir.mkdirs()) error("建不了「图片」目录")
            val f = File(dir, name)
            FileOutputStream(f).use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) error("图片压缩失败")
            }
            "相册 · 星露小助手/$name"
        }
    }

    /**
     * 截取当前窗口**可见画面**并分享。
     * 取不到 Activity/Window（如 Compose 预览环境）时如实报错，不静默失败。
     */
    suspend fun shareScreen(ctx: Context, label: String): Result<Unit> {
        val activity = ctx as? Activity
            ?: return Result.failure(IllegalStateException("当前环境不是 Activity，无法截屏"))
        val window = activity.window
            ?: return Result.failure(IllegalStateException("取不到窗口，无法截屏"))
        // 注意：PixelCopy.getWindowSize 是 API 34+，本项目 minSdk 26，不能用。
        // decorView 的宽高就是窗口内容区尺寸，与 PixelCopy(Window,…) 的输出一致。
        val decor = runCatching { window.decorView }.getOrNull()
            ?: return Result.failure(IllegalStateException("取不到窗口视图，无法截屏"))
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) {
            return Result.failure(IllegalStateException("窗口尺寸异常（${w}×$h），无法截屏"))
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val copied = copyWindow(window, bmp)
        if (!copied) {
            bmp.recycle()
            return Result.failure(IllegalStateException("截屏失败，请重试"))
        }
        return shareBitmap(ctx, bmp, label)
    }

    /** PixelCopy 是异步回调，这里挂成协程 */
    private suspend fun copyWindow(window: android.view.Window, dest: Bitmap): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    window,
                    dest,
                    { result -> cont.resume(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper()),
                )
            } catch (t: Throwable) {
                cont.resume(false)
            }
        }

    /** 写 cacheDir/shared/<时间戳>.png。调用方保证已在 IO 线程。 */
    private fun writePng(ctx: Context, bmp: Bitmap, label: String): File {
        val dir = File(ctx.cacheDir, "shared")
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw IllegalStateException("建目录失败：${dir.absolutePath}")
        }
        val file = File(dir, fileName(label))
        FileOutputStream(file).use { out ->
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IllegalStateException("PNG 压缩失败")
            }
            out.flush()
        }
        if (file.length() <= 0L) throw IllegalStateException("导出的文件是空的")
        return file
    }

    private fun launchChooser(ctx: Context, file: File, label: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享到…")
        // 从非 Activity Context 启动 chooser 需要挂 NewTask
        if (ctx !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

    private fun fileName(label: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val safe = label.replace(Regex("[^\\u4e00-\\u9fa5A-Za-z0-9_]"), "_")
        return "星露小助手_${safe}_$ts.png"
    }
}