package com.stardew.assistant.data

/**
 * v2.86.0：Steam 云存档的**内容嗅探**（纯逻辑，无 Android 依赖，可进 JVM 单测）。
 *
 * ## 这个文件解决什么
 * 用户从 Steam 官网下载云存档，浏览器把文件丢进「下载」目录。这个目录里
 * 混着各种东西：主存档、SaveGameInfo、`_old` 备份、Chrome 的 `.crdownload` 分片、
 * 半路写了一半的文件……我们要**只挑出那一个主存档**，其余一律不碰。
 *
 * ## 判定依据（四份真实存档实测，见下方「实测校准」）
 * 主存档判定 = **根标签是 `<SaveGame>` 且前若干 KB 内含 `<player>`**。
 *
 * ⚠ **不要用「含 `<locations>` 或 `<stats>`」当判据** —— 实测会两头都错：
 * - `ff` 与 `让我们重逢` 两档的**整个文件都没有** `<locations>` 节点；
 * - `让我们重逢` 连 `<stats>` 都没有；
 * - 而 `SaveGameInfo` **反而含有** `<locations>` 和 `<stats>`（位置 33KB / 34KB）。
 * ⇒ 用 locations/stats 判：真存档被判假，摘要被判真。**方向正好相反。**
 *
 * ## 实测校准（四档真实存档，仓库 `存档/_analysis_tmp/`）
 * | 文件 | 根标签 | 前 16KB 有 `<player>` | 判定 |
 * |---|---|---|---|
 * | `ff_312128132` | `<SaveGame>` | 是 | 主存档 ✓ |
 * | `穿越_447169055` | `<SaveGame>` | 是 | 主存档 ✓ |
 * | `穿越_447169055_old` | `<SaveGame>` | 是 | 备份 ✗（按文件名排除）|
 * | `让我们重逢_386818827` | `<SaveGame>` | 是 | 主存档 ✓ |
 * | `KKK_387956097` | `<SaveGame>` | 是 | 主存档 ✓ |
 * | `KKK_387956097_SVBAK` | `<SaveGame>` | 是 | 游戏备份 ✗（按后缀排除）|
 * | `SaveGameInfo` | **`<Farmer>`** | 否 | 摘要 ✗ |
 * | `SaveGameInfo_old` | **`<Farmer>`** | 否 | 摘要 ✗ |
 * | `BACKUP_SAVE` | 无 XML | 否 | 非存档 ✗ |
 * | `spacecore-serialization*.json` | 无 XML | 否 | 非存档 ✗ |
 *
 * ⚠ **`SaveGameInfo` 的根标签是 `<Farmer>`，不是 `<SaveGameInfo>`**（任务书里写的
 *   `<SaveGameInfo>` 是错的）。实测四个文件里 `SaveGameInfo` / `SaveGameInfo_old`
 *   的根都是 `<Farmer>`。所以**不能**只靠「排除文件名含 SaveGameInfo」——
 *   根标签判定才是可靠的，文件名排除只是第二道防线（防有人改了文件名）。
 *
 * ## 为什么只读前若干 KB
 * 主存档有 2MB~36MB，整文件读进内存既慢又没必要。根标签和 `<player>` 都在
 * **最前面几百字节**（实测 `<SaveGame>` 在第 38 字节、`<player>` 在第 147 字节），
 * 所以读 16KB 足够，还留了 100 倍余量。
 *
 * ## BOM
 * 四份存档全部以 `EF BB BF` 开头。解码前必须跳过 BOM，否则根标签会变成
 * `\uFEFF<SaveGame>` 的前导字符，判定就废了。
 */
object SteamSaveSniffer {

    /**
     * 嗅探时读的字节数。16KB。
     *
     * 实测 `<player>` 最早在第 147 字节、最晚在第 147 字节（各档一致），
     * 16KB 有 100 倍余量。**不能更小** —— 根标签本身就在第 38 字节，
     * 若只读 64 字节，遇到头部注释更长的存档就会读不到根标签。
     */
    const val SNIFF_BYTES = 16 * 1024

