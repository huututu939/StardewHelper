package com.stardew.assistant.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.text.font.FontFamily
import com.stardew.assistant.R
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily

/**
 * v2.95.0：用户导入的自定义字体（单个槽位）。
 *
 * ## 这个 App 对导入字体只做三件事
 * ① **把它收进私有目录**（`filesDir/fonts/`），不外传、不联网、不写公共存储；
 * ② **原样显示它自己声明的版权/许可信息**（读 TTF/OTF 的 `name` 表）；
 * ③ **把它应用到 App 内 UI**。
 *
 * ## 为什么不替用户背书（v2.95.0 的口径，改动不得偏离）
 * 导入的字体是**用户自己带进来的文件**，不是本 App 分发的内容。App 没有立场替它
 * 作授权声明，也**不改写**它自带的版权/许可文本（改写会破坏 OFL 等协议要求的
 * 署名完整性）。所以这里只做「原样显示 + 写明与本 App 无关」。
 *
 * ⚠️ 因此**不要**把导入字体的版权信息写进设置页「📜 数据来源与协议」抽屉——
 *   那是本 App **自己分发**的第三方归属清单；把用户自己的文件混进去等于虚假署名。
 *   导入字体的信息只在「🔠 字体」卡内展示。
 *
 * ## 为什么 name 表要自己解析（不引第三方库）
 * 这个 App 零第三方依赖、离线、不联网。为了读几个字符串就加一个字体库不划算；
 * 而 `name` 表的格式是公开且稳定的（sfnt 头 → 表目录 → name 表 → nameRecord 数组），
 * 纯字节解析一百来行就够。**解析失败不抛异常**（返回空元数据）——
 * 字体能不能用由 [loadFontFamily] 的 `Typeface.createFromFile` 把关，
 * 解析只影响「信息展示」这一层。
 *
 * ## 换字体必须换文件名（v2.95.0 踩过的坑）
 * Compose 的 `AndroidFileFont.getCacheKey()` 返回的是**文件路径**。
 * 同一个路径换内容 ⇒ 命中旧 Typeface 缓存 ⇒ 界面上还是上一个字体。
 * 所以文件名用**内容哈希**（不是原文件名）：内容变 ⇒ 路径变 ⇒ 缓存键必变。
 */
object CustomFont {

    // ── nameID 常量（OpenType 规范）──────────────────────────────────────
    /** 版权声明 */
    const val NAME_COPYRIGHT = 0
    /** 家族名 */
    const val NAME_FAMILY = 1
    /** 完整名（含子族） */
    const val NAME_FULL = 4
    /** 版本号字符串 */
    const val NAME_VERSION = 5
    /** PostScript 名 */
    const val NAME_POSTSCRIPT = 6
    /** 许可描述（OFL 等协议正文通常在这里） */
    const val NAME_LICENSE = 13
    /** 许可信息链接 */
    const val NAME_LICENSE_URL = 14

    /** 私有目录下的字体子目录名。放 filesDir：不进公共存储、不要任何权限、卸载即清。 */
    private const val DIR = "fonts"

    /** 单个槽位：导入新的会替换旧的（用户 2026-10-26 拍板）。 */
    const val MAX_FILES = 1

