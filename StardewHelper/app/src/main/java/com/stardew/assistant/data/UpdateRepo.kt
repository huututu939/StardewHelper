package com.stardew.assistant.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.stardew.assistant.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线更新检查（v2.55.0）——**全 App 唯一的网络出口文件，没有第二个**。
 *
 * 设计原则（改动前请先读）：
 * - **v2.73.3 起：每次进 App 静默检查一次**（用户 2026-10-19 明确要求，判据见 [shouldCheck]）。
 *   入口是「今日页横幅」。改动前是 20 小时节流（v2.61~v2.73.2），更早只有手动按钮一个入口。
 * - **仍然不做的事**：不后台轮询、不定时任务、不开机自启、失败不重试轰炸。
 *   频率上限 = 用户自己打开 App 的次数（进程内最多一次），且用户可以在设置里把
 *   「更新提示」整个关掉回到纯离线——「不轮询」的承诺靠的是**没人操作就没有请求**，
 *   不是靠一个时间窗口。
 * - **不上传任何数据**：请求不带存档、不带设备标识、不带自定义 header；
 *   唯一的信息泄露面是「谁在什么时候检查了更新」（服务器访问日志），故文案如实写明。
 * - 失败静默降级为一句提示，不弹错误打扰；离线时与「网络不可用」同路径。
 * - 数据源是 gitee 发布仓的 version.json（只放 APK + 清单 + data/ + tools/，不放源码）。
 *
 * v2.68.0 起本文件多了一件事：[downloadScript]——用户在「从电脑接收」抽屉里点一下，
 * 把电脑端那个 `send_save.bat` 拉到手机的「下载」目录。**同一个文件**继续装下全部网络行为，
 * 而不是另开一个 `ScriptRepo.kt`：verify 第 49 节的「网络出口白名单」钉的是文件名，
 * 放一个文件里等于把那条断言的原意（新增网络行为必须有人想清楚）保住了。
 * 它同样是用户点一下才发一次 GET，不轮询、不预取、不带任何标识。
 *
 * ⚠ 若要新增任何其它网络请求：必须同步改「关于」页的离线承诺文案 + verify 第 49 节
 *   的「唯一出口」断言，并让用户知情——那条断言会拦住悄悄扩散的网络面。
 */
object UpdateRepo {

    /** 日志 tag。失败原因必须落到这里——用户看得见的文案只是结论，不是原因。 */
    private const val TAG = "UpdateRepo"

    /**
     * 版本清单（GET 这个 URL 是本项目全部的网络行为之一）
     *
     * ⚠ 2026-11-10：项目迁到 GitHub，链接从 gitee raw 换成 **GitHub Release 资产直链**。
     *   换的**原因**不是「GitHub 更好」，是实测行为差异：
     *   ① `raw.githubusercontent.com` 在境内**不通**（连接被重置，0.35s 就失败），
     *      而 gitee raw 一直是通的 ⇒ 直接换域名等于把老用户的更新检查打死；
     *   ② **Release 资产**的响应带 `Content-Disposition: attachment` +
     *      `application/octet-stream`（浏览器**会落盘**），下载走
     *      `release-assets.githubusercontent.com`（境内可达、**免 token 可直下**），
     *      实测下载字节与源文件 md5 逐字节一致。
     *   ⇒ Release 资产是唯一能同时满足「境内可达 + 浏览器落盘 + 免认证」的路径。
     */
    const val VERSION_URL = "https://github.com/huututu939/xingluguzhushou/releases/latest/download/version.json"

    /**
     * Release 资产的前缀。version.json 里 apk 字段是**文件名**，拼这个前缀成直链。
     *
     * ⚠ 写全量字面量：verify 要能直接断言 URL 指向真实路径，
     *   拼出来的字符串在源码里看不见，是没法断言的。
     */
    private const val RELEASE_BASE = "https://github.com/huututu939/xingluguzhushou/releases/latest/download/"