    /**
     * 排除的文件名后缀（ASCII 结尾匹配）。
     *
     * 与 [SaveImport.ZIP_EXCLUDE_SUFFIX] 同源：`_old` 是上一份存档，
     * `_SVBAK` / `_SVEMERG` 是游戏自己写的合并中存档。
     * ⚠ **它们都可能比主存档大**（实测 KKK 档 `_SVBAK` 36.1MB > 主存档 35.6MB），
     * 所以「取最大的那个」必然选错 —— 必须按后缀排除。
     */
    val EXCLUDE_SUFFIX = listOf("_old", "_SVBAK", "_SVEMERG")

    /**
     * 排除的确切文件名（不分大小写）。
     *
     * `SaveGameInfo` 是摘要（根标签 `<Farmer>`），不是存档本体。
     * `BACKUP_SAVE` 是游戏写的 48 字节标记文件。
     */
    val EXCLUDE_EXACT = setOf("savegameinfo", "backup_save")

    /**
     * Chrome / 系统浏览器的**下载中**临时后缀。
     *
     * 这些文件还在写（或写完但没改名），**读到的内容是残缺的**——
     * 嗅探会失败或（更糟）半路上恰好出现 `<player>` 而误判成主存档。
     */
    val TEMP_SUFFIX = listOf(".crdownload", ".tmp", ".part", ".partial", ".download")

    /**
     * 嗅探结论。
     *
     * @param isMainSave true = 这是主存档（根 `<SaveGame>` 且含 `<player>`）
     * @param rootTag 嗅探到的根标签（没读到就是空串）
     * @param reason 人类可读的判断依据（失败时给用户看为什么）
     */
    data class Sniff(
        val isMainSave: Boolean,
        val rootTag: String,
        val reason: String,
    )

    /**
     * 判断一段**文件头字节**是不是主存档。
     *
     * @param head 文件的前若干字节（不必够 [SNIFF_BYTES]，有多少读多少）
     * @param fileName 文件名（用于排除规则；可为空串）
     */
    fun sniff(head: ByteArray, fileName: String = ""): Sniff {
        // ① 文件名排除：临时分片与备份先排掉。
        //    顺序有讲究：临时后缀**优先**于其它排除规则，
        //    因为「下载中的主存档」文件名可能是「农场名_123.crdownload」，
        //    它既不是 _old 也不是 SaveGameInfo，只能靠临时后缀认出来。
        if (isTempName(fileName)) {
            return Sniff(false, "", "还在下载中（临时文件）")
        }
        if (isExcludedName(fileName)) {
            return Sniff(false, "", "不是主存档（备份或摘要文件）")
        }

        // ② 内容判定：根标签 + player。
        if (head.isEmpty()) {
            return Sniff(false, "", "文件是空的")
        }
        val text = decodeHead(head)
        if (text.isBlank()) {
            return Sniff(false, "", "不是文本文件（可能是二进制或压缩包）")
        }
        val root = rootTagOf(text)
        if (root.isEmpty()) {
            return Sniff(false, "", "不是 XML 存档文件")
        }
        if (!root.equals("SaveGame", ignoreCase = true)) {
            // SaveGameInfo 的根是 Farmer；这里如实说清楚它是什么。
            return Sniff(false, root, "不是主存档（根节点是 <$root>）")
        }
        if (!text.contains("<player")) {
            return Sniff(false, root, "不是主存档（缺少 <player> 节点）")
        }
        return Sniff(true, root, "主存档")
    }

    /**
     * 解码文件头：UTF-8 + **跳过 BOM**。
     *
     * ⚠ 四份真实存档全部带 BOM（`EF BB BF`），不跳的话根标签会变成
     * `\uFEFF<SaveGame>`，取标签时拿到的是空串 —— 全部存档都会被判成「不是 XML」。
     */
    fun decodeHead(head: ByteArray): String {
        var off = 0
        if (head.size >= 3 &&
            head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        ) {
            off = 3
        }
        return String(head, off, head.size - off, Charsets.UTF_8)
    }