    /**
     * 字体文件自带的元数据。**全部原样取自字体文件**，一个字节都不改。
     *
     * 空串 = 该字体文件没声明这一项（不是「读不出来」，是字体自己没写）。
     */
    data class Meta(
        val family: String = "",
        val fullName: String = "",
        val version: String = "",
        val postScriptName: String = "",
        val copyright: String = "",
        val license: String = "",
        val licenseUrl: String = "",
    ) {
        /** 是否一项都没读到（⇒ 那个文件大概率不是字体，交给 [loadFontFamily] 判） */
        val isEmpty: Boolean
            get() = family.isBlank() && fullName.isBlank() && version.isBlank() &&
                postScriptName.isBlank() && copyright.isBlank() &&
                license.isBlank() && licenseUrl.isBlank()

        fun toJson(): String = JSONObject().apply {
            put("family", family)
            put("fullName", fullName)
            put("version", version)
            put("postScriptName", postScriptName)
            put("copyright", copyright)
            put("license", license)
            put("licenseUrl", licenseUrl)
        }.toString()

        companion object {
            fun fromJson(raw: String?): Meta {
                if (raw.isNullOrBlank()) return Meta()
                return runCatching {
                    val o = JSONObject(raw)
                    Meta(
                        family = o.optString("family"),
                        fullName = o.optString("fullName"),
                        version = o.optString("version"),
                        postScriptName = o.optString("postScriptName"),
                        copyright = o.optString("copyright"),
                        license = o.optString("license"),
                        licenseUrl = o.optString("licenseUrl"),
                    )
                }.getOrDefault(Meta())
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  一、name 表解析（纯字节，**不依赖 Android Context** ⇒ 可在 JVM 上测）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 解析 sfnt（TTF/OTF）的 `name` 表。
     *
     * ## 格式（照 OpenType 规范）
     * - 偏移 0：sfnt 版本（`0x00010000` = TrueType / `OTTO` = CFF / `true` = 旧 Mac）
     * - 偏移 4：numTables（uint16）
     * - 偏移 12：表目录，每项 16 字节 = tag(4) + checksum(4) + offset(4) + length(4)
     * - `name` 表内：format(u16) / count(u16) / stringOffset(u16)，
     *   随后 count × 12 字节的 nameRecord =
     *   platformID / encodingID / languageID / nameID / length / offset（各 u16）
     *
     * ## 取值口径（同一 nameID 有多条记录时）
     * 优先 **platformID=3（Windows）且 languageID=0x0409（en-US）**；
     * 无则退 platformID=1（Mac）；再无则取任意一条。
     *
     * ## 坏文件怎么办
     * 任何一步越界 / 表缺失 ⇒ 返回**空元数据**，**不抛异常**。
     * 字体能不能用由 [loadFontFamily] 把关；这里只管「信息展示」。
     */
    fun parseNameTable(bytes: ByteArray): Meta {
        return runCatching { parseNameTableUnsafe(bytes) }.getOrDefault(Meta())
    }

    private fun parseNameTableUnsafe(bytes: ByteArray): Meta {
        if (bytes.size < 12) return Meta()

        // 字体集合（ttcf）：偏移 12 起是各子字体的偏移表，取第一个。
        var dirBase = 0
        if (bytes[0] == 't'.code.toByte() && bytes[1] == 't'.code.toByte() &&
            bytes[2] == 'c'.code.toByte() && bytes[3] == 'f'.code.toByte()
        ) {
            if (bytes.size < 16) return Meta()
            dirBase = readU32(bytes, 12).toInt()
            if (dirBase <= 0 || dirBase + 12 > bytes.size) return Meta()
        }

        val numTables = readU16(bytes, dirBase + 4)
        if (numTables <= 0) return Meta()

        // 在表目录里找 'name'
        var nameOff = -1
        for (i in 0 until numTables) {
            val rec = dirBase + 12 + i * 16
            if (rec + 16 > bytes.size) break
            if (bytes[rec] == 'n'.code.toByte() && bytes[rec + 1] == 'a'.code.toByte() &&
                bytes[rec + 2] == 'm'.code.toByte() && bytes[rec + 3] == 'e'.code.toByte()
            ) {
                nameOff = readU32(bytes, rec + 8).toInt()
                break
            }
        }
        if (nameOff < 0 || nameOff + 6 > bytes.size) return Meta()

        val count = readU16(bytes, nameOff + 2)
        val stringOffset = readU16(bytes, nameOff + 4)

        // nameID → (优先级, 文本)。优先级越大越优先。
        val best = HashMap<Int, Pair<Int, String>>()
        for (i in 0 until count) {
            val rec = nameOff + 6 + i * 12
            if (rec + 12 > bytes.size) break
            val platform = readU16(bytes, rec)
            val language = readU16(bytes, rec + 4)
            val nameId = readU16(bytes, rec + 6)
            val len = readU16(bytes, rec + 8)
            val off = readU16(bytes, rec + 10)
            val strStart = nameOff + stringOffset + off
            if (strStart < 0 || len <= 0 || strStart + len > bytes.size) continue
            val text = decodeName(bytes, strStart, len, platform) ?: continue
            if (text.isBlank()) continue

            val priority = when {
                platform == 3 && language == 0x0409 -> 3
                platform == 3 -> 2
                platform == 1 -> 1
                else -> 0
            }
            val old = best[nameId]
            if (old == null || priority > old.first) best[nameId] = priority to text
        }

        fun pick(id: Int): String = best[id]?.second ?: ""
        return Meta(
            family = pick(NAME_FAMILY),
            fullName = pick(NAME_FULL),
            version = pick(NAME_VERSION),
            postScriptName = pick(NAME_POSTSCRIPT),
            copyright = pick(NAME_COPYRIGHT),
            license = pick(NAME_LICENSE),
            licenseUrl = pick(NAME_LICENSE_URL),
        )
    }

    /**
     * 解码 name 表里的字符串。
     *
     * - platformID 3（Windows）/ 0（Unicode）⇒ **UTF-16BE**
     * - platformID 1（Mac）⇒ **mac_roman**（不能当 Latin-1！0x80-0xFF 全不一样）
     *
     * mac_roman 的高半区（0x80~0xFF）用查表：Java 的标准字符集里**没有** mac_roman，
     * 而手写 128 个码位容易记错 —— 这张表是用 Python 的 mac_roman 编解码器逐字节生成的。
     */
    private fun decodeName(bytes: ByteArray, start: Int, len: Int, platform: Int): String? {
        return runCatching {
            if (platform == 1) {
                val sb = StringBuilder(len)
                for (i in 0 until len) {
                    val b = bytes[start + i].toInt() and 0xFF
                    sb.append(if (b < 0x80) b.toChar() else MAC_ROMAN_HIGH[b - 0x80])
                }
                sb.toString()
            } else {
                // UTF-16BE：Kotlin 的 Charsets 里没有 BE 版，用 java.nio 直接解。
                java.nio.charset.Charset.forName("UTF-16BE")
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                    .decode(java.nio.ByteBuffer.wrap(bytes, start, len))
                    .toString()
            }
        }.getOrNull()
    }

    /** mac_roman 高半区 0x80~0xFF（由 Python `bytes([b]).decode("mac_roman")` 逐字节生成）。 */
    private val MAC_ROMAN_HIGH = charArrayOf(
        'Ä', 'Å', 'Ç', 'É', 'Ñ', 'Ö', 'Ü', 'á',
        'à', 'â', 'ä', 'ã', 'å', 'ç', 'é', 'è',
        'ê', 'ë', 'í', 'ì', 'î', 'ï', 'ñ', 'ó',
        'ò', 'ô', 'ö', 'õ', 'ú', 'ù', 'û', 'ü',
        '†', '°', '¢', '£', '§', '•', '¶', 'ß',
        '®', '©', '™', '´', '¨', '≠', 'Æ', 'Ø',
        '∞', '±', '≤', '≥', '¥', 'µ', '∂', '∑',
        '∏', 'π', '∫', 'ª', 'º', 'Ω', 'æ', 'ø',
        '¿', '¡', '¬', '√', 'ƒ', '≈', '∆', '«',
        '»', '…', ' ', 'À', 'Ã', 'Õ', 'Œ', 'œ',
        '–', '—', '“', '”', '‘', '’', '÷', '◊',
        'ÿ', 'Ÿ', '⁄', '€', '‹', '›', 'ﬁ', 'ﬂ',
        '‡', '·', '‚', '„', '‰', 'Â', 'Ê', 'Á',
        'Ë', 'È', 'Í', 'Î', 'Ï', 'Ì', 'Ó', 'Ô',
        '', 'Ò', 'Ú', 'Û', 'Ù', 'ı', 'ˆ', '˜',
        '¯', '˘', '˙', '˚', '¸', '˝', '˛', 'ˇ',
    )

    private fun readU16(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 1 >= b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }

    private fun readU32(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 3 >= b.size) return 0L
        return ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)
    }

    // ══════════════════════════════════════════════════════════════════
    //  二、落盘（filesDir/fonts/）
    // ══════════════════════════════════════════════════════════════════

    /** 字体私有目录。不进公共存储、不要任何权限、卸载即清。 */
    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 字体文件路径：`<内容哈希>.ttf`。**用内容哈希而不是原文件名**（见文件头「换字体必须换文件名」）。 */
    fun fontFile(ctx: Context, id: String): File = File(dir(ctx), "$id.ttf")

    /** 元数据文件路径：与字体同目录的 `<id>.json`。 */
    fun metaFile(ctx: Context, id: String): File = File(dir(ctx), "$id.json")

    /** 内容哈希（SHA-1 前 16 位十六进制）。同内容 ⇒ 同 id ⇒ 同路径。 */
    fun idOf(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        val d = md.digest(bytes)
        val sb = StringBuilder(16)
        for (i in 0 until 8) sb.append("%02x".format(d[i]))
        return sb.toString()
    }

    /**
     * 导入一个字体文件（SAF 给的 Uri）。
     *
     * ## 流程
     * ① 读字节（有上限，挡住超大文件）→ ② 算内容哈希当 id → ③ **先试加载 Typeface**
     * （加载不了就是「不是可用的字体」，直接失败，**不落盘、不删用户的原文件**）→
     * ④ 写 `<id>.ttf` + `<id>.json` → ⑤ 清理除当前 id 外的旧文件（单槽位）。
     *
     * ## 为什么要先试加载
     * 「能解析 name 表」不等于「是可用字体」——一个随机改名成 .ttf 的文件可能连
     * sfnt 头都不合法。让 `Typeface.createFromFile` 先把关，才能给用户
     * 「这个文件不是可用的字体」而不是一个装上去才发现字全是方块的字体。
     *
     * @return 成功返回 id，失败返回错误文案（可直接 Toast）
     */
    fun importFont(ctx: Context, uri: Uri): Result {
        val bytes = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return Result.Err("读取文件失败，请重新选择")

        if (bytes.isEmpty()) return Result.Err("这个文件是空的")
        if (bytes.size > MAX_FONT_BYTES) {
            return Result.Err("字体文件太大（${bytes.size / 1048576} MB），本 App 只接受 20 MB 以内的字体")
        }

        val id = idOf(bytes)
        // 先验证是不是真字体：Typeface 加载不了就直接失败，不落盘
        val probe = File(ctx.cacheDir, "font_probe_$id.ttf")
        try {
            probe.writeBytes(bytes)
            val tf = Typeface.createFromFile(probe)
            if (tf == null) return Result.Err("这个文件不是可用的字体")
        } catch (_: Throwable) {
            return Result.Err("这个文件不是可用的字体")
        } finally {
            runCatching { probe.delete() }
        }

        val meta = parseNameTable(bytes)
        val target = fontFile(ctx, id)
        target.writeBytes(bytes)
        metaFile(ctx, id).writeText(meta.toJson())

        // 单槽位：清掉除当前 id 之外的所有字体文件与元数据
        runCatching {
            dir(ctx).listFiles()?.forEach { f ->
                if (f.name != target.name && f.name != metaFile(ctx, id).name) {
                    f.delete()
                }
            }
        }

        return Result.Ok(id)
    }

    /** 读取已导入字体的元数据（读不到 ⇒ 空元数据 ⇒ UI 显示「未声明」）。 */
    fun meta(ctx: Context, id: String): Meta {
        if (id.isBlank()) return Meta()
        return runCatching { Meta.fromJson(metaFile(ctx, id).readText()) }.getOrDefault(Meta())
    }

    /** 本地是否真的有这个字体文件（备份跨机后用它决定要不要回落像素字体）。 */
    fun exists(ctx: Context, id: String): Boolean {
        if (id.isBlank()) return false
        return runCatching { fontFile(ctx, id).isFile }.getOrDefault(false)
    }

    /** 删掉已导入的字体（文件 + 元数据）。删完调用方要把字体档回落「像素」。 */
    fun delete(ctx: Context, id: String) {
        if (id.isBlank()) return
        runCatching { fontFile(ctx, id).delete() }
        runCatching { metaFile(ctx, id).delete() }
    }

    /**
     * 把字体文件加载成 Compose 的 [FontFamily]。
     *
     * 走 `Typeface.createFromFile` 而不是 `Font(File)`：前者**失败返回 null**，
     * 后者失败要到真正渲染时才炸（那时候用户已经看到半屏方块了）。
     *
     * ## ⚠ 必须挂 Android 的 **typeface fallback 链**
     *
     * ### 先把「豆腐块是谁的锅」说清楚（2026-10-26 实测更正）
     * 导入 ZCOOL XiaoWei 后，设置页「← 返回」的「回」字渲染成实心方块。
     * 曾经的判断是「`FontFamily(Typeface)` 不带 fallback ⇒ 缺字无处可退」——**那是错的**：
     * ① fontTools 查该字体 cmap 有 U+56DE、字形非空（3 轮廓，bounds 正常）；
     * ② **拿同一个字体文件用 PIL 渲染，「回」同样是实心方块** ⇒
     *    **那个方块就是字体自己画的字形**，Android 没做错任何事。
     * ⇒ 这种方块 App **救不了也不该救**（那是字体作者的设计），fallback 只对
     * 「字体真的没有这个码位」有效。
     *
     * ### 为什么还是要挂 fallback
     * 用户导入的第三方字体码位往往不全（实测码位数：站酷小薇 7016、站酷庆科黄油 9429、
     * 霞鹜文楷 TC 22401、霞鹜Marker哥特 15285）。
     * 对本 App 实际用到的字符集（含 assets 里的游戏数据，共 4649 个汉字/标点），
     * **缺码位比例实测**：霞鹜文楷 TC 13.8%、霞鹜Marker哥特 19.8%、
     * 站酷庆科黄油 29.8%、站酷小薇 35.1%。
     * **真缺码位**时 Android 会画 .notdef（空洞方框），比「退到别的字体出字」难看得多
     * ⇒ 给 typeface 挂链，逐级退：**自定义字体 → 像素体 → 系统字体**。
     *
     * ### 为什么不能拿 Compose 的 `FontFamily` 列表顶替
     * 查 Compose 1.7.6 源码（`FontMatcher.matchFont` +
     * `FontListFontFamilyTypefaceAdapter.firstImmediatelyAvailable`）：一个 family
     * **只按字重/字形挑一个 typeface**，**不做按字符 fallback** ⇒
     * `FontFamily(自定义, 像素)` 里自定义字体照样独占整段文本。
     * 按字符退字**只能靠 Android 的 typeface fallback 链**。
     *
     * ⚠️ 这套 fallback API 需要 **API 29**（本 App minSdk 26）：
     * API 26–28 拿不到链，退回原行为（真缺字仍是 .notdef 空心框）——
     * 那两代机型占比极低，不为它们引入更重的方案。
     *
     * ⚠️ **调用方必须离开主线程**：7 MB 字体加载要几十到几百毫秒。
     */
    fun loadFontFamily(ctx: Context, id: String): FontFamily? {
        if (id.isBlank()) return null
        val f = fontFile(ctx, id)
        if (!f.isFile) return null
        return runCatching {
            val tf = Typeface.createFromFile(f) ?: return null
            FontFamily(attachFallback(ctx, f, tf))
        }.getOrNull()
    }

    /**
     * 给自定义字体挂 **Android 的 typeface fallback 链**（逐字符退字）。
     *
     * 链的顺序：**自定义字体 → 像素体 → 系统字体**。
     * ① 像素体在前：它是这个 App 的底色字体（2992 码位子集，覆盖 App 全部文案），
     *    混排时至少还是「这个 App 的字」；
     * ② 系统字体兜底：像素体也没覆盖的字（游戏数据里的生僻字）还能出字，
     *    至少不是空洞的 .notdef 方框。
     *
     * ## 为什么用「新一代字体 API」而不是 `Typeface.createCustomFallback`
     * 查本机 SDK `platforms/android-35/data/api-versions.xml`：
     * **公开 API 里没有 `Typeface.createCustomFallback`** —— API 29 起的那套是
     * `android.graphics.fonts.Font` → `FontFamily` → `Typeface.CustomFallbackBuilder`。
     * 任何按「`createCustomFallback` 存在」写的代码都编译不过（已踩）。
     *
     * API 26–28 没有这套 API ⇒ 原样返回，不加链（那两代机型占比极低）。
     */
    private fun attachFallback(ctx: Context, file: File, base: Typeface): Typeface {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return base
        return attachFallbackQ(ctx, file, base)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun attachFallbackQ(ctx: Context, file: File, base: Typeface): Typeface = runCatching {
        val custom = PlatformFontFamily.Builder(PlatformFont.Builder(file).build()).build()
        val builder = Typeface.CustomFallbackBuilder(custom)
        // ① 像素体（App 自己的底色字体）：`Font.Builder(Resources, resId)` 直接读资源，
        //    不用把 res/font 里的 ttf 再落一份到磁盘。
        runCatching {
            val pixel = PlatformFont.Builder(ctx.resources, R.font.fusion_pixel).build()
            builder.addCustomFallback(PlatformFontFamily.Builder(pixel).build())
        }
        // ② 系统字体兜底：像素体也没覆盖的字（游戏数据里的生僻字）还能出字。
        builder.setSystemFallback("sans-serif")
        builder.build()
    }.getOrDefault(base)

    /** 字体文件大小上限：20 MB。挡住「误选了一个几百 MB 的文件」这种事故。 */
    const val MAX_FONT_BYTES = 20L * 1024 * 1024

    /** 导入结果。 */
    sealed interface Result {
        data class Ok(val id: String) : Result
        data class Err(val msg: String) : Result
    }
}