    /**
     * 电脑端推送脚本（v2.68.0）。与 version.json 同一个发布仓、同一个 raw 域名——
     * 不为「一个小文件」单开一个仓，那只会多一处要维护的公开地址。
     * ⚠ 这里写全量字面量而不是拼 `RAW_BASE`：verify 要能直接断言 URL 指向真实路径，
     *   拼出来的字符串在源码里看不见，是没法断言的。
     */
    const val SCRIPT_URL = "https://github.com/huututu939/xingluguzhushou/releases/latest/download/send_save.bat"

    /** 落到手机上的文件名。必须与电脑端双击的那个完全一致，否则用户找不到。 */
    const val SCRIPT_NAME = "send_save.bat"

    /**
     * c239：电脑端**模组**包的发布仓直链 —— 给「在电脑上直接下载」这条路用。
     *
     * ## 为什么这条链接能直接用浏览器下
     * 实测（2026-11-09，gitee raw）：`.zip` 返回
     *   `Content-Type: application/octet-stream` +
     *   `Content-Disposition: attachment; filename="save_auto_push.zip"`
     * ⇒ 浏览器**会落盘**。对比同仓的 `.bat`（`text/plain` + `inline`）：
     *   浏览器把它当文本渲染、**不落盘**（v2.82.0 那次踩过这个坑，
     *   所以脚本才改走 `irm -OutFile`）。
     * ⇒ **模组包放发布仓后，电脑浏览器直接点链接就能下**，
     *   不用「手机下载 → 再传到电脑」。
     *
     * ## 两条路并存，不是二选一
     * - **手机下载**（[ASSET_MOD]）：读安装包里已有的文件，不联网，
     *   版本必然与 App 对得上；
     * - **电脑直链**（本常量）：不用装 App 的人也能拿，且省掉「传文件」这一步。
     * ⚠ 两条路给的是**同一个文件**（发布脚本保证逐字节一致），不会拿到两个版本。
     *
     * ⚠ 写全量字面量而不是拼 `RAW_BASE`：verify 要能直接断言 URL 指向真实路径
     *   （拼出来的字符串在源码里看不见，是没法断言的）。
     */
    const val MOD_URL =
        "https://github.com/huututu939/xingluguzhushou/releases/latest/download/save_auto_push.zip"

    /**
     * v2.82.0：电脑端一行命令（PowerShell），给「不装这个 App、只想在电脑上拿脚本」的人。
     *
     * ⚠ 为什么需要它：gitee raw 对 `.bat` 返回 `Content-Type: text/plain` +
     *   `Content-Disposition: inline`（实测 2026-10-21），浏览器**把 bat 当文本渲染**、
     *   不落盘——同一个服务上 `.apk` 是 octet-stream + attachment，所以会出现
     *   「App 更新能下、脚本下不来」的不对称。App 里原来那句「粘到电脑浏览器里也能下」
     *   就是照着这条假象写的，用户照做只会看到一屏 base64 乱码。
     * ⚠ `irm -OutFile` 写的是**原始字节**：实测落盘 md5 与源码仓那份一致、CRLF 未被换，
     *   且落盘文件名由 `-OutFile` 指定 → 拿到的就是能双击的 send_save.bat。
     * ⚠ 写全量字面量而不是拼 [SCRIPT_URL]：verify 要能直接断言这条命令指向真实路径
     *   （拼出来的字符串在源码里看不见，是没法断言的）。
     */
    const val SCRIPT_PS1_CMD =
        "irm https://github.com/huututu939/xingluguzhushou/releases/latest/download/send_save.bat -OutFile send_save.bat"

    /**
     * v2.75.0：电脑脚本**随 App 分发**（打进 assets），不再从网络拉。
     *
     * ⚠ 为什么要改：v2.75.0 给接收端加了验证码 + 协议版本，而**发布仓上的 bat
     *   还是旧的**（没带验证码）——从网络下载会拿到一个必然被 403「电脑脚本版本过旧」
     *   拒掉的脚本，用户照着提示「重新下载」拿到的还是同一份 → 死循环。
     *   让脚本跟着 App 走，这一步才成立。
     * ⚠ assets 里那份必须与 `tools/send_save.bat` **逐字节一致**（verify 79.15 钉着），
     *   否则手机上存的和电脑上跑的会不是同一个协议版本。
     */
    const val ASSET_SCRIPT = "tools/send_save.bat"

