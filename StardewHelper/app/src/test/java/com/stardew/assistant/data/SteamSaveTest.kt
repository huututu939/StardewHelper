package com.stardew.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * v2.86.0：Steam 云存档嗅探与完整性判定的验收测试。
 *
 * ## 为什么用**真实存档**做断言
 * 嗅探规则一旦写错，后果是「用户的存档导不进去」或「把半成品/摘要当存档导进去」。
 * 这两类错用构造的假数据测不出来 —— 假数据是照着我自己的理解造的，
 * 而**真实存档的结构**才是判据。所以这里直接读仓库 `存档/_analysis_tmp/` 下
 * 四档真实玩家存档（与 [WorldStateParserTest] 同一批），逐个文件断言判定结果。
 *
 * ## 校准出来的关键事实（这些是本测试存在的理由）
 * ① 主存档判定 = 根标签 `<SaveGame>` **且**含 `<player>`；
 * ② `SaveGameInfo` 的根标签是 **`<Farmer>`**（不是 `<SaveGameInfo>`），
 *    且它**含有** `<locations>` / `<stats>` ⇒ 不能拿这两个当判据；
 * ③ `<locations>` 在 33KB 处，`ff` 与 `让我们重逢` 两档**整个文件都没有**；
 * ④ 四份存档**全部带 UTF-8 BOM**；
 * ⑤ `_SVBAK` / `_SVEMERG` 比主存档**还大** ⇒ 不能按体积取最大。
 *
 * 存档目录默认 `存档/_analysis_tmp`，
 * 可用 `-Dsteam.saves=...` 覆盖；**目录不存在时测试被 Assume 跳过**
 * （不会假装通过）。
 */
class SteamSaveTest {

    /**
     * 真实存档目录。默认用相对路径（仓根下的 `存档/_analysis_tmp`）——
     * ⚠ 2026-11-10 开源清理：原值写死本机绝对路径，clone 到别人机器上必然指不到。
     * 仓库不含玩家存档（隐私原因），所以默认目录本来就不存在，
     * 测试会因 Assume 跳过（**不会假装通过**）；
     * 要跑真存档测试的人用 `-Dsteam.saves=...` 指自己的目录。
     */
    private val savesDir: File by lazy {
        System.getProperty("steam.saves")
            ?.let { File(it) }
            ?: File("存档/_analysis_tmp")
    }

