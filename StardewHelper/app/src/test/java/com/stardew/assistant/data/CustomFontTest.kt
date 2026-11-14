package com.stardew.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.95.0：自定义字体导入的验收测试。
 *
 * ## 为什么要有这个
 *
 * 「读 TTF 的 name 表」这件事，静态断言（verify）**完全测不出来**：
 * 它钉的是「代码里有没有那个结构」，而真正的风险全在**读出来的值对不对**——
 * 字符串偏移算错一位、UTF-16BE 解成别的编码、平台优先级取错，
 * 这些都会安静地返回**错的版权行**（甚至空的），界面照样显示得漂漂亮亮。
 *
 * 所以这里用**真字体**（仓库里两个都在 git 中）+ **真代码**（同一份 `CustomFont.parseNameTable`）
 * 在 JVM 上跑：解析入口刻意**不依赖 Android 的 Context**（也不碰 Typeface），
 * 所以能在普通 JVM 上直接跑。
 *
 * ## 期望值从哪来
 * 用 Python 的 fontTools 逐条读出真实字体�� name 表，作为这里的期望值 ——
 * **不是照着实现反推的**（那样就成了自证）。
 *
 * ## 这些断言钉的是什么
 *
 * ① 完整版字体的 nameID 0/1/4/5/6 全部读对（**版权行一个字都不能差**）；
 * ② 子集字体的版权元数据**没被子集化丢掉**（这正是 subset_font.py 声称保留的）；
 * ③ 同一 nameID 有多条记录时优先 Windows/en-US；
 * ④ 坏文件（截断 / 随机字节 / 没有 name 表）**返回空元数据且不抛异常**；
 * ⑤ 元数据 JSON 往返一致；
 * ⑥ 内容哈希命名：同内容同 id、同名不同内容不同 id；
 * ⑦ 字体档守卫：custom 必须**本地真有文件**才认，否则回落像素。
 */
class CustomFontTest {

    // ── 真实字体定位 ────────────────────────────────────────────────
    // ⚠ JavaExec 的工作目录是**模块目录**（app/），不是工程根 ⇒ 相对路径要向上找。