    /**
     * c237：电脑端**模组**「存档自动推送」（SMAPI mod）随 App 分发（打进 assets）。
     *
     * ⚠ 为什么模组也走「打进安装包」而不是联网拉：
     *   与 [ASSET_SCRIPT] 同理 —— 模组版本要和 App 的协议版本**对得上**，
     *   发布仓上的那份可能落后于 App（协议头对不上就是 403「版本过旧」），
     *   让用户拿到一个必然被拒的版本却不知道为什么，比不给还糟。
     *
     * ⚠ 这不是**新增网络出口**：读的是**安装包里已经有的文件**，不发任何请求。
     *   verify 第 49 节的「网络出口白名单」钉的是**联网行为**，这里一个都没加。
     *
     * ⚠ 落地名必须是 zip：SMAPI 装模组就是解压到 `Mods/<名字>/`，
     *   压缩包顶层就是 `StardewAutoPush/` 目录（ModBuildConfig 产出的形态）。
     */
    const val ASSET_MOD = "tools/save_auto_push.zip"

    /** 落到手机上的文件名。SMAPI 装模组要的是 zip，用户看到的就是这个名。 */
    const val MOD_NAME = "save_auto_push.zip"

    /** 模组包体积上限（真身约 38 KB；留足余量，超过就说明拿到的不是它）。 */
    private const val MOD_MAX_BYTES = 512 * 1024

