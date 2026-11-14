package com.stardew.assistant.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.stardew.assistant.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * v2.72.0 **Mod 扩展包仓库**（第一版：只下载与读取，不写回）。
 *
 * ## 这一版的边界（先说没做什么）
 * - 不做本地选文件导入：包只能从 URL 下载，用户不选路径。
 * - 不做编辑写回：包是**只读**数据源，App 不改包里的任何文件。
 * - 不做喜欢/讨厌礼物、行程、红心事件、礼物图标对接、季节变体头像。
 * - 隐藏 NPC（`hiddenNpcs`）只统计数量、**不展示**（那份数据本身待补）。
 *
 * ## 两种取包方式（都支持，由配置项的地址形态决定）
 * - **方式 A 索引清单**：地址指向一个 `index.json`，里面 packages 数组可以挂多个包。
 *   取清单 → 展示列表 → 用户点哪个下哪个。以后加包只改仓库，不改 App。
 * - **方式 B 单包直链**：地址以 `.zip` 结尾。此时「清单」就是它自己，
 *   名字/作者/描述在下载解包后从包内 `manifest.json` 读回来再显示
 *   （只有 SVE 一个包时最省事，不用先养一份 index.json）。
 *
 * 判据是**地址后缀**，不另设开关：同一份配置两种形态都能配，少一个会漂移的状态。
 *
 * ## 地址不进代码
 * [DEFAULT_INDEX_URL] 只是**首次写入偏好时的种子值**，真值存在 DataStore（`mod_index_url`），
 * 用户可以在本页改。「仓库地址」是数据不是代码——换仓库、换镜像、自己搭个 index.json
 * 都不用重新打包 APK。
 *
 * ## 网络面（与另两个出口同族的纪律）
 * 本文件是 App 的**第三个**联网出口，前两个是 [UpdateRepo]（检查更新 / 下载电脑脚本）
 * 与 `SaveReceiver`（局域网收存档）。三者共同的可点：
 * ① 本页的两个 GET 与 SaveReceiver 都是**用户点一下**才发请求；[UpdateRepo] 的
 *    检查更新另有「每次打开 App 自动查一次」的静默入口（用户可关，见其头注释）——
 *    不轮询、不预取、不开机自启这条对三者都成立；
 * ② 都不带存档、不带设备标识、不带自定义 header；
 * ③ 「关于」页的联网口径要与这三处行为相符（verify 49.2 钉着）。
 *
 * ⚠ 新增别的联网出口时，先改「关于」文案 + verify 49.1 的白名单，别悄悄扩。
 */
object ModRepo {

    private const val TAG = "ModRepo"

    /** 首次写入偏好的种子地址。**不是硬编码的取包地址**——真值在 DataStore，用户可改。 */
    const val DEFAULT_INDEX_URL =
        "https://github.com/huututu939/xingluguzhushou/releases/latest/download/index.json"

    /** 清单体量上限。真实 index.json 几百字节；512 KB 是容错余量，同时挡住「返回了一整个网页」。 */
    private const val INDEX_MAX_BYTES = 512L * 1024

    /** 单个扩展包体量上限。SVE 包 316 KB；64 MB 足够塞很多 NPC，挡住异常大的响应。 */
    private const val PACK_MAX_BYTES = 64L * 1024 * 1024

    /** zip 条目数上限。真实包 40 条上下；挡住「把整个目录树塞进 zip」。 */
    private const val MAX_ENTRIES = 2000

    /** 单条目体量上限。头像 64×64 才几十 KB；4 MB 已远超需求。 */
    private const val MAX_ENTRY_BYTES = 4L * 1024 * 1024

    /**
     * 头像单格边长（像素）。
     *
     * 星露谷头像是**表情精灵条**：一个文件横若干列、纵若干行 64×64 表情格。
     * 原版 1 列 × 8 行，SVE 2 列 × 5~10 行，**也有 2 列 × 2 行**（如 Marlon）。
     * 所以「第一格」= 左上 64×64，**不能**按「图宽×图宽」裁（那会一次裁出 4 个表情），
     * 也不能按「方图就不裁」（Marlon 正是方图，见 [decodeFirstCell]）。
     */
    private const val PORTRAIT_CELL_PX = 64

    /**
     * v2.73.5 作物 sprite sheet 的**单帧宽度**（像素）。
     *
     * 包里 crops.json 的 `icon` 指的是**成长阶段 sprite sheet**：多帧横向排列，
     * 每帧 16 宽 × 图高（实测 SVE v2.5 的 12 张全是 128×32 = 8 帧，`gold_carrot`
     * 是 112×32 = 7 帧）。
     */
    private const val CROP_FRAME_PX = 16

    /**
     * v2.73.8：包内物品图归一化目标宽度（像素）。
     *
     * = App 内置素材里菜谱/作物图标的**原生宽度**（实测原版 81 道菜谱全是 48×48）。
     * 包内是 16×16 的官方图，放大到 48 才和内置素材同密度。
     */
    private const val ITEM_ICON_TARGET_PX = 48

    /** modId 形态：纯 ASCII 短串，因为它要当目录名。 */
    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")

    // ── 数据模型 ────────────────────────────────────────────────

    /** 清单里的一个包条目（方式 A），或由单包直链合成的同形条目（方式 B）。 */
    data class PackMeta(
        val modId: String,
        val name: String,
        /**
         * **扩展包自己的版本号**，必须与包内 `manifest.json` 的 `packageVersion` 相同。
         *
         * ⚠ 它**不是** manifest 的 `modVersion`（那是上游游戏 Mod 的版本，SVE 本体 1.15.11）。
         *   填错不会报错，但「新版本」提示会永远不亮或一直亮——两种都是坏体验。
         *   包内 manifest 声明的号优先（见 [parsePack]），这里只是清单侧的对照值。
         */
        val version: String,
        val author: String,
        val description: String,
        val icon: String,
        val downloadUrl: String,
        val minAppVersion: String,
        val updatedAt: String,
        val size: Long = 0L,
        val md5: String = "",
        /** 方式 B 的合成条目为 true——此时名字/作者要等解包后才有 */
        val provisional: Boolean = false,
    )

    /** 已落盘、可参与合并的一个包。 */
    data class Installed(
        val modId: String,
        val name: String,
        /**
         * **包内 Mod 的版本**（= manifest 的 `modVersion`，SVE 本体是 1.15.11）。
         *
         * ⚠ 它**不参与**任何比较：这是「我打包时上游 Mod 是第几版」，不是「这个扩展包第几版」。
         *   新旧判断一律走 [pkgVersion]。UI 上两个号都要显示时，措辞必须写清是哪个，
         *   只印一个 "v1.15.11" 会让用户以为扩展包停更在一月。
         */
        val version: String,
        val author: String,
        val description: String,
        val updatedAt: String,
        val npcs: List<GiftVillager>,
        val hiddenCount: Int,
        val portraitCount: Int,
        val dir: File,
        /** v2.73.4：包内 crops.json 的作物。老包没有 → 空表（图鉴不出 SVE 作物分区）。 */
        val crops: List<ModCrop> = emptyList(),
        /** v2.73.7：包内 recipes.json 的料理。老包没有 → 空表（图鉴菜谱组不出 SVE 料理）。 */
        val recipes: List<ModRecipe> = emptyList(),
        /** v2.73.9：包内 fish.json 的鱼。老包没有 → 空表（图鉴钓鱼组不出 SVE 鱼）。 */
        val fish: List<ModFish> = emptyList(),
        /**
         * v2.75.1：fish.json 顶层的 `noLegendary` 说明（包作者自述"这个包没有传奇鱼王"）。
         * 图鉴顶部要照着它说，**不能自己编**。空 = 没说 → 那句话不显示。
         */
        val fishNoLegendary: String = "",
        /** v2.73.9：包内 shops.json 的商店概要。老包没有 → 空表（图鉴不出「商店」分类）。 */
        val shops: List<ModShop> = emptyList(),
        /** v2.73.10：包内 heart_events.json 的红心事件链概要。老包没有 → 空表。 */
        val heartEvents: List<ModHeartEvent> = emptyList(),
        /** v2.73.10：包级「婚后」说明（整包一份，不是每人一份）。 */
        val postMarriage: String = "",
        /** v2.73.11：包内 locations.json 的主要地点。老包没有 → 空表。 */
        val locations: List<ModLocation> = emptyList(),
        /** v2.73.11：包级「SVE 给原版节日加的新剧情」说明（整包一份）。 */
        val festivals: String = "",
        /**
         * **这个扩展包自己的版本**（= manifest 的 `packageVersion`，v2.2 是 "2.2"）。
         *
         * 「有新版」只能拿**清单号 vs 这一行**比。混用 [version] 会得出
         * 「清单 2.2 比已装 1.15.11 新」这种看着对、其实把两套号放在一起比了的结论——
         * 用户明确要求对比 packageVersion，不要用 modVersion。
         *
         * 包内 manifest 自带这个键时以它为准；老包（v2.0 无此键）回退到装包时写下的
         * `package.json`（那是 index.json 给的号）；都没有则空串，更新检测降级为「重新下载拿新的」。
         */
        val pkgVersion: String = "",
        /**
         * 用户看到的短标签（manifest 的 `shortName`，SVE 包是 "SVE"）。
         *
         * 缺失时 UI 退回 modId 大写，所以这里可以安心留空——**不写死**，
         * 否则装第二个包时那一列会显示成别的包的名字。
         */
        val tag: String = "",
    ) {
        val marriageableCount: Int get() = npcs.count { it.marriageable == true }

        /**
         * 这个包实际给了几段礼物数据（1~5）。
         *
         * 判据是**至少有一个 NPC 那一段非空**——按单个 NPC 数会得到 14 这种没意义的数，
         * 而 UI 要回答的是「这个包有没有讨厌/中立数据」，那要看全体。
         * 老包（v2.0 只有 lovedGifts）恒为 1，UI 据此隐藏「礼物数据不全」的提示。
         */
        val giftTierCount: Int by lazy {
            listOf<(GiftVillager) -> List<String>>(
                { it.loves }, { it.likes }, { it.dislikes }, { it.hates }, { it.neutrals },
            ).count { get -> npcs.any { get(it).isNotEmpty() } }
        }
    }