    private fun findFont(vararg rel: String): File {
        val candidates = buildList {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                for (r in rel) add(File(dir, r))
                dir = dir.parentFile
            }
        }
        val f = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到字体文件，试过：" + candidates.joinToString(" | ") { it.absolutePath },
            f != null,
        )
        return f!!
    }

    /** 完整版 Fusion Pixel（7 MB，36521 码位）—— 仓库 tools/fonts 下那份。 */
    private val fullFont: File by lazy {
        findFont("tools/fonts/fusion_pixel.ttf", "../tools/fonts/fusion_pixel.ttf")
    }

    /** 内置子集（524 KB，2992 码位）—— res/font 下那份。 */
    private val subsetFont: File by lazy {
        findFont(
            "app/src/main/res/font/fusion_pixel.ttf",
            "src/main/res/font/fusion_pixel.ttf",
        )
    }

    // ── ① 完整版：逐个 nameID 对齐 fontTools 实测值 ─────────────────

    @Test
    fun `完整版字体的 name 表全部读对`() {
        val m = CustomFont.parseNameTable(fullFont.readBytes())
        // 这些期望值是 Python fontTools 逐条读出来的，不是照实现反推的
        assertEquals("Copyright (c) 2022, TakWolf (https://takwolf.com)", m.copyright)
        assertEquals("Fusion Pixel 12px Prop zh_hans", m.family)
        assertEquals("Fusion Pixel 12px Prop zh_hans Regular", m.fullName)
        assertEquals("2026.08.11", m.version)
        assertEquals("Fusion-Pixel-12px-Prop-zh_hans-Regular", m.postScriptName)
        assertFalse("元数据不应为空", m.isEmpty)
    }

    @Test
    fun `完整版字体没有许可描述与许可链接时就是空串`() {
        val m = CustomFont.parseNameTable(fullFont.readBytes())
        // 这份字体的 name 表里确实没有 nameID 13/14 ⇒ 读出来必须是空串，
        // **不能**拿别的字段顶替（界面会照原样显示「未声明此项」）
        assertEquals("", m.license)
        assertEquals("", m.licenseUrl)
    }

    // ── ② 子集：版权元数据一字未丢（OFL 第 2 条要求随副本保留）────────

    @Test
    fun `子集字体的版权行与完整版一字不差`() {
        val full = CustomFont.parseNameTable(fullFont.readBytes())
        val sub = CustomFont.parseNameTable(subsetFont.readBytes())
        assertEquals(
            "子集化的字体必须原样保留版权行（OFL 第 2 条）",
            full.copyright,
            sub.copyright,
        )
        assertEquals("2026.08.11", sub.version)
    }

    @Test
    fun `子集字体已按 OFL 第 3 条改名`() {
        val m = CustomFont.parseNameTable(subsetFont.readBytes())
        assertEquals("TutuPixel", m.family)
        assertEquals("TutuPixel Regular", m.fullName)
        assertEquals("TutuPixel-Regular", m.postScriptName)
    }

    // ── ③ 同 nameID 多条记录 ⇒ 优先 Windows/en-US ────────────────────

    @Test
    fun `同一 nameID 有多条记录时优先取 Windows en-US`() {
        // 手工造一个最小 sfnt：name 表里给同一 nameID 三条不同平台的记录
        val bytes = buildFakeFont(
            listOf(
                FakeNameRecord(platform = 1, language = 0, text = "Mac 版权"),
                FakeNameRecord(platform = 3, language = 0x0804, text = "中文版权"),
                FakeNameRecord(platform = 3, language = 0x0409, text = "Windows 版权"),
            ),
            nameId = CustomFont.NAME_COPYRIGHT,
        )
        val m = CustomFont.parseNameTable(bytes)
        assertEquals("Windows 版权", m.copyright)
    }

    @Test
    fun `没有 en-US 记录时退回任意一条非空记录`() {
        val bytes = buildFakeFont(
            listOf(
                FakeNameRecord(platform = 3, language = 0x0804, text = "中文版权"),
            ),
            nameId = CustomFont.NAME_COPYRIGHT,
        )
        val m = CustomFont.parseNameTable(bytes)
        assertEquals("中文版权", m.copyright)
    }

    @Test
    fun `UTF-16BE 的中文版权行能正确解出来`() {
        val bytes = buildFakeFont(
            listOf(
                FakeNameRecord(platform = 3, language = 0x0409, text = "版权所有 © 某某某"),
            ),
            nameId = CustomFont.NAME_COPYRIGHT,
        )
        val m = CustomFont.parseNameTable(bytes)
        assertEquals("版权所有 © 某某某", m.copyright)
    }

    // ── ④ 坏文件：返回空元数据，且**不抛异常** ───────────────────────

    @Test
    fun `截断的字体文件返回空元数据且不抛异常`() {
        val m = CustomFont.parseNameTable(fullFont.readBytes().copyOf(200))
        assertTrue("坏文件应返回空元数据", m.isEmpty)
    }

    @Test
    fun `随机字节返回空元数据且不抛异常`() {
        val rnd = java.util.Random(42)
        val junk = ByteArray(4096).also { rnd.nextBytes(it) }
        val m = CustomFont.parseNameTable(junk)
        assertTrue("随机字节应返回空元数据", m.isEmpty)
    }

    @Test
    fun `空数组与超短数组都不抛异常`() {
        assertTrue(CustomFont.parseNameTable(ByteArray(0)).isEmpty)
        assertTrue(CustomFont.parseNameTable(ByteArray(5)).isEmpty)
    }

    @Test
    fun `没有 name 表的字体返回空元数据`() {
        // 造一个只有一张假表的 sfnt（表目录里没有 'name'）
        val bytes = buildFakeFont(emptyList(), nameId = CustomFont.NAME_COPYRIGHT, withNameTable = false)
        val m = CustomFont.parseNameTable(bytes)
        assertTrue("没有 name 表应返回空元数据", m.isEmpty)
    }

    // ── ⑤ 元数据 JSON 往返 ──────────────────────────────────────────

    @Test
    fun `元数据 JSON 往返一致`() {
        val orig = CustomFont.parseNameTable(fullFont.readBytes())
        val back = CustomFont.Meta.fromJson(orig.toJson())
        assertEquals(orig.family, back.family)
        assertEquals(orig.fullName, back.fullName)
        assertEquals(orig.version, back.version)
        assertEquals(orig.postScriptName, back.postScriptName)
        assertEquals(orig.copyright, back.copyright)
        assertEquals(orig.license, back.license)
        assertEquals(orig.licenseUrl, back.licenseUrl)
    }

    @Test
    fun `坏 JSON 读出来是空元数据而不是崩溃`() {
        assertTrue(CustomFont.Meta.fromJson("不是 json").isEmpty)
        assertTrue(CustomFont.Meta.fromJson(null).isEmpty)
        assertTrue(CustomFont.Meta.fromJson("").isEmpty)
    }

    // ── ⑥ 内容哈希命名 ──────────────────────────────────────────────

    @Test
    fun `同内容得到同一个 id`() {
        val a = CustomFont.idOf("同样的字体".toByteArray())
        val b = CustomFont.idOf("同样的字体".toByteArray())
        assertEquals(a, b)
        assertEquals("id 应为 16 位十六进制", 16, a.length)
        assertTrue("id 应只含十六进制字符", a.all { it in "0123456789abcdef" })
    }

    @Test
    fun `内容不同则 id 不同`() {
        assertNotEquals(CustomFont.idOf("字体甲".toByteArray()), CustomFont.idOf("字体乙".toByteArray()))
    }

    // ── ⑦ 字体档守卫（读处口径的等价判定）──────────────────────────
    // ⚠ 真机上真正的守卫在 PrefsRepo.resolveFontFamily（private），
    //    靠「文件存在才认 custom」这条规则；这里钉住同一条规则的语义。

    @Test
    fun `custom 档必须有本地字体文件才生效否则回落像素`() {
        // 文件存在 ⇒ 认 custom
        assertEquals(
            "custom",
            resolveFamily("custom", customId = "abc123", customExists = true),
        )
        // 文件不在（换机导入备份后就是这样）⇒ 回落像素
        assertEquals(
            "custom 档但本地无字体文件时必须回落像素",
            "pixel",
            resolveFamily("custom", customId = "abc123", customExists = false),
        )
        // id 为空 ⇒ 回落像素
        assertEquals(
            "custom 档但 id 为空时必须回落像素",
            "pixel",
            resolveFamily("custom", customId = "", customExists = false),
        )
    }

    @Test
    fun `脏值一律回落像素字体`() {
        assertEquals("pixel", resolveFamily("乱写的", customId = "x", customExists = true))
        assertEquals("pixel", resolveFamily("", customId = "x", customExists = true))
        assertEquals("pixel", resolveFamily(null, customId = "x", customExists = true))
    }

    @Test
    fun `system 档不需要任何字体文件`() {
        assertEquals("system", resolveFamily("system", customId = "", customExists = false))
    }

    // ── 工具：造最小 sfnt 字节 ──────────────────────────────────────

    private data class FakeNameRecord(val platform: Int, val language: Int, val text: String)

    /**
     * 造一个只含 `name` 表的最小 sfnt 字节（够解析器读，不求它能当字体用）。
     *
     * 结构：sfnt 头(12) + 表目录(16) + name 表。
     * name 表：format(2) + count(2) + stringOffset(2) + count×12 记录 + 字符串区。
     */
    private fun buildFakeFont(
        records: List<FakeNameRecord>,
        nameId: Int,
        withNameTable: Boolean = true,
    ): ByteArray {
        // 先把字符串区拼好（每条记录用 UTF-16BE）
        val strings = ArrayList<ByteArray>()
        val strBuf = ArrayList<Byte>()
        val entries = records.map { r ->
            val raw = r.text.toByteArray(Charsets.UTF_16BE)
            val off = strBuf.size
            strBuf.addAll(raw.toList())
            strings.add(raw)
            Triple(r, off, raw.size)
        }
        val nameTableSize = 6 + entries.size * 12 + strBuf.size
        val headerSize = 12 + 16
        val nameOff = headerSize

        val out = ArrayList<Byte>()
        // sfnt 头：版本 0x00010000 + numTables=1 + 3 个 uint16
        out.addAll(byteArrayOf(0x00, 0x01, 0x00, 0x00).toList())
        out.addAll(byteArrayOf(0x00, 0x01).toList())   // numTables
        out.addAll(byteArrayOf(0x00, 0x00).toList())   // searchRange
        out.addAll(byteArrayOf(0x00, 0x00).toList())   // entrySelector
        out.addAll(byteArrayOf(0x00, 0x00).toList())   // rangeShift
        // 表目录一条：tag='name' + checksum + offset + length
        out.addAll("name".toByteArray(Charsets.US_ASCII).toList())
        out.addAll(byteArrayOf(0, 0, 0, 0).toList())   // checksum（解析器不读）
        out.addAll(u32(nameOff).toList())
        out.addAll(u32(nameTableSize).toList())
        if (!withNameTable) {
            // 把表目录的 tag 改成别的（解析器就找不到 name 表了）
            out[headerSize - 16] = 'x'.code.toByte()
        }
        // name 表：format=0 + count + stringOffset(6)
        out.addAll(byteArrayOf(0x00, 0x00).toList())
        out.addAll(u16(entries.size).toList())
        out.addAll(u16(6 + entries.size * 12).toList())
        // nameRecord 数组
        for ((r, off, len) in entries) {
            out.addAll(u16(r.platform).toList())
            out.addAll(u16(0).toList())               // encodingID
            out.addAll(u16(r.language).toList())
            out.addAll(u16(nameId).toList())
            out.addAll(u16(len).toList())
            out.addAll(u16(off).toList())
        }
        // 字符串区
        out.addAll(strBuf)
        return out.toByteArray()
    }

    private fun u16(v: Int): ByteArray = byteArrayOf(
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun u32(v: Int): ByteArray = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    /**
     * 与 PrefsRepo.resolveFontFamily **同规则**的等价实现。
     *
     * ⚠ 为什么在这里复刻而不是直接调真函数：真函数是 PrefsRepo 的 private 方法，
     *    而 PrefsRepo 依赖 Android Context，JVM 上跑不起来。复刻的是**规则**
     *    （system 认 / custom 要文件在 / 其余像素），verify 第 101 节另外钉住
     *    「真函数与这份复刻的规则一致」这一条。
     */
    private fun resolveFamily(raw: String?, customId: String, customExists: Boolean): String =
        when {
            raw == "system" -> "system"
            raw == "custom" && customId.isNotBlank() && customExists -> "custom"
            else -> "pixel"
        }
}