    /** 读文件前 N 字节。 */
    private fun head(file: File, n: Int = SteamSaveSniffer.SNIFF_BYTES): ByteArray {
        file.inputStream().use { input ->
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(buf, read, n - read)
                if (r <= 0) break
                read += r
            }
            return if (read == n) buf else buf.copyOf(read)
        }
    }

    // ── ① 用真实存档校准嗅探判据 ────────────────────────────────

    /**
     * **核心断言**：四档真实存档里，凡是该被认出来的主存档，全部认出来；
     * 凡是不该认的（SaveGameInfo / _old / _SVBAK / BACKUP_SAVE / json），全部不认。
     *
     * 这条断言是整个功能的生死线：认错了 → 用户的进度导错；
     * 认不出来 → 功能完全不可用。
     */
    @Test
    fun realSaves_sniffedCorrectly() {
        assumeTrue("真实存档目录不存在：" + savesDir.path, savesDir.isDirectory)

        // 各档目录 → (主存档文件名, 期望农场名)
        val expectations = listOf(
            Triple("ff_312128132", "ff_312128132", "ff"),
            Triple("穿越_447169055", "穿越_447169055", "穿越"),
            Triple("让我们重逢_386818827", "让我们重逢_386818827", "让我们重逢"),
            Triple("KKK_387956097", "KKK_387956097", "KKK"),
        )

        var checkedMain = 0
        var checkedOther = 0

        for ((dirName, mainName, farmName) in expectations) {
            val dir = File(savesDir, dirName)
            assumeTrue("档位目录不存在：" + dir.path, dir.isDirectory)

            // 主存档：必须认出来
            val main = File(dir, mainName)
            assumeTrue("主存档不存在：" + main.path, main.exists())
            val s = SteamSaveSniffer.sniff(head(main), main.name)
            assertTrue(
                "主存档「$mainName」应被认成主存档，实际：${s.reason}",
                s.isMainSave,
            )
            assertEquals("主存档根标签", "SaveGame", s.rootTag)
            assertEquals("农场名（文件名去数字 ID）", farmName, SteamSaveSniffer.farmNameOf(mainName))
            checkedMain++

            // 同一目录里的其它文件：一个都不能被认成主存档
            dir.listFiles()?.forEach { f ->
                if (!f.isFile || f.name == mainName) return@forEach
                val r = SteamSaveSniffer.sniff(head(f), f.name)
                assertFalse(
                    "「${f.parentFile.name}/${f.name}」不该被认成主存档（理由：${r.reason}）",
                    r.isMainSave,
                )
                checkedOther++
            }        }
        assertTrue("主存档样本数不足（$checkedMain），真实存档目录可能不对", checkedMain >= 4)
        assertTrue("负样本数不足（$checkedOther）", checkedOther >= 4)
    }

    /**
     * **`SaveGameInfo` 的根标签是 `<Farmer>`，不是 `<SaveGameInfo>`** ——
     * 任务书里写的那个名字是错的，代码必须按实测来。
     *
     * 这条断言单独钉住，是为了防止「按任务书而不是按实测」写代码。
     */
    @Test
    fun saveGameInfo_rootTagIsFarmer_notSaveGameInfo() {
        assumeTrue("真实存档目录不存在", savesDir.isDirectory)
        val info = File(savesDir, "穿越_447169055/SaveGameInfo")
        assumeTrue("SaveGameInfo 不存在", info.exists())

        val text = SteamSaveSniffer.decodeHead(head(info, 4096))
        assertEquals("SaveGameInfo 的根标签", "Farmer", SteamSaveSniffer.rootTagOf(text))
        assertTrue(
            "任务书写的是 <SaveGameInfo>，实测是 <Farmer> —— 若此断言失败说明存档格式变了",
            !text.contains("<SaveGameInfo"),
        )
    }

    /**
     * **`<locations>` / `<stats>` 不能当主存档判据** ——
     * 实测它们在 33KB 之后，且有的档**整个文件都没有**，
     * 而 `SaveGameInfo` **反而含有**它们。
     */
    @Test
    fun locationsAndStats_areNotUsableAsCriteria() {
        assumeTrue("真实存档目录不存在", savesDir.isDirectory)

        // ① 有档整个文件都没有 <locations>
        val ff = File(savesDir, "ff_312128132/ff_312128132")
        assumeTrue("ff 档不存在", ff.exists())
        val ffText = SteamSaveSniffer.decodeHead(head(ff))
        assertFalse(
            "ff 档前 16KB 不该含 <locations>（实测它在 33KB 处或不存在）",
            ffText.contains("<locations"),
        )

        // ② SaveGameInfo 反而含有 <locations> 与 <stats>（在 33KB/34KB 处）
        val info = File(savesDir, "穿越_447169055/SaveGameInfo")
        assumeTrue("SaveGameInfo 不存在", info.exists())
        val infoFull = String(info.readBytes(), Charsets.UTF_8).removePrefix("﻿")
        assertTrue(
            "SaveGameInfo 竟然不含 <locations> —— 若失败说明格式变了",
            infoFull.contains("<locations"),
        )
    }

    /**
     * **BOM 必须被跳过** —— 四份存档全部带 BOM，不跳的话根标签取不到。
     */
    @Test
    fun bom_isSkipped() {
        assumeTrue("真实存档目录不存在", savesDir.isDirectory)
        val main = File(savesDir, "穿越_447169055/穿越_447169055")
        assumeTrue("主存档不存在", main.exists())

        val raw = head(main, 64)
        assertEquals("BOM 头", 0xEF.toByte(), raw[0])
        assertEquals("BOM 头", 0xBB.toByte(), raw[1])
        assertEquals("BOM 头", 0xBF.toByte(), raw[2])

        val text = SteamSaveSniffer.decodeHead(raw)
        assertFalse("解码后不该还带 BOM 字符", text.startsWith("﻿"))
        assertEquals("跳 BOM 后的根标签", "SaveGame", SteamSaveSniffer.rootTagOf(text))
    }

    /**
     * **`<SVBAK>` / `<SVEMERG>` 比主存档还大** ⇒ 不能按体积取最大。
     * 这条把「必须按文件名排除」钉死。
     */
    @Test
    fun backupFilesMayBeLargerThanMainSave() {
        assumeTrue("真实存档目录不存在", savesDir.isDirectory)
        val dir = File(savesDir, "KKK_387956097")
        assumeTrue("KKK 档不存在", dir.isDirectory)
        val main = File(dir, "KKK_387956097")
        assumeTrue("KKK 主存档不存在", main.exists())
        val svbak = File(dir, "KKK_387956097_SVBAK")
        assumeTrue("KKK _SVBAK 不存在", svbak.exists())

        assertTrue(
            "实测 _SVBAK 比主存档还大（若失败说明存档样本变了）",
            svbak.length() > main.length(),
        )
        // 但它必须被文件名规则排除
        assertTrue("_SVBAK 应被排除", SteamSaveSniffer.isExcludedName("KKK_387956097_SVBAK"))
        assertFalse("主存档本身不该被排除", SteamSaveSniffer.isExcludedName("KKK_387956097"))
    }

    // ── ② 文件名规则 ──────────────────────────────────────────

    @Test
    fun excludedNames_areRecognized() {
        assertTrue(SteamSaveSniffer.isExcludedName("SaveGameInfo"))
        assertTrue(SteamSaveSniffer.isExcludedName("savegameinfo"))
        assertTrue(SteamSaveSniffer.isExcludedName("SaveGameInfo_old"))
        assertTrue(SteamSaveSniffer.isExcludedName("BACKUP_SAVE"))
        assertTrue(SteamSaveSniffer.isExcludedName("农场_old"))
        assertTrue(SteamSaveSniffer.isExcludedName("农场_SVBAK"))
        assertTrue(SteamSaveSniffer.isExcludedName("农场_SVEMERG"))
        assertFalse(SteamSaveSniffer.isExcludedName("农场_123456"))
        assertFalse(SteamSaveSniffer.isExcludedName("让我们重逢_386818827"))
    }

    @Test
    fun tempNames_areRecognized() {
        assertTrue(SteamSaveSniffer.isTempName("农场_123.crdownload"))
        assertTrue(SteamSaveSniffer.isTempName("农场_123.tmp"))
        assertTrue(SteamSaveSniffer.isTempName("农场_123.part"))
        assertTrue(SteamSaveSniffer.isTempName("农场_123.partial"))
        assertFalse(SteamSaveSniffer.isTempName("农场_123"))
    }

    /**
     * **临时文件优先于其它排除规则** ——
     * 「下载中的主存档」文件名可能是「农场名_123.crdownload」，
     * 它既不是 `_old` 也不是 `SaveGameInfo`，只能靠临时后缀认出来。
     */
    @Test
    fun tempFile_isRejectedEvenIfNameLooksNormal() {
        val s = SteamSaveSniffer.sniff(ByteArray(0), "农场_123.crdownload")
        assertFalse(s.isMainSave)
        assertTrue("理由应提到临时文件", s.reason.contains("临时"))
    }

    @Test
    fun farmName_stripsNumericId() {
        assertEquals("农场", SteamSaveSniffer.farmNameOf("农场_123456789"))
        assertEquals("穿越", SteamSaveSniffer.farmNameOf("穿越_447169055"))
        assertEquals("让我们重逢", SteamSaveSniffer.farmNameOf("让我们重逢_386818827"))
        // 没有数字 ID 时原样返回
        assertEquals("农场名", SteamSaveSniffer.farmNameOf("农场名"))
        // 尾巴不是数字时不该被剥
        assertEquals("农场_old", SteamSaveSniffer.farmNameOf("农场_old"))
    }

    // ── ③ 根标签解析 ──────────────────────────────────────────

    @Test
    fun rootTag_skipsXmlDeclarationAndComments() {
        assertEquals("SaveGame", SteamSaveSniffer.rootTagOf("<?xml version=\"1.0\"?><SaveGame>"))
        assertEquals("SaveGame", SteamSaveSniffer.rootTagOf("<!-- 注释 --><SaveGame>"))
        assertEquals("SaveGame", SteamSaveSniffer.rootTagOf("<!DOCTYPE x><SaveGame>"))
        assertEquals("SaveGame", SteamSaveSniffer.rootTagOf("  \n <SaveGame "))
        assertEquals("", SteamSaveSniffer.rootTagOf(""))
        assertEquals("", SteamSaveSniffer.rootTagOf("没有尖括号"))
        // 声明与注释都没跳过时会取到 "xml" / "!--"
        assertTrue(SteamSaveSniffer.rootTagOf("<?xml version=\"1.0\"?><SaveGame>") != "xml")
    }

    @Test
    fun sniff_rejectsNonMainSave() {
        // 根标签不对（文件名**不**被排除时才能看到根标签）
        val farmer = "﻿<Farmer><name>x</name></Farmer>".toByteArray(Charsets.UTF_8)
        val r1 = SteamSaveSniffer.sniff(farmer, "农场_1")
        assertFalse(r1.isMainSave)
        assertEquals("Farmer", r1.rootTag)

        // 是 SaveGame 但没有 player
        val noPlayer = "﻿<SaveGame><locations/></SaveGame>".toByteArray(Charsets.UTF_8)
        val r2 = SteamSaveSniffer.sniff(noPlayer, "农场_1")
        assertFalse(r2.isMainSave)

        // 二进制
        val r3 = SteamSaveSniffer.sniff(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00), "农场_1.zip")
        assertFalse(r3.isMainSave)

        // 空文件
        val r4 = SteamSaveSniffer.sniff(ByteArray(0), "农场_1")
        assertFalse(r4.isMainSave)
    }

    /**
     * **文件名排除优先于内容判定** —— 这不是可有可无的顺序问题。
     *
     * `_old` 备份的**内容完全合法**（根 `<SaveGame>` + 含 `<player>`），
     * 只有文件名能认出它是备份。所以文件名排除必须**前置**：
     * 先按名字排掉，再谈内容。
     *
     * ⚠ 反过来，`SaveGameInfo` 被文件名排除后**拿不到根标签**（提前返回）——
     *   这是对的：它压根不该进候选列表，没人需要知道它的根是什么。
     */
    @Test
    fun filenameExclusion_takesPriorityOverContent() {
        val validMainSaveBytes = "﻿<SaveGame><player>x</player></SaveGame>".toByteArray(Charsets.UTF_8)

        // ① 内容完全合法，但文件名是 _old → 必须排除
        val old = SteamSaveSniffer.sniff(validMainSaveBytes, "农场_123_old")
        assertFalse("_old 内容合法也必须排除", old.isMainSave)
        assertTrue("理由应说明是备份/摘要", old.reason.contains("备份"))

        // ② SaveGameInfo 被文件名排除 → 提前返回，根标签拿不到
        val info = SteamSaveSniffer.sniff(
            "﻿<Farmer><name>x</name></Farmer>".toByteArray(Charsets.UTF_8),
            "SaveGameInfo",
        )
        assertFalse("SaveGameInfo 必须排除", info.isMainSave)
        assertEquals("被文件名排除时根标签不报（提前返回）", "", info.rootTag)

        // ③ 同样的内容换个名字就认得出来 —— 证明确实是文件名在起作用
        val same = SteamSaveSniffer.sniff(validMainSaveBytes, "农场_123")
        assertTrue("同样内容换个名字就认出来", same.isMainSave)
    }

    // ── ④ 完整性状态机 ────────────────────────────────────────

    /**
     * 下载中 → 大小在变 → 继续等；大小稳定 + 内容对 → 认。
     */
    @Test
    fun watcher_waitsWhileSizeChanges_thenReady() {
        val o1 = SteamSaveWatcher.Observation(1000L, 111L)
        val o2 = SteamSaveWatcher.Observation(5000L, 222L)
        val o3 = SteamSaveWatcher.Observation(5000L, 222L)

        // 大小在变 → 等
        val v1 = SteamSaveWatcher.judge(o1, o2, null, 0)
        assertTrue("大小在变时应继续等", v1 is SteamSaveWatcher.Verdict.Wait)

        // 大小稳定 + 嗅出主存档 → 认
        val good = SteamSaveSniffer.Sniff(true, "SaveGame", "主存档")
        val v2 = SteamSaveWatcher.judge(o2, o3, good, 0)
        assertTrue("稳定且内容对应认", v2 is SteamSaveWatcher.Verdict.Ready)
    }

    /**
     * 嗅探失败**不立刻判死** —— 先给重试窗口，全失败才拒。
     */
    @Test
    fun watcher_retriesBeforeRejecting() {
        val o1 = SteamSaveWatcher.Observation(5000L, 111L)
        val o2 = SteamSaveWatcher.Observation(5000L, 111L)
        val bad = SteamSaveSniffer.Sniff(false, "Farmer", "不是主存档（根节点是 <Farmer>）")

        // 还没到重试上限 → 等
        for (attempt in 0 until SteamSaveWatcher.RETRY_COUNT) {
            val v = SteamSaveWatcher.judge(o1, o2, bad, attempt)
            assertTrue(
                "第 $attempt 次应还在重试窗口内",
                v is SteamSaveWatcher.Verdict.Wait,
            )
        }
        // 超出重试上限 → 拒
        val v = SteamSaveWatcher.judge(o1, o2, bad, SteamSaveWatcher.RETRY_COUNT)
        assertTrue("超出重试上限应判失败", v is SteamSaveWatcher.Verdict.Reject)
    }

    @Test
    fun watcher_fileGone_isRejected() {
        val v = SteamSaveWatcher.judge(null, SteamSaveWatcher.Observation(-1L, -1L), null, 0)
        assertTrue("文件消失应判失败", v is SteamSaveWatcher.Verdict.Reject)
    }

    @Test
    fun watcher_stabilityCheck() {
        val a = SteamSaveWatcher.Observation(100L, 1L)
        val b = SteamSaveWatcher.Observation(100L, 2L)
        val c = SteamSaveWatcher.Observation(200L, 3L)
        assertFalse("没有上一次观察时不算稳定", SteamSaveWatcher.isStable(null, a))
        assertTrue("大小相同即稳定（修改时间可以不同）", SteamSaveWatcher.isStable(a, b))
        assertFalse("大小变了不算稳定", SteamSaveWatcher.isStable(a, c))
    }

    // ── ⑤ 去重与排序 ──────────────────────────────────────────

    @Test
    fun dedupe_removesSameFileSeenTwice() {
        val c = SteamSaveSniffer.Candidate("u1", "农场_1", 100L, 5L, true, "主存档")
        val dup = SteamSaveSniffer.Candidate("u1", "农场_1", 100L, 5L, true, "主存档")
        val other = SteamSaveSniffer.Candidate("u2", "农场_2", 200L, 6L, true, "主存档")
        val out = SteamSaveWatcher.dedupe(listOf(c, dup, other))
        assertEquals("同文件两次只留一条", 2, out.size)
    }

    @Test
    fun candidates_sort_mainFirst_thenNewerThenBigger() {
        val old = SteamSaveSniffer.Candidate("u1", "农场_1", 100L, 1L, true, "主存档")
        val new = SteamSaveSniffer.Candidate("u2", "农场_2", 100L, 9L, true, "主存档")
        val notMain = SteamSaveSniffer.Candidate("u3", "SaveGameInfo", 999L, 99L, false, "摘要")
        val sorted = listOf(notMain, old, new).sortedWith { a, b ->
            SteamSaveSniffer.compareCandidates(a, b)
        }
        assertTrue("主存档排最前", sorted[0].isMainSave)
        assertTrue("同为真档时新的在前", sorted[0].name == "农场_2")
        assertFalse("非主存档排最后", sorted[2].isMainSave)
    }

    @Test
    fun fingerprint_identifiesSameFile() {
        val a = SteamSaveWatcher.fingerprint("农场_1", 100L, 5L)
        val b = SteamSaveWatcher.fingerprint("农场_1", 100L, 5L)
        val c = SteamSaveWatcher.fingerprint("农场_1", 101L, 5L)
        assertEquals("同文件指纹相同", a, b)
        assertTrue("大小不同指纹不同", a != c)
    }
}