    sealed class Fetch {
        data class Ok(val packs: List<PackMeta>) : Fetch()

        /** [why] 面向用户可读，不出现异常类名，也不把系统原文甩给用户 */
        data class Failed(val why: String) : Fetch()
    }

    sealed class Install {
        data class Ok(val pack: Installed) : Install()
        data class Failed(val why: String) : Install()
    }

    /** 解包后、尚未并入 [GameData] 时的中间结构（比 [Installed] 多一个版本门槛字段）。 */
    private data class Contents(
        val modId: String,
        val name: String,
        val version: String,
        val tag: String = "",
        val author: String,
        val description: String,
        val updatedAt: String,
        val minAppVersion: String,
        /**
         * v2.73.13：manifest 声明的模块（`modules` 数组）。
         *
         * 拆包后**必需**：「这个包声明了 npcs 模块却一个 NPC 都没有」= 坏包；
         * 而「这个包压根不声明 npcs」（商店包/作物包）= 正常的分片，不能当坏包拒掉。
         */
        val modules: List<String> = emptyList(),
        val npcs: List<GiftVillager>,
        val hiddenCount: Int,
        val portraitCount: Int,
        val dir: File,
        val crops: List<ModCrop> = emptyList(),
        val recipes: List<ModRecipe> = emptyList(),
        val fish: List<ModFish> = emptyList(),
        val fishNoLegendary: String = "",
        val shops: List<ModShop> = emptyList(),
        val heartEvents: List<ModHeartEvent> = emptyList(),
        val postMarriage: String = "",
        val locations: List<ModLocation> = emptyList(),
        val festivals: String = "",
        val pkgVersion: String = "",
    ) {
        fun toInstalled() = Installed(
            modId = modId, name = name, version = version, tag = tag, author = author,
            description = description, updatedAt = updatedAt, npcs = npcs,
            hiddenCount = hiddenCount, portraitCount = portraitCount, dir = dir,
            crops = crops, recipes = recipes, fish = fish, shops = shops,
            fishNoLegendary = fishNoLegendary,
            heartEvents = heartEvents, postMarriage = postMarriage,
            locations = locations, festivals = festivals, pkgVersion = pkgVersion,
        )
    }

    // ── 目录 ───────────────────────────────────────────────────

    /** 解包后的私有目录。放 filesDir：不进公共存储、不要任何权限、卸载即清。 */
    fun modsDir(ctx: Context): File = File(ctx.filesDir, "mods")

    fun packDir(ctx: Context, modId: String): File = File(modsDir(ctx), modId)

    /** 下载暂存：cacheDir 会被系统按空间回收，这里只放「正在下」的那一个 zip。 */
    private fun downloadTmp(ctx: Context): File = File(ctx.cacheDir, "mods_download.zip")

    private fun stageDir(ctx: Context, tag: String): File = File(modsDir(ctx), ".stage-$tag")

    // ── 取清单 ─────────────────────────────────────────────────

    /**
     * 拉清单。同步网络调用，**必须在后台线程执行**（UI 层用 Dispatchers.IO）。
     * 走方式 A 还是 B 由地址后缀判定。
     */
    fun fetchIndex(url: String): Fetch {
        val u = url.trim()
        if (u.isEmpty()) return Fetch.Failed("还没有填仓库地址")
        if (!isHttpsOrHttp(u)) return Fetch.Failed("地址要以 http:// 或 https:// 开头")
        if (isZipUrl(u)) {
            // 方式 B：清单就是它自己。先登记一条占位条目，名字等解包后才知道。
            val id = provisionalId(u)
            return Fetch.Ok(listOf(
                PackMeta(
                    modId = id,
                    name = id,
                    version = "",
                    author = "",
                    description = "单包直链：下载后从包内 manifest.json 读说明",
                    icon = "",
                    downloadUrl = u,
                    minAppVersion = "",
                    updatedAt = "",
                    provisional = true,
                ),
            ))
        }
        val bytes = runCatching { fetchBytes(u, INDEX_MAX_BYTES) }
            .getOrElse { return Fetch.Failed(networkWhy(it)) }
        if (bytes.none { (it.toInt() and 0xFF) > 0x7E }) {
            return Fetch.Failed("这个地址返回的不是清单（纯文本不像 JSON）")
        }
        val packs = runCatching { parseIndex(String(bytes, Charsets.UTF_8)) }
            .getOrElse { t ->
                Log.w(TAG, "index 解析失败", t)
                return Fetch.Failed("清单读取失败，多半是这个地址不是 index.json")
            }
        if (packs.isEmpty()) return Fetch.Failed("清单里一个能用的包都没有")
        return Fetch.Ok(packs)
    }