    /**
     * c237：把电脑端模组包从**安装包里**读出来，落到手机的「下载」目录。
     * 同步调用，必须在后台线程执行。
     *
     * 用户拿到后在电脑上解压到 `Stardew Valley/Mods/` 目录即可。
     */
    fun downloadMod(ctx: Context): Script {
        val bytes = try {
            ctx.assets.open(ASSET_MOD).use { it.readBytes() }
        } catch (t: Throwable) {
            Log.w(TAG, "read bundled mod failed", t)
            return Script.Failed("读不到安装包里的电脑端模组，请重新安装这个 App")
        }
        if (bytes.isEmpty()) return Script.Failed("读到的模组包是空的，请重新安装这个 App")
        if (bytes.size > MOD_MAX_BYTES) {
            return Script.Failed("读到的模组包有 %d KB，不像是那个几十 KB 的包".format(bytes.size / 1024))
        }
        // ⚠ zip 的硬不变量：头两个字节必须是 PK。拿到的如果不是它（读错了文件、
        //   资源被打包规则剔掉了），用户会拿到一个解压不了的假包。
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            return Script.Failed("读到的不是模组包（格式不对），请重新安装这个 App")
        }
        runCatching { saveToDownloads(ctx, MOD_NAME, bytes) }
            .onFailure { Log.w(TAG, "save mod to downloads failed", it) }
            .getOrElse {
                return Script.Failed(
                    if (Build.VERSION.SDK_INT < 29) {
                        "存不进手机的「下载」目录，请检查剩余空间或存储权限"
                    } else {
                        "存不进手机的「下载」目录（系统没让写入），再试一次"
                    }
                )
            }
        return Script.Ok("手机的「下载」文件夹里的 $MOD_NAME", bytes.size)
    }

    /**
     * Android 8/9 写公共「下载」目录要运行时权限；Android 10 起走 MediaStore，一个权限都不用。
     * manifest 里那条 WRITE_EXTERNAL_STORAGE 因此限了 `maxSdkVersion="28"`。
     */
    fun needsStoragePermission(): Boolean = Build.VERSION.SDK_INT < 29

    /** 交给 [androidx.activity.result.contract.ActivityResultContracts.RequestPermission] 的权限名 */
    const val LEGACY_WRITE_PERMISSION = "android.permission.WRITE_EXTERNAL_STORAGE"

    /**
     * v2.73.3 静默检查的判据：**每次进 App 一次**（进程级去重）。
     *
     * ## 与旧版（20 小时节流）的关系
     * 旧节流的初衷是**防自动联网长成后台轮询**——那个顾虑的核心是
     * 「没人操作它也在发请求」。现在频率上限 = 用户自己打开 App 的次数：
     * 不自启、不定时、切后台不查，每次请求都由一次真实的人机动作触发，仍不是轮询；
     * 而「每次打开都查」是用户明确要求的（原话「改成每次进 APP 都检查更新一次」）。
     * 时间窗口因此整体废弃，`THROTTLE_MS` 已删——留一个不参与判定的常量是误导。
     *
     * ## 为什么是「进程级」而不是「每次回前台」
     * 进程活着期间切后台再回来，Compose 树还在、没有一次新的「打开」动作，
     * 再发一个请求属于同一句话里的重复；进程被杀/重启才是一次新的进 App。
     * 这同时挡住了「刷一下后台就发一个」这种最接近轮询的形态。
     *
     * ⚠ [enabled] 退路必须保留：关掉「更新提示」= 一次都不发，App 回到纯离线。
     *
     * ⚠ 判定即记账：返回 true 的同时就把本进程标记为已查。调用方查完不管成功失败
     *   都不再重试（与旧版「失败也记账，断网不轰炸」同一哲学）。
     *
     * @param enabled 用户是否开着「更新提示」
     * @return 本进程该不该发这一次静默检查
     */
    @Volatile
    private var sessionChecked = false

    fun shouldCheck(enabled: Boolean): Boolean {
        if (!enabled) return false
        if (sessionChecked) return false
        sessionChecked = true
        return true
    }

    data class Latest(
        val versionCode: Int,
        val versionName: String,
        val date: String,
        val notes: List<String>,
        val apkUrl: String,
        val size: Long,
        val md5: String,
    )

    sealed class Check {
        object UpToDate : Check()
        data class NewVersion(val latest: Latest) : Check()
        /** why 面向用户可读，不出现异常类名 */
        data class Failed(val why: String) : Check()
    }

    /** 同步网络调用，必须在后台线程执行（UI 层用 Dispatchers.IO）。任何失败都收敛为 Failed。 */
    fun check(currentCode: Int): Check {
        val text = runCatching { fetch(VERSION_URL) }
            .getOrElse { t ->
                // ⚠ 别把 404 也报成「连不上网络」：网络明明是通的，只是那个文件暂时不在，
                //   用户会去查 WiFi，而问题根本不在 WiFi——把人引向错误的地方比报错更坏。
                //   判据取 withRedirects 抛出的 "HTTP <code>" 字面量，不碰异常类名（R8 会改名）。
                val raw = t.message ?: ""
                val http = if (raw.startsWith("HTTP ")) raw.removePrefix("HTTP ").trim() else ""
                return Check.Failed(
                    when {
                        http == "404" -> "版本信息暂时取不到（404），多半是发布仓正在同步，稍后再试"
                        http.isNotEmpty() -> "服务器回了 HTTP $http，稍后再试"
                        else -> "连不上网络，稍后再试（检查需要联网，App 其它功能离线可用）"
                    }
                )
            }
        val latest = runCatching { parse(text) }.getOrNull()
            ?: return Check.Failed("版本信息读取失败，可能是发布仓数据格式变了")
        return if (latest.versionCode > currentCode) Check.NewVersion(latest) else Check.UpToDate
    }

    sealed class Script {
        /** [where] 是给用户看懂的位置描述（「手机的『下载』文件夹」），不是内部路径 */
        data class Ok(val where: String, val bytes: Int) : Script()

        /** [why] 面向用户可读，不出现异常类名，也不把系统原文（可能是带路径的 EACCES）甩给用户 */
        data class Failed(val why: String) : Script()
    }

    /** 脚本体量上限。真实文件 ~20 KB；给到 2 MB 是给容错留的余量，同时挡住「返回了一整个网页」 */
    private const val SCRIPT_MAX_BYTES = 2 * 1024 * 1024

    /**
     * 拉取电脑端推送脚本并落到手机的「下载」目录。同步调用，必须在后台线程执行。
     *
     * 为什么不给浏览器下载（`ACTION_VIEW` 打 URL，本可以一个 Intent 搞定）：
     * ① 浏览器可能把 .bat 当未知类型改名（见过变 .txt / .download 的），用户拿到手双击就废了；
     * ② 用户要的是「文件已经躺在下载目录里」，走浏览器还要再点两次保存确认。
     * 脚本本来就是要在电脑上双击的，所以宁可多写这几行，也要保证**文件名一个字不差**。
     */
    fun downloadScript(ctx: Context): Script {
        // ⚠ v2.75.0：从**安装包里**读脚本，不再联网。
        //   （原实现是 fetchBytes(SCRIPT_URL)，配套说明见 [ASSET_SCRIPT]。）
        val bytes = try {
            ctx.assets.open(ASSET_SCRIPT).use { it.readBytes() }
        } catch (t: Throwable) {
            Log.w(TAG, "read bundled script failed", t)
            return Script.Failed("读不到安装包里的电脑脚本，请重新安装这个 App")
        }
        // 下面两条不是洁癖：gitee 出错页、网关拦截页都可能带着 200 回来，
        // 而它们的体积和编码都跟真脚本差得远。不校验的话，用户会拿到一个双击没反应的假货，
        // 却只看到「下载成功」——比报错更坏。
        if (bytes.isEmpty()) return Script.Failed("下载到的内容是空的，仓库可能临时不可用")
        if (bytes.size > SCRIPT_MAX_BYTES) {
            return Script.Failed("下载到的内容有 %d KB，不像是那个几十 KB 的脚本".format(bytes.size / 1024))
        }
        // 纯 ASCII 是这个 bat 的**硬不变量**：cmd.exe 按系统 OEM 代码页读 .bat，
        // UTF-8 的中文注释会被当乱码命令执行（已在 gen_send_save_bat.py 与 verify 钉住）。
        // 这里再钉一道是防「拿到的根本不是它」：HTML/GBK 错误页里必有 >= 0x80 的字节。
        if (bytes.any { (it.toInt() and 0xFF) > 0x7F }) {
            return Script.Failed("下载到的内容不是脚本文件（编码不对），请稍后再试")
        }
        runCatching { saveToDownloads(ctx, SCRIPT_NAME, bytes) }
            // ⚠ 原来这个异常被 runCatching 吞掉、既不记日志也不往上传：
            //   真机上失败过却查不出原因，就是这么来的。真因必须进 Log。
            .onFailure { Log.w(TAG, "save script to downloads failed", it) }
            .getOrElse {
                // ⚠ 文案按系统版本分：Android 10+ 走 MediaStore，**一个存储权限都不需要**，
                //   在那儿说「检查存储权限」是误导（用户会去找一个根本不存在的权限开关）。
                //   「权限被拒」只可能发生在 Android 8/9 那条老路径上。
                return Script.Failed(
                    if (Build.VERSION.SDK_INT < 29) {
                        "存不进手机的「下载」目录，请检查剩余空间或存储权限"
                    } else {
                        "存不进手机的「下载」目录（系统没让写入），再试一次"
                    }
                )
            }
        return Script.Ok("手机的「下载」文件夹里的 $SCRIPT_NAME", bytes.size)
    }

    /**
     * 写系统「下载」目录。Android 10+ 走 MediaStore（**不需要任何权限**）；
     * Android 8/9 走公共目录，调用前必须已拿到 WRITE_EXTERNAL_STORAGE（见 [needsStoragePermission]）。
     *
     * ## ⚠ 29+ 分支里三个写死的细节，每一个都有实测依据（2026-10-20）
     *
     * **① `RELATIVE_PATH` 必须显式给。**
     * 不给时落点由系统默认决定，**而默认行为不是契约**：实测 API 35 上恰好默认落到
     * Download/，但各版本/各 ROM 不同，可能落到外部存储根目录（用户明明下到了却找不到），
     * 或在某些定制 ROM 上直接 insert 失败。显式给才在所有 29+ 上行为确定。
     *
     * **② `MIME_TYPE` 只能是 `application/octet-stream`，不能改成 `text/plain`。**
     * 实测（直插 MediaProvider）：DISPLAY_NAME="send_save.bat" + MIME="text/plain"
     * → MediaProvider 判定扩展名与 MIME 冲突，**落盘成 "send_save.bat.txt"**；
     * 而电脑端双击的是 "send_save.bat"。改成 text/plain 只会让用户双击找不到，
     * 比现状更糟。octet-stream 则原名保留（实测落盘 "send_save.bat"）。
     *
     * **③ 同名要覆盖，不能让它加 (1)。**
     * MediaStore 遇到同名会自动加 (1)（实测下载目录里出现过 "send_save.bat (1)" / "(2)"），
     * 用户下两次就不知道该双击哪个；而 Android 8/9 走 File 是覆盖的。
     * 两条路径行为不一致 = 同一个功能在两个系统版本上表现不同。
     * 所以先查同目录下的同名条目，有就复用它的 URI 覆盖写。
     */
    private fun saveToDownloads(ctx: Context, name: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= 29) {
            // 已有同名 → 覆盖写（不再让 MediaStore 加 (1)）
            val existing = findExistingDownload(ctx, name)
            if (existing != null) {
                ctx.contentResolver.openOutputStream(existing, "wt")?.use { it.write(bytes) }
                    ?: error("MediaStore 打不开输出流")
                return
            }
            val vs = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                // ⚠ 必须显式给相对路径：不给时落点由系统默认决定，各版本/各 ROM 不同
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                // 先挂起再写：写一半的文件不该出现在文件管理器里
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, vs)
                ?: error("MediaStore 拒绝创建条目")
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("MediaStore 打不开输出流")
            } catch (t: Throwable) {
                // 失败必须把占位条目删掉，否则下载目录里会留一个 0 字节的 send_save.bat，
                // 用户下次再点「下载」就会撞上同名文件
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                throw t
            }
            ctx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null, null,
            )
            return
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        // 这条只在 Android 8/9 走（10+ 见上面的 MediaStore 分支），
        // 而「建不了目录」多半是空间不足或权限没给到。
        if (!dir.isDirectory && !dir.mkdirs()) error("建不了「下载」目录（Android 9 及以下需存储权限）")
        // 覆盖同名而不是加 (1)：两条路径的命名规则本来就不同（MediaStore 会加 (1)），
        // 再叠一层会让「我到底双击哪个」变糊涂。
        File(dir, name).outputStream().use { it.write(bytes) }
    }

    /**
     * 找「下载」目录下已存在的同名文件。
     *
     * ⚠ `RELATIVE_PATH` 在库里存的是**带尾斜杠**的形式（实测 "Download/"），
     *   少了斜杠就查不到 —— 那会退化成「每次都新建」，(1)(2) 又回来了。
     */
    private fun findExistingDownload(ctx: Context, name: String): Uri? = runCatching {
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(name, Environment.DIRECTORY_DOWNLOADS + "/"),
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            ContentUris.withAppendedId(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                c.getLong(0),
            )
        }
    }.getOrNull()

    private fun open(u: String): HttpURLConnection =
        (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            useCaches = false
            instanceFollowRedirects = false // gitee raw 会跨主机 302，手动跟，见 withRedirects
            setRequestProperty("User-Agent", "StardewHelper/${BuildConfig.VERSION_CODE} asset-fetch")
        }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /**
     * 跟完重定向再读。v2.68.0 抽出来是因为清单（文本）和脚本（字节）要读同一条重定向链，
     * 复制第二份循环 = 两份各自漂移的 302 策略，gitee 改一次行为就只坏一个。
     */
    private fun <T> withRedirects(u: String, read: (HttpURLConnection) -> T): T {
        var conn = open(u)
        try {
            var status = conn.responseCode
            var hops = 0
            while (status in REDIRECTS && hops < 3) {
                val loc = conn.headerFields["Location"]?.firstOrNull() ?: break
                conn.disconnect()
                conn = open(loc)
                status = conn.responseCode
                hops++
            }
            if (status != HttpURLConnection.HTTP_OK) error("HTTP $status")
            return read(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetch(u: String): String = withRedirects(u) { it.inputStream.bufferedReader().readText() }

    private fun fetchBytes(u: String): ByteArray = withRedirects(u) { it.inputStream.readBytes() }

    private fun parse(text: String): Latest {
        val o = JSONObject(text)
        val notes = mutableListOf<String>()
        o.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) notes += a.getString(i) }
        return Latest(
            versionCode = o.getInt("versionCode"),
            versionName = o.getString("versionName"),
            date = o.optString("date"),
            notes = notes,
            apkUrl = asciiUrl(RELEASE_BASE + o.getString("apk")),
            size = o.optLong("size"),
            md5 = o.optString("md5").lowercase(),
        )
    }

    /**
     * v2.69：把 [Latest] 序列化成**本地快照**（给 PrefsRepo 存进 `update_pending_json`）。
     *
     * ⚠ 为什么不复用 [parse] / version.json 那个格式：那个解析的是**远端清单**
     *   （字段名是 apk/date/notes，apkUrl 还要拼 RAW_BASE），这个写的是**本地已解析好的**
     *   （直接存完整 apkUrl）。两个格式各有各的演进方向，混成一个函数会让远端改字段时
     *   把落盘的旧快照一起改坏——而那份快照的用途恰恰是"远端改坏时还能提示用户"。
     *   所以这里显式只存**提示真正需要的字段**，apkUrl 原样存（本地不需要再拼）。
     */
    fun encodeLatest(l: Latest): String = JSONObject()
        .put("vc", l.versionCode)
        .put("vn", l.versionName)
        .put("dt", l.date)
        .put("sz", l.size)
        .put("md5", l.md5)
        .put("url", l.apkUrl)
        .put("notes", JSONArray(l.notes))
        .toString()

    /**
     * v2.69：读回 [encodeLatest] 写的快照。**脏数据一律返回 null**（当"没有待提示的版本"）。
     *
     * 与 [PrefsRepo.parseRecent] / `parseTodoList` 同款：一条脏记录不该让 App 起不来。
     * 返回 null 时调用方按"没有 pending"走，**不去清空既有的 updateSeenVersion**——
     * 那是用户划掉过某版的决定，跟快照能不能解析是两回事。
     */
    fun decodeLatest(raw: String?): Latest? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val vc = o.optInt("vc", 0)
            if (vc <= 0) return null
            val notes = mutableListOf<String>()
            o.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) notes += a.optString(i) }
            Latest(
                versionCode = vc,
                versionName = o.optString("vn"),
                date = o.optString("dt"),
                notes = notes,
                // 空 url 也要如实存空：横幅的下载按钮要能判出"没有下载地址"而不是崩在 Intent 上
                apkUrl = o.optString("url"),
                size = o.optLong("sz", 0L),
                md5 = o.optString("md5").lowercase(),
            )
        }.getOrNull()
    }

    /**
     * 把 URL 里的非 ASCII 字符（如中文文件名）percent-encode 成纯 ASCII。
     * java.net.URI 的多参构造会自动对 path 编码；toASCIIString 输出 IDNA + percent 编码。
     * ⚠ 发布仓文件名已由发布脚本改为 ASCII（stardew-assistant_*.apk），这里是**防御**：
     *   万一清单里混进中文路径，浏览器收到的也是合法 URL，而不是赌各浏览器的容错。
     *   （v2.55.0 首推后冒烟实测：未编码的中文 URL 在严格 HTTP 客户端直接抛异常。）
     *
     * ⚠⚠ v2.72 修正：原来用的是**四参**构造 `URI(scheme, host, path, fragment)`，
     *   它把第三个参数当 authority 且**只认 host 不认端口**，会把 `…:8731/a.zip`
     *   悄悄改成 `…/a.zip`。本项目的三个 URL 都不带端口，所以这个坑一直没露；
     *   Mod 扩展包下载第一次用带端口的地址就当场炸了（同款写法同款病，已一并修）。
     *   现在用五参 `(scheme, authority, path, query, fragment)`，authority 取
     *   `rawAuthority`（已转义，host:port 都在）。
     */
    private fun asciiUrl(u: String): String =
        if (u.isEmpty()) u
        else runCatching {
            val uri = java.net.URI(u)
            java.net.URI(
                uri.scheme,
                uri.rawAuthority,
                uri.rawPath,
                uri.rawQuery,
                uri.rawFragment,
            ).toASCIIString()
        }.getOrDefault(u)
}