    /**
     * 取根标签名。
     *
     * 只认「文档里第一个形如 `<Tag` 的**元素**」：跳过 `<?xml ?>` 声明、
     * `<!-- -->` 注释、`<!DOCTYPE>`。存档文件头部就是这三样之一。
     */
    fun rootTagOf(xml: String): String {
        var i = 0
        val n = xml.length
        while (i < n) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) return ""
            when {
                // <?xml ...?> / <?php ...?>
                xml.startsWith("<?", lt) -> {
                    val end = xml.indexOf("?>", lt)
                    if (end < 0) return ""
                    i = end + 2
                }
                // <!-- ... -->
                xml.startsWith("<!--", lt) -> {
                    val end = xml.indexOf("-->", lt)
                    if (end < 0) return ""
                    i = end + 3
                }
                // <!DOCTYPE ...>（含内部子集 [...]）
                xml.startsWith("<!", lt) -> {
                    var j = lt + 2
                    var depth = 0
                    while (j < n) {
                        when (xml[j]) {
                            '[' -> depth++
                            ']' -> depth--
                            '>' -> if (depth <= 0) break
                        }
                        j++
                    }
                    if (j >= n) return ""
                    i = j + 1
                }
                else -> {
                    // 真正的元素开始标签
                    var j = lt + 1
                    if (j < n && (xml[j] == '/' || xml[j] == '!' || xml[j] == '?')) return ""
                    val start = j
                    while (j < n) {
                        val c = xml[j]
                        if (c.isLetterOrDigit() || c == '_' || c == '-' || c == ':' || c == '.') j++
                        else break
                    }
                    if (j == start) return ""
                    return xml.substring(start, j)
                }
            }
        }
        return ""
    }

    /** 文件名是不是「下载中」的临时文件。 */
    fun isTempName(fileName: String): Boolean {
        if (fileName.isEmpty()) return false
        val lower = fileName.lowercase()
        return TEMP_SUFFIX.any { lower.endsWith(it) }
    }

    /**
     * 文件名是不是该排除的（备份 / 摘要）。
     *
     * 后缀匹配只看 ASCII 结尾 —— 中文存档名在某些文件系统上会乱码，
     * 但结尾那几个 ASCII 字符不受影响。
     */
    fun isExcludedName(fileName: String): Boolean {
        if (fileName.isEmpty()) return false
        if (fileName.lowercase() in EXCLUDE_EXACT) return true
        return EXCLUDE_SUFFIX.any { fileName.endsWith(it, ignoreCase = true) }
    }

    /**
     * 从文件名里取出**农场名**（去掉结尾的数字 ID）。
     *
     * 星露谷的存档文件夹名形如「农场名_123456789」，数字部分是存档 ID。
     * 展示时只要农场名。
     */
    fun farmNameOf(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName)
        val idx = base.lastIndexOf('_')
        if (idx <= 0) return base
        val tail = base.substring(idx + 1)
        // 尾巴必须全是数字才是 ID（`_old` 那种已被排除，这里再兜一层）
        if (tail.isNotEmpty() && tail.all { it.isDigit() }) return base.substring(0, idx)
        return base
    }

    /**
     * 候选排序：**主存档优先 → 修改时间新 → 体积大**。
     *
     * ⚠ 体积排在最后不是随便定的：`_SVBAK` / `_SVEMERG` 比主存档还大，
     * 但它们已被文件名排除规则排掉；剩下的候选里体积大的更可能是主存档
     * （`SaveGameInfo` 只有几百 KB，主存档是 MB 级）。
     */
    fun compareCandidates(a: Candidate, b: Candidate): Int {
        if (a.isMainSave != b.isMainSave) return if (a.isMainSave) -1 else 1
        if (a.lastModified != b.lastModified) return b.lastModified.compareTo(a.lastModified)
        if (a.size != b.size) return b.size.compareTo(a.size)
        return a.name.compareTo(b.name)
    }

    /**
     * 一个候选文件。
     *
     * @param uri SAF / MediaStore 的内容 Uri（真正读数据的入口）
     * @param name 显示用文件名
     * @param size 字节数
     * @param lastModified 最后修改时间（毫秒）
     * @param isMainSave 嗅探结论
     * @param reason 嗅探依据（给用户看的失败原因）
     */
    data class Candidate(
        val uri: String,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val isMainSave: Boolean,
        val reason: String,
    ) {
        /** 农场名（从文件名推）。 */
        val farmName: String get() = farmNameOf(name)
    }
}