    /** 解析 index.json。字段缺就给默认值——清单由包作者维护，不该一处缺字段就整页崩。 */
    private fun parseIndex(text: String): List<PackMeta> {
        val arr = JSONObject(text).optJSONArray("packages") ?: JSONArray()
        val out = mutableListOf<PackMeta>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("modId").trim()
            val url = asciiUrl(o.optString("downloadUrl").trim())
            // modId 要当目录名用，形态先钉死；下载非 http(s) 的一律不展示（避免点开才报错）
            if (!ID_PATTERN.matches(id) || !isHttpsOrHttp(url)) continue
            out += PackMeta(
                modId = id,
                name = o.optString("name").ifBlank { id },
                version = o.optString("version"),
                author = o.optString("author"),
                description = o.optString("description"),
                icon = asciiUrl(o.optString("icon").trim()),
                downloadUrl = url,
                minAppVersion = o.optString("minAppVersion"),
                updatedAt = o.optString("updatedAt"),
                size = o.optLong("size", 0L),
                md5 = o.optString("md5").lowercase(),
            )
        }
        return out
    }

    // ── 装包 ───────────────────────────────────────────────────

    /**
     * 下载 → 解包 → 校验 → 换目录。同步调用，**必须在后台线程执行**。
     *
     * [onProgress] 收 0..100（仅下载阶段有值；服务器不给 content-length 时停在 0，不报假进度）。
     *
     * ⚠ 幂等：同一个包重复下载是**覆盖**而不是叠加。新内容先解到暂存目录，
     *   全部校验过了才把正式目录整体换掉——所以「下到一个坏包」不会把用户
     *   正在用的那一版弄没（这是「重复下载不崩溃」的另一半保证）。
     */
    suspend fun install(ctx: Context, pack: PackMeta, onProgress: (Int) -> Unit = {}): Install {
        val url = pack.downloadUrl.trim()
        if (!isHttpsOrHttp(url)) return Install.Failed("下载地址无效")

        val tmp = downloadTmp(ctx)
        // 自动重试**一次**。理由不是"重试能治百病"，而是实测：移动网络下大文件
        // （本包 316 KB）确实会被偶发截断，okhttp 抛 unexpected end of stream，
        // 用户看到的就是「下载失败」而什么也没做错。重试一次能救回绝大多数瞬时断流，
        // 也让"重复下载覆盖"不至于因为一次抖动就失败；仍失败才把原因如实报上去。
        var lastErr: Throwable? = null
        for (attempt in 1..2) {
            val err = runCatching { download(url, tmp, onProgress) }.exceptionOrNull()
            if (err == null) { lastErr = null; break }
            lastErr = err
            // ⚠ 这里**必须**打日志：下面 networkWhy 会把认不出的异常收敛成一句人话，
            //   没有日志就等于「什么都试过了还是连不上网络」，排查时连个词都抓不到。
            Log.w(TAG, "下载失败（第 $attempt 次）url=$url", err)
            runCatching { tmp.delete() }
            if (attempt == 1) kotlinx.coroutines.delay(600)
        }
        if (lastErr != null) return Install.Failed(networkWhy(lastErr))

        val tag = pack.modId.ifBlank { "pack" }
        val stage = stageDir(ctx, tag)
        runCatching {
            stage.deleteRecursively()
            if (!stage.mkdirs()) error("建不了暂存目录")
        }.getOrElse {
            runCatching { tmp.delete() }
            return Install.Failed("手机存储空间不足，装不下这个包")
        }

        val unpacked = runCatching { unzip(tmp, stage) }.getOrElse { t ->
            Log.w(TAG, "解压失败", t)
            runCatching { stage.deleteRecursively() }
            runCatching { tmp.delete() }
            return Install.Failed("这个包解不开，可能没下完")
        }
        runCatching { tmp.delete() }
        if (!unpacked) {
            runCatching { stage.deleteRecursively() }
            return Install.Failed("包里没有 manifest.json，不像是扩展包")
        }

        val contents = runCatching { parsePack(stage) }.getOrNull()
        if (contents == null) {
            runCatching { stage.deleteRecursively() }
            return Install.Failed("包里的 manifest.json 或 npcs.json 读不出来")
        }
        val minApp = contents.minAppVersion
        if (minApp.isNotEmpty() && compareVersion(BuildConfig.VERSION_NAME, minApp) < 0) {
            runCatching { stage.deleteRecursively() }
            return Install.Failed(
                "这个包要求 App 版本 $minApp 以上，当前是 ${BuildConfig.VERSION_NAME}，请先更新 App",
            )
        }
        // ⚠ v2.73.13：只有**声明了 npcs 模块**的包才要求至少一个 NPC。
        //   扩展包拆成分片后，「商店包」「作物包」压根没有 NPC 数据 ——
        //   那不是坏包，是这个包不管这块。原来一刀切「没 NPC 就不装」会把分片全拒掉。
        if (contents.modules.contains("npcs") && contents.npcs.isEmpty()) {
            runCatching { stage.deleteRecursively() }
            return Install.Failed("这个包说是带村民的，却一个都没解析出来，先别装")
        }
        // 头像缺失只降级成「没头像」，不该让整包装不上——数据本身是好的。
        contents.npcs.firstOrNull { it.portraitPath != null && !portraitExists(stage, it.portraitPath!!) }
            ?.let { Log.w(TAG, "头像缺失，降级为无头像: ${it.name}") }

        // 换正式目录。manifest 里的 modId 才是权威（方式 B 的占位 id 可能与它不同）。
        val dest = packDir(ctx, contents.modId)
        runCatching { dest.deleteRecursively() }
        val moved = runCatching { if (stage.renameTo(dest)) true else false }.getOrDefault(false)
        if (!moved) {
            // renameTo 跨卷时会失败，退化成复制。
            val copied = runCatching {
                dest.mkdirs()
                stage.copyRecursively(dest, overwrite = true)
                stage.deleteRecursively()
                true
            }.getOrDefault(false)
            if (!copied) {
                runCatching { stage.deleteRecursively() }
                return Install.Failed("存不进 App 目录，请检查手机剩余空间")
            }
        }
        Portraits.clear(contents.modId)
        CropIcons.clear(contents.modId)
        ItemIcons.clear(contents.modId)
        writePkgMeta(dest, pack)
        val pkgVer = contents.pkgVersion.ifBlank { pack.version }
        Log.i(
            TAG,
            "已装 ${contents.modId} 包版本 $pkgVer（含 Mod v${contents.version}）：${contents.npcs.size} 个 NPC",
        )
        // ⚠ 这里**不能**无脑写 pack.version（index.json 的号）：manifest.packageVersion 才是权威，
        //   两者不一致时以包内声明为准——否则「更新检查对比 packageVersion」这条要求
        //   在装包这一步就被清单号顶掉了。
        return Install.Ok(contents.copy(pkgVersion = pkgVer).toInstalled())
    }

    /** 彻底删掉：删目录 + 清头像/作物图/物品图缓存（是否先摘启用由调用方负责）。 */
    fun uninstall(ctx: Context, modId: String) {
        runCatching { packDir(ctx, modId).deleteRecursively() }
        Portraits.clear(modId)
        CropIcons.clear(modId)
        ItemIcons.clear(modId)
    }

    // ── 读已装包 ───────────────────────────────────────────────

    /**
     * 扫盘读出全部已装包。启动与进页面各调一次即可。
     *
     * ⚠ 目录在但内容坏掉时（用户手动删了文件 / 装包中途被杀）**跳过这一条**，
     *   而不是抛异常——一个坏包不该让其它包一起消失。
     */
    fun loadInstalled(ctx: Context): List<Installed> {
        val kids = runCatching { modsDir(ctx).listFiles() }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Installed>()
        for (f in kids) {
            if (!f.isDirectory || f.name.startsWith(".")) continue // .stage-* 是暂存目录
            // parsePack 内部已经逐份 runCatching，这里再包一层兜住「目录权限异常」那类意外
            runCatching { parsePack(f)?.toInstalled() }.getOrNull()?.let(out::add)
        }
        return out.sortedBy { it.modId }
    }

    /** 内存里的已装列表。启动扫一次，之后每次装/删都刷新——UI 读它，不重复扫盘。 */
    @Volatile
    private var installedCache: List<Installed> = emptyList()

    fun installed(): List<Installed> = installedCache

    /** 重新扫盘并刷新内存列表，返回新列表。 */
    fun refreshInstalled(ctx: Context): List<Installed> =
        loadInstalled(ctx).also { installedCache = it }

    /** 把「已装哪些」+「启用哪些」交给 [GameData] 重算合并结果。启用集合来自偏好（设备态）。 */
    fun applyEnabled(enabled: Set<String>) {
        GameData.applyMods(installedCache, enabled)
    }

    // ── 包内解析 ───────────────────────────────────────────────

    private val SEASON_CN = mapOf(
        "spring" to "春", "summer" to "夏", "fall" to "秋", "autumn" to "秋", "winter" to "冬",
        "春" to "春", "夏" to "夏", "秋" to "秋", "冬" to "冬",
    )

    private fun JSONArray.strList(): List<String> =
        (0 until length()).map { optString(it, "") }.filter { it.isNotEmpty() }

    /** 包内一条概要行程（schedules.json）。**不是**精确到分钟的时段表，见 [parseSchedules]。 */
    private data class Schedule(val summary: String, val locations: List<String>)

    /**
     * 包内一个 SVE 作物（crops.json）。
     *
     * ⚠ 季节在这里就转成**中文**（[Crop.seasons] 的口径），不在 UI 层转。
     *   原版 crops.json 用中文季节，而包内用英文（summer/fall/…）；而 `Calc.plantResults`
     *   与今日页播种死线都按 `seasonName in crop.seasons` 过滤（传的是中文）。
     *   留着英文的话每个 SVE 作物在所有季节筛选里**全部消失**——不报错，只是安静地不见了。
     */
    data class ModCrop(
        val modId: String,
        val seed: String,
        val name: String,
        val harvest: String,
        val seasons: List<String>,
        val growDays: Int?,
        val regrowDays: Int?,
        val raisedBed: Boolean?,
        /** 包内 `icon`（`images/crops/xxx.png`）。经 [safeNameOf] 过滤，不是包内路径则为 null。 */
        val icon: String?,
        val note: String,
    ) {
        /**
         * 转成图鉴/计算页通用的 [Crop]。
         *
         * 字段取舍说明：
         * - `sellPrice`/`seedPrice` 包里**没有**，给 null（图鉴不显示价格行，而不是显示 0g）；
         * - `channels` 给「{seed}」，让图鉴有渠道行可写，也让 `sourceHint` 那类按渠道
         *   推断来源的地方有值；`seedName` 另存一份供 UI 直接显示「种子」行；
         * - `giant=false`、`trellis=false`：包里没有这两个概念，别编。
         */
        fun toCrop(): Crop = Crop(
            name = name,
            seedPrice = null,
            sellPrice = null,
            growth = growDays,
            // 包里 -1 表示一次性收获；Crop.regrow 的原版口径是「无此键 → null」
            regrow = regrowDays?.takeIf { it > 0 },
            seasons = seasons,
            channels = seed,
            note = note,
            giant = false,
            trellis = false,
            modId = modId,
            seedName = seed,
            harvestName = harvest.ifEmpty { null },
            raisedBed = raisedBed,
            iconPath = icon,
        )
    }

    /** 包内英文季节 → 原版 crops.json 的中文口径。**未列出的值一律丢弃**（不猜）。 */
    private val CROP_SEASON_CN = mapOf(
        "spring" to "春", "summer" to "夏",
        "fall" to "秋", "autumn" to "秋", "winter" to "冬",
        "春" to "春", "夏" to "夏", "秋" to "秋", "冬" to "冬",
    )

    /**
     * 包内一条料理配方（recipes.json）。
     *
     * ⚠ 包内 `ingredients` 是**字符串数组**（"美洲大树莓×15"），原版 recipes.json
     *   用的是对象数组（`{name, count}`）。这里**在数据层就转成 (名, 数量)**，
     *   UI 层因此只有一套显示格式，不用为 Mod 料理单开一条渲染路径。
     * ⚠ `unlock` 空 = 没有好感门槛（自学 / 商店买）。**空值不是缺数据**，
     *   所以解析成空串而不是 null——UI 据此显示「自学 / 购买」而不是空白。
     */
    data class ModRecipe(
        val modId: String,
        val name: String,
        val ingredients: List<Pair<String, Int>>,
        val unlock: String,
        /**
         * 包内图标相对路径（`images/recipes/xxx.png`）。经 [safeNameOf] 过滤。
         * null = 包里没给图，UI 退回原版图标框（空框 + 首字占位）。
         */
        val icon: String?,
    )

    /**
     * 解析 `recipes.json`（v2.73.7 新增的可选文件）。
     *
     * 与 [parseCrops] / [parseSchedules] 同款纪律：文件不在 → 空表；
     * 根键缺失 → 空表；单条脏数据跳过不拖累整份。
     */
    private fun parseRecipes(dir: File, modId: String): List<ModRecipe> {
        val f = File(dir, "recipes.json")
        if (!f.isFile) return emptyList()
        val root = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("recipes") ?: return emptyList()
        val out = mutableListOf<ModRecipe>()
        for (i in 0 until arr.length()) {
            runCatching {
                val o = arr.getJSONObject(i)
                val name = o.optString("name").trim()
                if (name.isEmpty()) return@runCatching
                out += ModRecipe(
                    modId = modId,
                    name = name,
                    ingredients = o.optJSONArray("ingredients")?.let { a ->
                        (0 until a.length()).mapNotNull { j ->
                            a.optString(j).trim().takeIf { it.isNotEmpty() }?.let { parseIngredient(it) }
                        }
                    } ?: emptyList(),
                    unlock = o.optString("unlock").trim(),
                    // 图路径与头像/作物图同款纪律：必须过 safeNameOf（crops.json 是别人写的，
                    // 一个 ../ 就够读到 App 私有目录外）。包里没这个键时给 null。
                    icon = o.optString("icon").trim()
                        .takeIf { it.isNotEmpty() && safeNameOf(it) != null },
                )
            }
        }
        return out
    }

    /**
     * 把包内的一条材料串拆成 (名, 数量)。
     *
     * 形态是 `名称×数量`（全角乘号）。**用 lastIndexOf 切**而不是 indexOf：
     * 名称本身含「×」时（例如「A×B×2」）indexOf 会把名字切一半。
     * 右边不是数字就整串当名称、数量 1——宁可少一个数量，也不把名字切坏。
     */
    private fun parseIngredient(raw: String): Pair<String, Int> {
        val i = raw.lastIndexOf('×')
        if (i <= 0) return raw to 1
        val name = raw.substring(0, i).trim()
        val cnt = raw.substring(i + 1).trim().toIntOrNull()
        if (name.isEmpty() || cnt == null || cnt <= 0) return raw to 1
        return name to cnt
    }

    /**
     * 包内一条 SVE 鱼（fish.json）。
     *
     * ## 两代包字段不同
     * - 老包（v2.5~v2.9 / 分片 v1.0）：只有 `name / id / where`（v2.7+ 加 `icon`），
     *   **没有季节/天气/时段/难度** —— 所以那些字段为空，图鉴里不显示（**不许编**）。
     * - 新包：`name / id / aliases / difficulty / time / seasons / weather / location`，
     *   字段全的鱼才能参与季节/天气/难度筛选。
     *
     * @param difficulty 钓鱼难度。**null = 不可钓**（采集物，如红藻），
     *   但只有新包才带这个字段；老包没有它，不能拿 null 当"采集物"（见 [isForage]）。
     * @param seasons 已转成中文口径（春/夏/秋/冬/全年），与原版鱼筛选同一套。
     * @param weather 已转成中文口径（任意/晴天/雨天）。
     */
    data class ModFish(
        val modId: String,
        val name: String,
        /** 包内 id（如 Butterfish）。溯源用；显示一律走中文名。 */
        val id: String,
        /** 钓点概要。老包用 `where`，新包用 `location` —— 两者都收，展示时 location 优先。 */
        val where: String,
        /** 别名（不同汉化补丁译名不同，图鉴里要一起显示「黄油鱼（又名：鲳鱼）」）。 */
        val aliases: List<String> = emptyList(),
        /** 钓鱼难度。null = 不可钓 / 包里没给。 */
        val difficulty: Int? = null,
        /** 可钓时间段（如 "6:00-2:00"）。 */
        val time: String = "",
        /** 季节（中文口径：春/夏/秋/冬/全年）。 */
        val seasons: List<String> = emptyList(),
        /** 天气（中文口径：任意/晴天/雨天）。 */
        val weather: String = "",
        /** 钓点（新包的 `location` 字段）。 */
        val location: String = "",
        /** 包内图标相对路径（`images/fish/xxx.png`）。null = 包里没给图。 */
        val icon: String?,
        /**
         * 是不是**采集物**（不是钓的）。
         *
         * ⚠ 判据不能是「difficulty == null」：老包**根本没有** difficulty 字段，
         *   它的 null 是"不知道"不是"不可钓"——把不知道说成否，会把老包里的鱼
         *   全判成采集物。真正的判据是**包里明确写了 difficulty 且为 null**
         *   （解析时确定），或老包自己写的「非钓鱼」措辞。
         */
        val isForage: Boolean = false,
    ) {
        /** 钓点展示：新包的 location 优先，老包的 where 兜底。 */
        val spotText: String get() = location.ifBlank { where }
    }

    /**
     * 包内一个 SVE 商店概要（shops.json）。
     *
     * ⚠ 这是**概要**不是完整商店表：只有店主/位置/商品数/类别，没有商品清单。
     *   包 note 写明「商品数为 mod 内条目数；具体商品以游戏内商店为准」——
     *   所以 UI 只能说「这个店有 N 件商品」，不能列出卖什么。
     */
    data class ModShop(
        val modId: String,
        val name: String,
        /** 店主（如「索菲娅」）。包里没写（家具目录）时为空串。 */
        val owner: String,
        /** 位置（如「城堡村」）。包里没写时为空串。 */
        val location: String,
        /** 商品数（mod 内条目数）。null = 包里没给这个数。 */
        val items: Int?,
        /** 商品类别概要（如「武器、装备、补给」）。包里没写时为空串。 */
        val sells: String,
    )

    /**
     * 解析 `fish.json`（v2.73.9 新增的可选文件）。
     *
     * 与 [parseCrops] / [parseRecipes] 同款纪律：文件不在 → 空表；单条脏数据跳过。
     *
     * ⚠ **剔除非鱼条目**：包里那条「毒蘑菇」的 `where` 写的是「采集非钓鱼」——
     *   它是采集物不是鱼，进钓鱼图鉴是错的。判据用**包里自己的措辞**（`where` 含
     *   「非钓鱼」），不靠名字猜。
     */
    private fun parseFish(dir: File, modId: String): List<ModFish> {
        val f = File(dir, "fish.json")
        if (!f.isFile) return emptyList()
        val root = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("fish") ?: return emptyList()
        val out = mutableListOf<ModFish>()
        for (i in 0 until arr.length()) {
            runCatching {
                val o = arr.getJSONObject(i)
                val name = o.optString("name").trim()
                if (name.isEmpty()) return@runCatching
                val where = o.optString("where").trim()
                val location = o.optString("location").trim()
                // ⚠ 采集物判定：**包里明确写了 difficulty 且为 null** 才算。
                //   老包根本没有 difficulty 字段 —— 它的"没有"是"不知道"，
                //   不能当成"不可钓"（那会把老包里的鱼全判成采集物）。
                val hasDiff = o.has("difficulty")
                val isForage = if (hasDiff) o.isNull("difficulty") else where.contains("非钓鱼")
                val aliases = o.optJSONArray("aliases")?.let { a ->
                    (0 until a.length())
                        .mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
                } ?: emptyList()
                val seasons = o.optJSONArray("seasons")?.let { a ->
                    (0 until a.length()).map { seasonCn(a.optString(it)) }
                        .filter { it.isNotEmpty() }.distinct()
                } ?: emptyList()
                out += ModFish(
                    modId = modId,
                    name = name,
                    id = o.optString("id").trim(),
                    where = where,
                    aliases = aliases,
                    difficulty = if (hasDiff) o.optInt("difficulty", -1).takeIf { it >= 0 } else null,
                    time = o.optString("time").trim(),
                    seasons = seasons,
                    weather = weatherCn(o.optString("weather").trim()),
                    location = location,
                    icon = o.optString("icon").trim()
                        .takeIf { it.isNotEmpty() && safeNameOf(it) != null },
                    isForage = isForage,
                )
            }
        }
        return out
    }

    /**
     * 读 `fish.json` 顶层的 `noLegendary` 说明（v2.75.1）。
     *
     * 那是**包作者对"这个包没有传奇鱼"的自述**，图鉴顶部要照着说
     * （"SVE 没有新增鱼王，最难的是 XX"），**不能自己编**——
     * 编了就是把"包里没写"说成"确实没有"。
     * 文件不在 / 字段不在 → 空串（那句话就不显示）。
     */
    private fun parseFishNote(dir: File): String {
        val f = File(dir, "fish.json")
        if (!f.isFile) return ""
        val root = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return ""
        return root.optString("noLegendary").trim()
    }

    /** 包里的季节是英文（spring/summer/fall/winter），转成原版鱼同一套中文口径。 */
    private fun seasonCn(s: String): String = when (s.trim().lowercase()) {
        "spring" -> "春"
        "summer" -> "夏"
        "fall", "autumn" -> "秋"
        "winter" -> "冬"
        "all", "allseasons", "year" -> "全年"
        else -> s.trim()
    }

    /** 包里的天气是英文（sunny/rainy/both），转成原版鱼同一套中文口径。 */
    private fun weatherCn(s: String): String = when (s.trim().lowercase()) {
        "sunny" -> "晴天"
        "rainy" -> "雨天"
        "both", "any", "all" -> "任意"
        else -> s.trim()
    }

    /**
     * 解析 `shops.json`（v2.73.9 新增的可选文件）。
     *
     * 同款纪律：文件不在 → 空表；单条脏数据跳过。
     */
    private fun parseShops(dir: File, modId: String): List<ModShop> {
        val f = File(dir, "shops.json")
        if (!f.isFile) return emptyList()
        val root = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("shops") ?: return emptyList()
        val out = mutableListOf<ModShop>()
        for (i in 0 until arr.length()) {
            runCatching {
                val o = arr.getJSONObject(i)
                val name = o.optString("name").trim()
                if (name.isEmpty()) return@runCatching
                out += ModShop(
                    modId = modId,
                    name = name,
                    owner = o.optString("owner").trim(),
                    location = o.optString("location").trim(),
                    items = if (o.has("items")) o.optInt("items", 0) else null,
                    sells = o.optString("sells").trim(),
                )
            }
        }
        return out
    }

    /**
     * 包内一位可结婚 NPC 的红心事件链概要（heart_events.json）。
     *
     * ⚠ 这是**概要**不是逐事件剧情：包里给的是「2/4/6/8/10 心各在哪个地点触发」的
     *   一段话（[triggers]），**没有**原版 heartEvents.json 那种
     *   `events:[{hearts,place,event,options}]` 的逐条剧情。
     *   所以 UI 只能给「心数节点 + 触发条件概要」，**不能编剧情对话**。
     *
     * @param id 包内主键（与 npcs.json 的 id 一致，如 "Lance"）。关联用**主键**不用中文名
     *   ——包作者改显示名不该静默丢掉整条红心事件链。
     * @param hearts 节点心数（实测一律 [2,4,6,8,10]）。
     * @param triggers 触发条件概要（一整段文本，形如「2心高地前哨，4心城堡村，…」）。
     */
    data class ModHeartEvent(
        val modId: String,
        val id: String,
        val name: String,
        val hearts: List<Int>,
        val triggers: String,
    )

    /**
     * 解析 `heart_events.json`（v2.73.10 新增的可选文件）。
     *
     * 与其它可选文件同款纪律：文件不在 → 空表；单条脏数据跳过。
     *
     * ⚠ 顶层还有个 `postMarriage`（婚后说明，**整包一份**不是每人一份）——
     *   单独取出来，不塞进每一条里重复 7 遍。
     */
    private fun parseHeartEvents(dir: File, modId: String): Pair<List<ModHeartEvent>, String> {
        val f = File(dir, "heart_events.json")
        if (!f.isFile) return emptyList<ModHeartEvent>() to ""
        val root = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return emptyList<ModHeartEvent>() to ""
        val arr = root.optJSONArray("heartEvents") ?: return emptyList<ModHeartEvent>() to ""
        val out = mutableListOf<ModHeartEvent>()
        for (i in 0 until arr.length()) {
            runCatching {
                val o = arr.getJSONObject(i)
                val id = o.optString("id").trim()
                val name = o.optString("name").trim()
                if (id.isEmpty() && name.isEmpty()) return@runCatching
                out += ModHeartEvent(
                    modId = modId,
                    id = id,
                    name = name,
                    hearts = o.optJSONArray("hearts")?.let { a ->
                        (0 until a.length()).mapNotNull { a.optInt(it, -1).takeIf { h -> h > 0 } }
                    } ?: emptyList(),
                    triggers = o.optString("triggers").trim(),
                )
            }
        }
        return out to root.optString("postMarriage").trim()
    }

    /**
     * 包内一个 SVE 主要地点（locations.json）。
     *
     * ⚠ [who] 是**自由描述文字**（如「索菲娅家，酿酒/售酒」），**不是结构化的 NPC 列表**。
     *   所以「关联 NPC」只能靠**子串精确匹配包内 NPC 名单**（见 [GameData.locationsOf]），
     *   命中不了就不关联 —— 绝不从文字里**猜**谁相关。
     */
    data class ModLocation(
        val modId: String,
        val id: String,
        val name: String,
        /** 这个地点是干什么的 / 谁的地盘（包内原文）。 */
        val who: String,
    )

    /**
     * 解析 `locations.json`（v2.73.11 新增的可选文件）。
     *
     * 同款纪律：文件不在 → 空表；单条脏数据跳过。
     * 顶层还有个 `festivals`（SVE 给原版节日加的新剧情，**整包一份**）——
     * 单独取出来，不塞进每条重复 20 遍。
     */
    private fun parseLocations(dir: File, modId: String): Pair<List<ModLocation>, String> {
        val f = File(dir, "locations.json")
        if (!f.isFile) return emptyList<ModLocation>() to ""
        val root = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return emptyList<ModLocation>() to ""
        val arr = root.optJSONArray("locations") ?: return emptyList<ModLocation>() to ""
        val out = mutableListOf<ModLocation>()
        for (i in 0 until arr.length()) {
            runCatching {
                val o = arr.getJSONObject(i)
                val name = o.optString("name").trim()
                if (name.isEmpty()) return@runCatching
                out += ModLocation(
                    modId = modId,
                    id = o.optString("id").trim(),
                    name = name,
                    who = o.optString("who").trim(),
                )
            }
        }
        return out to root.optString("festivals").trim()
    }

    /**
     * 解析 `crops.json`（v2.73.4 新增的可选文件）。
     *
     * 与 [parseSchedules] 同款纪律：文件不在 → 空表；单条脏数据跳过不拖累整份。
     * 收获物名与作物名相同时给空串（UI 据此省略「收获」行，不重复显示一遍名字）。
     */
    private fun parseCrops(dir: File, modId: String): List<ModCrop> {
        val f = File(dir, "crops.json")
        if (!f.isFile) return emptyList()
        val root = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("crops") ?: return emptyList()
        val out = mutableListOf<ModCrop>()
        for (i in 0 until arr.length()) {
            runCatching {
                val o = arr.getJSONObject(i)
                val name = o.optString("crop").trim()
                if (name.isEmpty()) return@runCatching
                val harvestRaw = o.optString("harvest").trim()
                val regrow = o.optInt("regrowDays", -1)
                out += ModCrop(
                    modId = modId,
                    seed = o.optString("seed").trim(),
                    name = name,
                    // 收获物与作物同名时置空串：图鉴里不必把同一个名字显示两遍
                    harvest = if (harvestRaw == name) "" else harvestRaw,
                    seasons = o.optJSONArray("seasons")?.strList()
                        ?.mapNotNull { CROP_SEASON_CN[it.lowercase()] }
                        ?.distinct()
                        ?: emptyList(),
                    growDays = o.optInt("growDays", 0).takeIf { it > 0 },
                    regrowDays = regrow.takeIf { it > 0 },
                    raisedBed = if (o.has("raisedBed")) o.optBoolean("raisedBed") else null,
                    // 图路径与头像同款纪律：必须过 safeNameOf 才认，否则一个 "../" 就够读到
                    // App 私有目录外。包里没这个键（v2.4 及更早的 crops.json）时给 null，
                    // UI 退回原版图标框——**不是**编一张图出来。
                    icon = o.optString("icon").trim()
                        .takeIf { it.isNotEmpty() && safeNameOf(it) != null },
                    note = o.optString("note").trim(),
                )
            }
        }
        return out
    }

    /**
     * 解析 `schedules.json`（v2.73.1 新增的可选文件）。
     *
     * ## 可选而非必需
     * v2.0/v2.2 的包没有这个文件，装着老包的用户升级 App 后不该看到「行程数据损坏」——
     * 文件不在就返回空表，详情页不渲染行程卡而已，其余功能照常。**不是**把整包判为损坏。
     *
     * ## 概要版的数据现实
     * 包里每条只有 `id / summary / locations` 三项，**没有按天/按季的时段结构**——
     * mod 日程注释质量参差，包作者只整理了「白天去哪、晚上回哪」。
     * 所以「当天位置」在 App 侧只能是：summary 全文 + 常去地点清单，
     * 外加对地点条目里显式写的季节后缀（如「安迪农场(春)」）做当季标注。
     * 别假装有更细的数据（按小时切分之类），那种表得等包作者升级数据结构。
     *
     * 按 id 建 map：一条脏 entry 跳过（runCatching 单条），不拖累整个文件。
     */
    private fun parseSchedules(dir: File): Map<String, Schedule> {
        val f = File(dir, "schedules.json")
        if (!f.isFile) return emptyMap()
        val root = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyMap()
        val arr = root.optJSONArray("schedules") ?: return emptyMap()
        val out = mutableMapOf<String, Schedule>()
        for (i in 0 until arr.length()) {
            runCatching {
                val o = arr.getJSONObject(i)
                val sid = o.optString("id").trim()
                if (sid.isEmpty()) return@runCatching
                out[sid] = Schedule(
                    summary = o.optString("summary").trim(),
                    locations = o.optJSONArray("locations")?.strList() ?: emptyList(),
                )
            }
        }
        return out
    }

    private fun parsePack(dir: File): Contents? {
        val mf = File(dir, "manifest.json")
        if (!mf.isFile) return null
        val mo = runCatching { JSONObject(mf.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        val id = mo.optString("modId").trim()
        if (!ID_PATTERN.matches(id)) return null
        val npcFile = File(dir, "npcs.json")
        // ⚠ v2.73.13：npcs.json 不再是**骨架**（必需文件）。
        //   扩展包拆成多个分片后，「商店包」「作物包」本来就没有 NPC 数据 ——
        //   那不是坏包，是这个包不管这块。骨架只看 manifest.json（包的身份证）。
        //   原来「缺 npcs.json 就不是扩展包」的判据会让**所有分片都装不上**。
        val root = if (npcFile.isFile) {
            runCatching { JSONObject(npcFile.readText(Charsets.UTF_8)) }.getOrNull()
        } else null
        val schedules = parseSchedules(dir)
        val crops = parseCrops(dir, id)
        val recipes = parseRecipes(dir, id)
        val fish = parseFish(dir, id)
        val fishNote = parseFishNote(dir)
        val shops = parseShops(dir, id)
        val (heartEvents, postMarriage) = parseHeartEvents(dir, id)
        val (locations, festivals) = parseLocations(dir, id)
        val arr = root?.optJSONArray("npcs") ?: JSONArray()
        val npcs = mutableListOf<GiftVillager>()
        for (i in 0 until arr.length()) {
            // 逐个 runCatching：一条脏数据不该让整包都装不上。
            runCatching { npcFromJson(arr.getJSONObject(i), id, schedules) }.getOrNull()?.let(npcs::add)
        }
        // 声称数与实际不符时按实际走，并在日志留痕——用户看到的是真装了几个，
        // 而不是 manifest 里那个跟实际不符的宣称值。
        val declared = mo.optInt("npcCount", 0)
        if (declared > 0 && declared != npcs.size) {
            Log.w(TAG, "$id 声称 $declared 个 NPC，实际解析 ${npcs.size} 个，按实际走")
        }
        return Contents(
            modId = id,
            name = mo.optString("modName").ifBlank { mo.optString("modNameEn").ifBlank { id } },
            version = mo.optString("modVersion"),
            tag = mo.optString("shortName"),
            author = mo.optString("author"),
            description = mo.optString("description"),
            updatedAt = mo.optString("updatedAt"),
            minAppVersion = mo.optString("minAppVersion"),
            modules = mo.optJSONArray("modules")?.strList() ?: emptyList(),
            npcs = npcs,
            hiddenCount = mo.optInt("hiddenNpcCount", 0),
            portraitCount = mo.optInt("portraitCount", 0),
            dir = dir,
            crops = crops,
            recipes = recipes,
            fish = fish,
            fishNoLegendary = fishNote,
            shops = shops,
            heartEvents = heartEvents,
            postMarriage = postMarriage,
            locations = locations,
            festivals = festivals,
            // v2.73：包版本**优先取 manifest 自己的 packageVersion**，回退到装包时写下的
            //   package.json（那是 index.json 给的号）。理由：manifest.packageVersion 由包作者
            //   声明、跟着 zip 一起走，而 index.json 是别人维护的清单——两者可能不一致，
            //   而「有没有新版」这件事该由包自己说了算（用户明确要求对比它，不要用 modVersion）。
            //   v2.0 的包没有这个键，回退逻辑让老包照样能更新检测。
            pkgVersion = mo.optString("packageVersion").ifBlank {
                readPkgMeta(dir)?.optString("pkgVersion").orEmpty()
            },
        )
    }

    /**
     * 装包时写下的 `package.json`：**只记清单给的包版本与来源**，不重复 manifest 里已有的东西
     * （modName / modVersion 那些由 manifest.json 单源负责，抄一份就是第二份真相）。
     *
     * `pkgVersion` 记的是**扩展包版本号**（= manifest 的 `packageVersion`，清单里那个 `version`
     * 也应是同一个号）。manifest 自带该键时以它为准，这里只是给老包留的回退来源。
     */
    private fun writePkgMeta(dir: File, pack: PackMeta) {
        runCatching {
            File(dir, "package.json").writeText(
                JSONObject()
                    .put("pkgVersion", pack.version)
                    .put("sourceUrl", pack.downloadUrl)
                    .put("installedAt", System.currentTimeMillis())
                    .toString(),
                Charsets.UTF_8,
            )
        }
    }

    private fun readPkgMeta(dir: File): JSONObject? {
        val f = File(dir, "package.json")
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun npcFromJson(o: JSONObject, modId: String, schedules: Map<String, Schedule> = emptyMap()): GiftVillager {
        val srcId = o.optString("id").trim()
        val name = o.optString("nameCn").trim().ifEmpty { srcId }
        if (name.isEmpty()) error("既没有中文名也没有 id")
        val b = o.optJSONObject("birthday")
        val seasonRaw = b?.optString("season").orEmpty()
        val day = b?.optInt("day", 0) ?: 0
        // 头像路径必须留在包内：npcs.json 是别人写的，一个 ../ 就够读到 App 私有目录外。
        val portrait = o.optString("portrait").trim().takeIf { it.isNotEmpty() && safeNameOf(it) != null }
        // 行程按 **srcId** 关联而不是中文名：两个键各管一头（id 是包内主键，中文名是显示键），
        // 用显示键关联会在包作者改中文名时静默丢掉行程——而包改名不需要 App 配合。
        val sched = schedules[srcId]
        return GiftVillager(
            name = name,
            birthdaySeason = SEASON_CN[seasonRaw.lowercase()],
            birthdayDay = day.takeIf { it in 1..28 },
            // ⚠ 五段全部走 `?.` + `?:`：Android 的 optJSONArray 返回**平台类型**，编译器不拦，
            //   直接 `.strList()` 会在「包里没有该段」时 NPE——而包是别人写的、随时可能只给三段。
            //   v2.0 的包就只有 lovedGifts；v2.2 起才有完整五段。缺段就空着，不编。
            loves = o.optJSONArray("lovedGifts")?.strList() ?: emptyList(),
            likes = o.optJSONArray("likedGifts")?.strList() ?: emptyList(),
            dislikes = o.optJSONArray("dislikedGifts")?.strList() ?: emptyList(),
            hates = o.optJSONArray("hatedGifts")?.strList() ?: emptyList(),
            neutrals = o.optJSONArray("neutralGifts")?.strList() ?: emptyList(),
            modId = modId,
            srcId = srcId.ifEmpty { null },
            portraitPath = portrait,
            location = o.optString("location").trim().ifEmpty { null },
            marriageable = if (o.has("marriageable")) o.optBoolean("marriageable") else null,
            // v2.73.1：行程。包里没有 schedules.json（v2.0/v2.2 老包）时 sched 为 null → 两个空值。
            scheduleSummary = sched?.summary?.ifEmpty { null },
            scheduleLocations = sched?.locations ?: emptyList(),
        )
    }

    // ── zip 解包 ───────────────────────────────────────────────

    /**
     * 把 zip 解到 [dest]。只放**白名单里的两类**：根级 JSON，以及 images 目录下的 png。
     *
     * 三道闸门（缺一道都可能让一个恶意或损坏的包写到目录外）：
     * ① 条目名过 [safeNameOf]：挡 `../`、绝对路径、反斜杠；     * ② 条目数、单条体量、总量各有上限；
     * ③ 落盘前再比一次规范化路径确实在 dest 之下（对符号链接与怪大小写的兜底）。
     *
     * @return 是否解出了 manifest.json（false = 这压根不是扩展包）
     */
    private fun unzip(zip: File, dest: File): Boolean {
        val root = dest.canonicalFile
        var count = 0
        var total = 0L
        var hasManifest = false
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var e: ZipEntry? = zin.nextEntry
            while (e != null) {
                val entry = e
                e = null
                count++
                if (count > MAX_ENTRIES) error("包里的文件条目过多")
                // 不认识的条目跳过而不是报错：包作者多塞个 README 不该导致装不上。
                val name = safeNameOf(entry.name)
                if (name != null && !entry.isDirectory) {
                    if (entry.size > MAX_ENTRY_BYTES) error("包里有单个过大的文件")
                    val out = File(root, name)
                    if (!out.canonicalFile.path.startsWith(root.path + File.separator)) {
                        error("包里有越界路径")
                    }
                    out.parentFile?.mkdirs()
                    var written = 0L
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = zin.read(buf)
                            if (n <= 0) break
                            written += n
                            total += n
                            if (written > MAX_ENTRY_BYTES || total > PACK_MAX_BYTES) {
                                error("包体超出上限")
                            }
                            fos.write(buf, 0, n)
                        }
                    }
                    if (name == "manifest.json") hasManifest = true
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        return hasManifest
    }

    /** 条目名安全化：不合格返回 null（调用方跳过该条目）。 */
    private fun safeNameOf(raw: String): String? {
        if (raw.isEmpty() || raw.length > 200) return null
        if (raw.contains('\\') || raw.startsWith("/") || raw.contains(':')) return null
        val parts = raw.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        val top = parts.first()
        val okJson = parts.size == 1 && top.endsWith(".json")
        val okImage = parts.size in 2..3 && top == "images" && parts.last().endsWith(".png")
        if (!okJson && !okImage) return null
        return parts.joinToString("/")
    }

    private fun portraitExists(dir: File, rel: String): Boolean {
        val safe = safeNameOf(rel) ?: return false
        return File(dir, safe).isFile
    }

    // ── 网络 ───────────────────────────────────────────────────

    private fun open(u: String): HttpURLConnection =
        (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            // 包比版本清单大得多，读取超时给到 60 秒；不设的话慢网必假失败。
            readTimeout = 60000
            useCaches = false
            instanceFollowRedirects = false // gitee raw 跨主机 302，手动跟
            setRequestProperty("User-Agent", "StardewHelper/${BuildConfig.VERSION_CODE} mod-fetch")
        }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /** 跟完重定向再读。与 [UpdateRepo] 同一套 302 策略——复制第二份循环 = gitee 改一次行为只坏一个。 */
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

    /**
     * 取字节并卡上限。
     *
     * ⚠ 不看 contentLength 也不够：gitee 出错页/网关拦截页可能带 200 与一个像模像样的长度，
     * 所以真正拦的是**读完之后的体积**与调用方那两道形态校验。
     */
    private fun fetchBytes(u: String, max: Long): ByteArray =
        withRedirects(u) { conn ->
            conn.inputStream.use { it.readBounded(max) }
        }

    private fun InputStream.readBounded(max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n <= 0) break
            total += n
            if (total > max) error("响应体超出上限")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 下载 zip 到 [dst]。先写 `.part` 再改名——中断留下的半截文件不会被当成成品，
     * 下次点下载直接覆盖它。
     */
    private fun download(url: String, dst: File, onProgress: (Int) -> Unit) {
        dst.parentFile?.mkdirs()
        val part = File(dst.parentFile, dst.name + ".part")
        runCatching { part.delete() }
        withRedirects(url) { conn ->
            val declared = conn.contentLength.toLong().takeIf { it > 0 } ?: -1L
            // 声称的体量明显超上限就直接拒，别先下 64 MB 再说。
            if (declared > PACK_MAX_BYTES) error("这个包超过 ${PACK_MAX_BYTES / 1024 / 1024} MB，先别下")
            val known = declared in 1..PACK_MAX_BYTES
            onProgress(0)
            conn.inputStream.use { input ->
                FileOutputStream(part).use { fos ->
                    val buf = ByteArray(32 * 1024)
                    var got = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        got += n
                        if (got > PACK_MAX_BYTES) error("包体超出上限")
                        // 没有 content-length 时不给百分比：宁可停在「下载中」，也不报假进度。
                        if (known) {
                            val pct = (got * 100 / declared).toInt().coerceIn(0, 99)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                    // 收尾核对：声称多少就得收到多少。少了就是断流，必须自己发现——
                    // okhttp 多数情况会替你抛 unexpected end of stream，但**不能只靠它**：
                    // 有些中间设备会安静地提前收尾，而拿到一个半截 zip 的表现是
                    // 「解不开」「manifest 读不出来」这类把人带偏的错话。
                    if (known && got != declared) error("下载不完整（收到 $got，应为 $declared）")
                }
            }
        }
        if (part.length() <= 0L) { runCatching { part.delete() }; error("下到的内容是空的") }
        // zip 最小魔数 "PK\x03\x04"。HTML/GBK 错误页不带它，挡在这里最省事。
        if (!hasZipMagic(part)) { runCatching { part.delete() }; error("下到的不是 zip 文件") }
        if (!part.renameTo(dst)) {
            part.copyTo(dst, overwrite = true)
            runCatching { part.delete() }
        }
        onProgress(100)
    }

    private fun hasZipMagic(f: File): Boolean {
        val head = ByteArray(4)
        f.inputStream().use { if (it.read(head) != 4) return false }
        return head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            (head[2] == 3.toByte() || head[2] == 5.toByte() || head[2] == 7.toByte())
    }

    /**
     * 把失败归纳成用户看得懂的话。
     *
     * ⚠ **404 不说成「连不上网络」**：网络明明是通的，只是那个文件暂时不在，
     *   把用户引向 WiFi 比报错更坏。判据取 [withRedirects] 抛出的 "HTTP <code>"
     *   字面量，不碰异常类名（R8 会改名）。
     */
    private fun networkWhy(t: Throwable?): String {
        val raw = t?.message ?: return OFFLINE_HINT
        val http = if (raw.startsWith("HTTP ")) raw.removePrefix("HTTP ").trim() else ""
        return when {
            http == "404" -> "下载地址取不到（404），多半是仓库正在同步，稍后再试"
            http.isNotEmpty() -> "服务器回了 HTTP $http，稍后再试"
            raw.contains("超出上限") -> "这个包太大了，先换一个试试"
            raw.contains("下载不完整") || raw.contains("end of stream") ->
                "下载中途断了（已自动重试一次），再点一次试试；网络差的话换 WiFi 更稳"
            raw.contains("timeout") || raw.contains("timed out") -> "网络太慢没下完，稍后再试"
            raw.contains("下到的内容是空的") -> "这个包是空的，仓库多半没同步好"
            raw.contains("不是 zip 文件") -> "下到的不是扩展包（多半是仓库返回了错误页）"
            raw.contains("rename") || raw.contains("Permission denied") ->
                "存不进 App 目录，请检查手机剩余空间"
            // ⚠ 兜底**不许**再说「连不上网络」：这条分支意味着「出错了但还不知道错在哪」，
            //   照实说「再试一次」比让用户去查 WiFi 诚实（Log 里已经有真因）。
            else -> "没能装上这个包，再试一次；还是不行就换个包或稍后再来"
        }
    }

    private const val OFFLINE_HINT = "连不上网络，稍后再试（装扩展包需要联网，App 其它功能离线可用）"

    // ── 头像 ───────────────────────────────────────────────────

    /**
     * Mod 头像缓存。全量驻留（一张 64×64 ≈ 16 KB，一包十几张不到 1 MB），
     * 与 [IconCache] 同款并发容器；换包/删包时按 modId 整组清掉。
     */
    object Portraits {
        private val cache = ConcurrentHashMap<String, Bitmap>()

        /** 只查缓存不解码：UI 层先 peek 同步命中，miss 走 IO 线程异步解码（别在组合期读盘）。 */
        fun peek(modId: String, rel: String): Bitmap? = cache["$modId/$rel"]

        /** 从 [ctx] 的已装目录解码「第一格」。失败返回 null，UI 降级为原版图标框。 */
        fun load(ctx: Context, modId: String, rel: String): Bitmap? {
            val key = "$modId/$rel"
            cache[key]?.let { return it }
            val safe = safeNameOf(rel) ?: return null
            val f = File(packDir(ctx, modId), safe)
            if (!f.isFile) return null
            val bmp = runCatching { decodeFirstCell(f) }.getOrNull() ?: return null
            cache[key] = bmp
            return bmp
        }

        /** [modId] 为 null 时清全部（重装/全部删除时用）。 */
        fun clear(modId: String? = null) {
            if (modId == null) cache.clear() else cache.keys.removeIf { it.startsWith("$modId/") }
        }
    }

    // ── 包内物品图 ───────────────────────────────────────────────

    /**
     * v2.73.8：包内**单张物品图**缓存（料理图标用）。
     *
     * 与 [CropIcons] 分开：作物图是**成长阶段 sprite sheet**（要裁帧、找末个非空帧），
     * 料理图是**单张 16×16 物品图**（不裁帧）。两套解码规则混进一个函数里，
     * 早晚会有人传错参数把图裁坏——所以分两个对象，各有各的 clear。
     */
    object ItemIcons {
        private val cache = ConcurrentHashMap<String, Bitmap>()

        /** 只查缓存不解码：UI 层先 peek 同步命中，miss 走 IO 线程解码（别在组合期读盘）。 */
        fun peek(modId: String, rel: String): Bitmap? = cache["$modId/$rel"]

        /** 从 [ctx] 的已装目录解码物品图。失败返回 null，UI 退回原版图标框。 */
        fun load(ctx: Context, modId: String, rel: String): Bitmap? {
            val key = "$modId/$rel"
            cache[key]?.let { return it }
            val safe = safeNameOf(rel) ?: return null
            val f = File(packDir(ctx, modId), safe)
            if (!f.isFile) return null
            val bmp = runCatching { decodeItemIcon(f) }.getOrNull() ?: return null
            cache[key] = bmp
            return bmp
        }

        /** [modId] 为 null 时清全部（重装/全部删除时用）。 */
        fun clear(modId: String? = null) {
            if (modId == null) cache.clear() else cache.keys.removeIf { it.startsWith("$modId/") }
        }
    }

    /**
     * 解码一张包内物品图，并按整数倍**归一化到 48 宽**。
     *
     * ## 为什么归一化
     * App 内嵌的原版 81 道菜谱图标**全是 48×48**（实测），而 SVE 官方物品图是 16×16。
     * 同一格（26dp ≈ 78 物理像素）里，原版图是 48→78 的**降采样**（看着精细），
     * SVE 图是 16→78 的 **4.9 倍放大**（像素块粗得明显）。两边放一起会很不一样。
     *
     * ## 为什么在**解码时**放大而不是在打包时
     * 包里放**官方原始 16×16**（素材保真：用户日后可直接替换成新版 SVE 的图，
     * 不用再想「包里那张是不是放大过的」）。归一化是**显示层**的事，在 App 侧做。
     *
     * 只在宽度**不足** 48 时放大，且放大倍数取整（16→48 是 3 倍，32→48 不整除就不放）。
     * Nearest 放大（`filter=false`）——像素风要硬边，双线性会糊成一团。
     */
    private fun decodeItemIcon(f: File): Bitmap? {
        val src = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        if (src.width <= 0 || src.height <= 0) return null
        return normalizeIcon(src)
    }

    /**
     * 把一张解码出的图标**按整数倍最近邻放大**到 [ITEM_ICON_TARGET_PX] 宽。
     *
     * 共享给物品图与作物图两条通道（v2.73.12 起）——
     * 两者源图都是 16 宽（作物是 16×32，物品是 16×16），不归一化就是
     * 「16px 源放大 4.9 倍」的粗像素块，跟内置的 48×48 素材并排一眼能看出差别。
     *
     * ⚠ **先裁帧再归一化**（顺序不能反）：作物图要先从 128 宽的 sheet 里裁出 16 宽的帧，
     *   之后才是 16→48 放大 3 倍；反过来先归一化（整图 128 宽 ≥ 48，不动）再裁帧，
     *   拿到的还是 16 宽，等于没归一化。
     */
    private fun normalizeIcon(src: Bitmap): Bitmap {
        val w = src.width
        if (w >= ITEM_ICON_TARGET_PX) return src
        val factor = ITEM_ICON_TARGET_PX / w
        if (factor <= 1) return src
        return runCatching {
            Bitmap.createScaledBitmap(src, w * factor, src.height * factor, false)
        }.getOrDefault(src).also { if (it !== src) src.recycle() }
    }

    // ── 作物图 ───────────────────────────────────────────────────
    /**
     * Mod 作物图标缓存。与 [Portraits] 同款结构（并发容器 + peek/load 两段），
     * 但**解码规则不同**——包的 `icon` 是成长阶段 sprite sheet，不是单张表情格。
     *
     * 分成两个对象而不是给 [Portraits] 加参数：裁帧规则两套（64 见方取第一格 vs
     * 16 宽取末个非空帧），混进一个函数里早晚有人传错参数裁坏图。
     */
    object CropIcons {
        private val cache = ConcurrentHashMap<String, Bitmap>()

        /** 只查缓存不解码：UI 层先 peek 同步命中，miss 走 IO 线程异步解码（别在组合期读盘）。 */
        fun peek(modId: String, rel: String): Bitmap? = cache["$modId/$rel"]

        /** 从 [ctx] 的已装目录解码作物图。失败返回 null，UI 退回原版图标框。 */
        fun load(ctx: Context, modId: String, rel: String): Bitmap? {
            val key = "$modId/$rel"
            cache[key]?.let { return it }
            val safe = safeNameOf(rel) ?: return null
            val f = File(packDir(ctx, modId), safe)
            if (!f.isFile) return null
            val bmp = runCatching { decodeLastFrame(f) }.getOrNull() ?: return null
            cache[key] = bmp
            return bmp
        }

        /** [modId] 为 null 时清全部（重装/全部删除时用）。 */
        fun clear(modId: String? = null) {
            if (modId == null) cache.clear() else cache.keys.removeIf { it.startsWith("$modId/") }
        }
    }

    /**
     * 解码作物 sprite sheet 并裁出**最后一个非空帧**。
     *
     * ## 为什么取末帧而不是包 note 里写的「取第一帧」
     * 第一帧是**刚播下/刚冒芽**的阶段：实测 SVE 12 张里 `gold_carrot` 首帧只剩
     * 7~9 列几个像素高、整张几乎全透明；`ancient_ferns` 首帧也比末帧矮一大截。
     * 图鉴一栏是「这是什么」的识别位，末帧（成熟形态）才是玩家认得出的那个，
     * 也和原版作物图标（成熟收获物）落在同一个视觉语境上。
     *
     * ## 为什么不能简单取「最后一帧」
     * 有些 sheet 尾部留了空槽位（`ancient_ferns` 128 宽里第 8 帧整帧全透明，
     * `void` 第 7、8 帧全空）。直接取末帧会拿到一张纯透明的空白图标——
     * 所以这里**从后往前找第一个有像素的帧**。
     *
     * 帧宽按包声明的格式取 [CROP_FRAME_PX]（16），帧高取整图高。与 [decodeFirstCell]
     * 同款立场：按格式走，不去猜「这张方图到底是单图还是格子」——不合规格的包
     * 本来就不是星露谷的作物图，裁歪了也是包作者的事。
     */
    private fun decodeLastFrame(f: File): Bitmap? {
        val src = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        // 比一帧还窄（或只有一帧）：没有「取哪一帧」的问题，整张用。
        if (w <= CROP_FRAME_PX) return src
        val frames = (w + CROP_FRAME_PX - 1) / CROP_FRAME_PX
        var idx = frames - 1
        while (idx >= 0) {
            if (frameHasPixels(src, idx * CROP_FRAME_PX, minOf(CROP_FRAME_PX, w - idx * CROP_FRAME_PX), h)) break
            idx--
        }
        // 全透明（不该有，但别返回一个 128×32 的空条）：退回末帧，UI 那边看不出差别。
        val x = (if (idx < 0) frames - 1 else idx) * CROP_FRAME_PX
        val fw = minOf(CROP_FRAME_PX, w - x)
        val cropped = runCatching { Bitmap.createBitmap(src, x, 0, fw, h) }
            .getOrDefault(src)
            .also { if (it !== src) src.recycle() }
        // v2.73.12：裁完帧再**归一化**（16→48 整数倍最近邻）。
        // ⚠ 顺序不能反：先归一化的话整图 128 宽 ≥ 48 不会动，裁出来还是 16 宽 = 白做。
        return normalizeIcon(cropped)
    }

    /** 这一帧矩形里有没有任何不透明像素。 */
    private fun frameHasPixels(bmp: Bitmap, x0: Int, w: Int, h: Int): Boolean {
        val xEnd = minOf(bmp.width, x0 + w)
        val yEnd = minOf(bmp.height, h)
        for (y in 0 until yEnd) for (x in x0 until xEnd) {
            if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 0) return true
        }
        return false
    }

    /**
     * 解码头像并裁出**第一格**（左上 [PORTRAIT_CELL_PX] 见方）。
     *
     * 星露谷的头像格式是**定死**的：一律由 64×64 的表情格拼成（1~2 列 × N 行），
     * 「第一格」就是那张中性表情。所以这里**无条件裁**，不按宽高比猜——
     * 初版写过「方图（h == w）就整张用」，理由是怕裁坏单张大图，结果本包里
     * `images/Marlon.png` 恰好是 128×128 的 **2×2 表情格**，被整张塞进 36dp 的框里，
     * 一个小方块上挤了 4 张脸（实测截图才发现，肉眼看「像是加载错了」）。
     * 与其去猜「这张方图到底是单图还是格子」，不如按格式走：不合 64 格规范的包
     * 本来就不是星露谷的头像，裁歪了也是包作者的事。
     *
     * 图比 64 还小时取其短边（退化成整张，不放大）。
     */
    private fun decodeFirstCell(f: File): Bitmap? {
        val src = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        val side = minOf(PORTRAIT_CELL_PX, w, h)
        if (side >= w && side >= h) return src
        return runCatching { Bitmap.createBitmap(src, 0, 0, side, side) }
            .getOrDefault(src)
            .also { if (it !== src) src.recycle() }
    }

    // ── 杂项 ───────────────────────────────────────────────────

    private fun isZipUrl(u: String): Boolean =
        u.substringBefore('?').substringBefore('#').lowercase().endsWith(".zip")

    /** 方式 B 的占位 modId：从文件名取，形态不合格就退回 "pack"。 */
    private fun provisionalId(u: String): String {
        val file = u.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val stem = file.removeSuffix(".zip")
        return if (ID_PATTERN.matches(stem)) stem else "pack"
    }

    private fun isHttpsOrHttp(u: String): Boolean = u.startsWith("https://") || u.startsWith("http://")

    /**
     * 版本号比较（点分数字），返回 a<b / a==b / a>b。
     *
     * 逐段转数字而不是字典序：包版本 "1.15.11" 与 "1.15.9"，字典序会把 11 判成比 9 小。
     */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.trim().split(".")
        val pb = b.trim().split(".")
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val na = pa.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val nb = pb.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (na != nb) return na.compareTo(nb)
        }
        return 0
    }

    /**
     * URL 里的非 ASCII 字符 percent-encode 成纯 ASCII（中文文件名在严格客户端直接抛异常）。
     *
     * ⚠⚠ 必须用**五参**构造 `(scheme, authority, path, query, fragment)`，不能用
     *   四参的 `(scheme, host, path, fragment)`：那个把第三个参数当 authority 用、
     *   **只认 host 不认端口**，于是 `http://10.0.2.2:8731/a.zip` 会被悄悄改成
     *   `http://10.0.2.2/a.zip`——地址看着对、连的却是 80 端口，表现为「连不上网络」。
     *   调试这一版 Mod 下载时真踩过：清单是用户手输的（没走这里）所以拉到了，
     *   而清单里给的 downloadUrl 走了这里，端口被吃掉，服务器日志里一个请求都没有。
     *   authority 用 [java.net.URI.rawAuthority]：它已经是转义过的 ASCII，host:port 都在。
     */
    private fun asciiUrl(u: String): String {
        if (u.isEmpty()) return u
        return runCatching {
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
}
