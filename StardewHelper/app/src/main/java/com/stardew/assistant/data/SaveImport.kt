package com.stardew.assistant.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * v2.65：导入游戏存档，把已完成的图鉴条目自动勾上。
 *
 * ## 只读，绝不写回
 * 只从存档**读**。永不修改 Steam 存档文件——写回丢了农场、或与 Steam 云同步
 * 撞车的代价，远大于「App 里少勾几条」的收益。
 *
 * ## 为什么必须有映射表
 * App 包内 49 个 data JSON **全是中文名，没有一个带 item_id 或英文名**；
 * 游戏存档给的全是数字 ID（`(O)131`）或英文名（`Fried Egg`）。两边对不上。
 * `assets/data/saveImportMap.json`（由 `tools/gen_save_import_map.py` 生成，
 * 覆盖 App 侧 338 / 560 条）是唯一的桥。**映射查不到就静默跳过，绝不瞎猜**——
 * 勾错条目比不勾更糟。
 *
 * ## 六个实测陷阱（全部来自 `存档/` 下四份真实存档，不是推测）
 *
 * 1. **键类型双形态**：`ff`（MOD 档）写 `<key><int>113</int></key>`，
 *    `KKK`/`穿越`/`让我们重逢`（原版档）写 `<key><string>111</string></key>`。
 *    只认一种不会报错，会**静默全空**。
 * 2. **节点可能整个不存在**：`穿越` 档 `fishCaught` 是自闭合的 `<fishCaught />`。
 *    缺失/空是**正常情况**，该组计 0 条，不算失败。
 * 3. **制作配方的 0 值陷阱**：`craftingRecipes` 的 value=0 表示「已解锁未制作」，
 *    只判 key 存在会多勾。制作组必须 `value > 0`。
 * 4. **ZIP 里选错文件**：`KKK` 压缩包含 `_SVBAK`(36.1MB) 与 `_SVEMERG`(36.1MB)，
 *    **都比主存档(35.6MB) 大**——「取最大」必然选错。必须按文件名后缀排除。
 * 5. **BOM**：四份存档全部以 `EF BB BF` 开头，直接喂给 XmlPullParser 会炸。
 * 6. **博物馆认「捐赠」不认「捡到」**（v2.66.0 起）：`mineralsFound` /
 *    `archaeologyFound` 是**捡到过**，而游戏 1.6 起**收藏页只统计捐赠**——
 *    捐了才算进 Museum 那一栏。所以博物馆组改读
 *    `locations/GameLocation[xsi:type="LibraryMuseum"]/museumPieces`。
 *
 * ## museumPieces 有三个反直觉之处（都踩过）
 *
 * - **不在 `player` 子树内**，在 `locations` 下。前五个容器都在 `player` 下，
 *   靠 `playerDepth` 就能圈住；它必须单独记 `museumDepth`。
 * - **键不是 ID**：`<key>` 是 `<Vector2>` 展柜坐标，**`<value>` 才是 item_id**。
 *   所以它 `idFromValue = true`；`<key>` 里的 `<X>/<Y>` 会被现有的
 *   `int`/`string` 分支天然忽略。
 * - **两个分组共用这一个标签**：矿物与古物都从 `museumPieces` 取，靠映射表区分。
 *   所以分桶必须按 `groupId`（**不能按 `tag`**，否则两个桶互相覆盖成一个，
 *   而且数量看起来仍然合理、极难察觉）。
 *
 * ## 为什么博物馆要留回退
 * `museumPieces` 只在**进过博物馆**的存档里有条目。四档实测都有（8~95 条），
 * 但老存档/极端情况可能整个节点缺失。捐赠集合恒为「找到过」的子集
 * （四档实测无例外），所以回退到 `mineralsFound`/`archaeologyFound`
 * **只可能多勾、不会少勾或错勾**，比博物馆整组空掉好。回退会在预览里说明。
 *
 * ## 命中数按「唯一条目」计，不按存档条数累加
 * 分母 `denom` 是映射表去重后的条目数，`hit` 若按存档条目累加，
 * 两者口径不同——存档里一旦出现重复键就会显示 `hit > denom` 的荒谬数字。
 * 四份存档当前无重复键，但那只是这四份的事实，不是结构保证。
 * 故 `hit` 取唯一条目集合的 size。
 *
 * ## 为什么拷进 cacheDir 再用 ZipFile
 * SAF 给的是 `content://` Uri 而不是文件路径；`ZipInputStream` 只能顺序读、不能回退，
 * 做不到「先看所有 entry 的大小再挑一个」。压缩包本身只有 115KB~1.1MB
 * （35MB 是解压后的主存档），拷进 cache 换 `ZipFile` 的随机访问能力，成本可忽略。
 *
 * ## 深度计数与叶子取值
 * 深度用 `parser.depth`，不自己数——它由解析器维护，不受事件消费顺序影响。
 * 叶子取值**必须走 [readLeaf]**：START_TAG 上直接读文本拿不到（恒 null，会静默产出
 * 空导入），`nextText()` 又会连带吃掉 END_TAG（深度匹配随之错位）。两头都不能用。
 *
 * ## 线程
 * 解析 35MB 单行 XML 是秒级操作，**调用方必须在后台线程**（UI 层包 `Dispatchers.IO`）。
 * 本文件不切线程，只保证纯逻辑、无 UI 依赖。
 */
object SaveImport {

    /** 压缩包大小上限。正常 Steam 存档压完 < 2MB，留 10 倍余量。 */
    private const val MAX_ARCHIVE_BYTES = 20L * 1024 * 1024

    /**
     * 存档里要读的容器 → App 图鉴分组。
     *
     * `sections` 是**按顺序尝试**的映射表分区。钓鱼特殊：`fishCaught` 的键
     * 既可能是 `(O)131` 这种数字 ID，也可能是 `(O)SeaJelly` 这种 1.6 新鱼
     * 的字符串 ID，所以挂两张表。
     *
     * 注意 `museumMinerals` 与 `museumArtifacts` **共用 `museum:` 前缀**，
     * 靠中文名区分。两组条目名无交集（verify 第 58 节断言 6 兜住），
     * 一旦日后数据更新引入同名条目就会撞键。
     */
    private class Container(
        val tag: String,
        val groupId: String,
        val title: String,
        val sections: List<String>,
        /** 该组是否要求 value > 0（制作组：0 = 已解锁未制作）。 */
        val requirePositive: Boolean = false,
        /** 该组是否需要剥掉 `(O)` 前缀（钓鱼专用）。 */
        val stripObjectPrefix: Boolean = false,
        /**
         * 该组是否在 `player` 子树**之外**（v2.66.0 博物馆组专用）。
         * `museumPieces` 挂在 `locations/GameLocation[LibraryMuseum]` 下，
         * 靠 `playerDepth` 圈不到，要单独记 `museumDepth`。
         */
        val outsidePlayer: Boolean = false,
        /**
         * 该组是否从 `<value>` 读 ID（v2.66.0 博物馆组专用）。
         * `museumPieces` 的 `<key>` 是 `<Vector2>` 展柜坐标，`<value>` 才是 item_id。
         */
        val idFromValue: Boolean = false,
    ) {
        /** 复制一份并改掉 `tag`，用于给回退容器建桶（分组与映射分区保持不变）。 */
        fun withTag(newTag: String) = Container(newTag, groupId, title, sections,
            requirePositive, stripObjectPrefix, outsidePlayer, idFromValue)
    }

    /**
     * `mineralsFound` / `archaeologyFound` 是「捡到过」，`museumPieces` 是「已捐赠」。
     * 游戏 1.6 起收藏页只认捐赠，故博物馆两组的 `tag` 指向 `museumPieces`；
     * 前者在 [parseStream] 里作为**回退**使用（读不到捐赠记录时兜底）。
     */
    private val CONTAINERS = listOf(
        Container("fishCaught", "fishing", "钓鱼图鉴", listOf("fishById", "fishByName"), stripObjectPrefix = true),
        Container("recipesCooked", "recipes", "烹饪图鉴", listOf("recipeById", "recipeByName")),
        Container("craftingRecipes", "crafting", "制作图鉴", listOf("craftByEn"), requirePositive = true),
        Container("museumPieces", "museumMinerals", "博物馆·矿物", listOf("mineralById"),
                  outsidePlayer = true, idFromValue = true),
        Container("museumPieces", "museumArtifacts", "博物馆·古物", listOf("artifactById"),
                  outsidePlayer = true, idFromValue = true),
    )

    /**
     * 博物馆的「找到过」容器，作为 `museumPieces` 缺失时的回退。
     *
     * 捐赠恒为找到的子集（四档实测无例外），所以回退只多不少，不会勾错。
     * 这两条**不进** `CONTAINERS`：它们不是独立分组，只是同一分组的备用数据源，
     * 混进去会让 `denom` 多算一遍分母。
     */
    private val MUSEUM_FALLBACK = mapOf(
        "museumMinerals" to "mineralsFound",
        "museumArtifacts" to "archaeologyFound",
    )

    /**
     * ZIP 里要排除的 entry。
     *
     * `_SVBAK` / `_SVEMERG` 是游戏自己写的合并中存档，`_old` 是上一份，
     * `SaveGameInfo` 是摘要（不是存档本体）。**它们都可能比主存档大**。
     *
     * 排除只看 ASCII 后缀，所以即使中文存档名因 UTF-8 flag 未置而解码成乱码，
     * 筛选依然正确。
     */
    private val ZIP_EXCLUDE_SUFFIX = listOf("_old", "_SVBAK", "_SVEMERG")
    private val ZIP_EXCLUDE_EXACT = setOf("SaveGameInfo", "BACKUP_SAVE")

    /** 某组的导入结果。`total` 是映射表覆盖数（分母），`hit` 是本次存档命中数。 */
    data class GroupStat(val title: String, val groupId: String, val hit: Int, val total: Int) {
        val label: String get() = "$title $hit/$total"
    }

    sealed interface Result {
        /** 解析成功。`keys` 是可直接并入 checks 的打勾键集合。 */
        data class Ok(
            val keys: Set<String>,
            val groups: List<GroupStat>,
            /** 存档里有、但映射表查不到的条目数（不勾选，只如实告知）。 */
            val unmapped: Int,
            val farmerName: String?,
            /**
             * v2.66.0：博物馆是否走了「找到过」回退。
             * `museumPieces` 只在进过博物馆的存档里有条目，读不到时退回
             * `mineralsFound`/`archaeologyFound`。捐赠恒为找到的子集，所以回退
             * 只多不少、不会勾错，但用户该知道看到的数字是哪个口径算出来的。
             */
            val museumFellBack: Boolean = false,
            /**
             * v2.74.0：同一份存档里读出来的「农场年度报告」统计。
             *
             * 为 null 只有两种情况：解析失败，或这份存档没有可用的统计。
             * **不影响导入本身**——导入要的是打勾键，报告只是搭车带出来的。
             */
            val report: FarmReport? = null,
        ) : Result

        /** 任何失败。`msg` 直接给用户看，必须说清「怎么改」。 */
        data class Err(val msg: String) : Result
    }

    // ── 对外入口 ────────────────────────────────────────────────────────

    /**
     * v2.81.0：**主存档本体**的取流方式。
     *
     * 导入存档要做两件事：勾图鉴进度（[parse] 的老本行）与读世界状态（作物/牲畜/背包）。
     * 两件事都要读**同一份**主存档，但它们的状态机是两套（前者绕不开那七个坑，
     * 后者要按序号归属地图），所以这里把「主存档怎么再开一次」交给调用方，
     * 而不是让 [parse] 变成两套逻辑合一。
     *
     * ⚠ [bytes] 是主存档的字节数 —— 手机拿到的是 PC 存档的**副本**，副本的修改时间
     *   每次导入都变（毫无意义），字节数才是「存档变没变」能拿到的最稳指纹。
     */
    fun interface MainSaveSource {
        /** 再开一次主存档的输入流。调用方负责关闭。 */
        fun open(): InputStream

        /** 主存档的字节数（未知给 0）。 */
        val bytes: Long get() = 0
    }

    /**
     * 从用户选中的 Uri 解析存档。**必须在后台线程调用。**
     *
     * 返回 [Result.Ok] 或 [Result.Err]，不抛异常——所有失败都收敛成可读文案。
     *
     * @param onMainSave 解析成功时回调一次，把主存档本体交出去（v2.81.0 世界状态用）。
     *   **在同一个后台线程里同步调用**，所以回调里可以直接做耗时解析。
     *   回调抛异常会被吞掉（不影响导入结果）——世界状态是附加功能，失败不该连累勾选。
     */
    fun parse(ctx: Context, uri: Uri, onMainSave: ((MainSaveSource) -> Unit)? = null): Result {
        val map = loadMap(ctx)
        val cache = File(ctx.cacheDir, "save_import_${System.currentTimeMillis()}.tmp")
        return try {
            val copied = copyToCache(ctx, uri, cache)
            if (copied == null) return Result.Err("读取文件失败，请重新选择")
            if (copied > MAX_ARCHIVE_BYTES) {
                return Result.Err(tooLargeMsg(copied))
            }
            parseFile(cache, map, onMainSave)
        } catch (e: Throwable) {
            Result.Err("读取存档失败：${e.message ?: "未知错误"}")
        } finally {
            runCatching { cache.delete() }
        }
    }

    /**
     * v2.67.0：解析**已经是本地文件**的 zip/裸 XML（局域网接收到的包走这里）。
     *
     * 与 [parse] 共用同一段判定与解析逻辑——接收到的字节属于**不可信输入**，
     * 校验（zip 签名、大小、能不能解析出 `<SaveGame>`）一步都不能少，
     * 所以这里刻意**不新写一条通道**，而是收敛到同一个 [parseFile]。
     *
     * ⚠ 调用方负责删除传入的文件（接收服务写在 cacheDir 里，用完即删）。
     *
     * @param onMainSave 同 [parse]：解析成功时把主存档本体交出去（v2.81.0 世界状态用）。
     */
    fun parseFile(ctx: Context, file: File, onMainSave: ((MainSaveSource) -> Unit)? = null): Result {
        val map = loadMap(ctx)
        return try {
            if (!file.exists()) return Result.Err("接收到的文件已不存在，请重新发送一次")
            val size = file.length()
            if (size > MAX_ARCHIVE_BYTES) {
                runCatching { file.delete() }
                return Result.Err(tooLargeMsg(size))
            }
            parseFile(file, map, onMainSave)
        } catch (e: Throwable) {
            Result.Err("解析存档失败：${e.message ?: "未知错误"}")
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun tooLargeMsg(bytes: Long) =
        "文件太大（${bytes / 1048576} MB）。正常 Steam 存档压缩后不到 2 MB，" +
            "请确认压缩的是 Save_Game 下的**农场文件夹**，而不是整个游戏目录。"

    /** zip 还是裸 XML 的分流判定，两条入口共用。 */
    private fun parseFile(
        file: File,
        map: Map<String, Map<String, String>>,
        onMainSave: ((MainSaveSource) -> Unit)? = null,
    ): Result {
        val head = ByteArray(2)
        file.inputStream().use { it.read(head) }
        return if (head.size == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            parseZip(file, map, onMainSave)
        } else {
            // 顺手支持裸 XML（用户直接选了未压缩的存档文件）
            val r = parseStream(file.inputStream().buffered(), map)
            // v2.81.0：裸 XML 时主存档就是它自己
            if (r is Result.Ok) {
                runCatching {
                    onMainSave?.invoke(
                        object : MainSaveSource {
                            override fun open(): InputStream = file.inputStream().buffered()
                            override val bytes: Long = file.length()
                        }
                    )
                }
            }
            withReport(r) { file.inputStream().buffered() }
        }
    }

    /**
     * v2.74.0：给解析结果**补上**农场报告统计。
     *
     * ## 为什么是「再读一遍」而不是一趟解析出两样
     * 本文件那个状态机是围绕「五个容器的映射表」写的，逻辑密、踩过七个坑，
     * 往里塞统计字段等于在雷区加脚手架。所以统计由独立的 [FarmReportParser] 负责，
     * 代价是**这个文件要被 tokenize 两遍**（4~5MB 的真档约多花 0.2~0.5 秒）。
     * 换来的是：统计解析坏了**不会**影响打勾导入，反之亦然。
     *
     * 统计解析失败一律吞成 null——它是搭车的，失败不该拦住导入。
     */
    private fun withReport(r: Result, open: () -> InputStream): Result {
        if (r !is Result.Ok) return r
        val report = try {
            open().use { FarmReportParser.parse(it) }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "农场报告统计解析失败：${t.message}")
            null
        }
        return r.copy(report = report)
    }

    private fun copyToCache(ctx: Context, uri: Uri, dest: File): Long? = runCatching {
        val ins = ctx.contentResolver.openInputStream(uri) ?: return null
        var written = 0L
        ins.use { i ->
            dest.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n <= 0) break
                    written += n
                    o.write(buf, 0, n)
                }
            }
        }
        written
    }.getOrNull()

    // ── ZIP 处理 ────────────────────────────────────────────────────────

    /** 打开 zip 并枚举完条目。`cs` 是实际生效的 entry 名解码 charset。 */
    private class Opened(val zf: ZipFile, val all: List<ZipEntry>, val cs: String)

    /**
     * 打开 zip，entry 名的解码 charset 按需回退。
     *
     * ⚠ **v2.65.1 修的第三个 bug，且是唯一一个只有真机才复现的。**
     *
     * Windows 压中文文件名的 zip 时经常**不置 UTF-8 标志**（general purpose bit 11），
     * 而把名字按系统本地编码（中文 Windows = GBK / GB18030）写进 central directory。
     * `ZipFile` 默认按 UTF-8 解码，GBK 中文字节不是合法 UTF-8 序列 → 抛
     * `ZipException: MALFORMED[1]`，真机上导入带中文的存档**必撞**。
     *
     * 为什么模拟器测不出来：模拟器那版实现宽松，把 GBK 字节按 ISO-8859-1 之类解成
     * **乱码而不抛**（日志里看到的就是 `�������ط�_386818827`），于是测试全绿、
     * 真机全红。这与本项目反复出现的「本地环境 ≠ 目标环境」是同一类。
     *
     * 回退顺序：默认(UTF-8) → GB18030(Windows 中文) → CP437(zip 规范代码页)。
     * CP437 是单字节表、**永不抛解码异常**，所以最坏也能列出条目——名字可能乱码，
     * 但排除名单（`_old` / `_SVBAK` / `SaveGameInfo`）全是 **ASCII**，乱码中
     * ASCII 部分仍保留，`endsWith` / `in setOf` 照常生效。
     *
     * 解码只影响 entry **名字**，不影响 entry **内容**，所以内容始终是正确的。
     *
     * @return null 表示文件根本不是 zip（构造就抛），由调用方给「不是有效 zip」文案
     */
    private fun openZip(zipFile: File): Opened? {
        val probes: List<Charset?> = listOf(
            null,                                // 默认：平台按 UTF-8 解码 entry 名
            Charset.forName("GB18030"),          // Windows 中文 zip 的本地编码（GBK 超集）
            Charset.forName("CP437"),            // zip 规范的原始代码页，单字节不抛
        )
        var constructFailed = false
        var listingFailed: Throwable? = null
        for (cs in probes) {
            val zf = try {
                if (cs == null) ZipFile(zipFile) else ZipFile(zipFile, cs)
            } catch (e: Exception) {
                constructFailed = true
                continue
            }
            val all = try {
                zf.entries().asSequence().toList()
            } catch (e: Throwable) {
                listingFailed = e
                runCatching { zf.close() }
                continue
            }
            return Opened(zf, all, cs?.name() ?: "默认UTF-8")
        }
        // 全部 charset 都不行：只有「构造都没成功过」才说明不是 zip；
        // 构造成功过但枚举全抛，是真实的解码/损坏问题，要如实上报。
        if (constructFailed && listingFailed == null) return null
        // 只抛真实原因，不加前缀——外面会统一包「读取压缩包目录失败：」，
        // 两处都写的话文案会变成「读取压缩包目录失败：读取压缩包目录失败：…」。
        throw java.io.IOException(listingFailed?.message ?: "未知原因")
    }

    private fun parseZip(
        zipFile: File,
        map: Map<String, Map<String, String>>,
        onMainSave: ((MainSaveSource) -> Unit)? = null,
    ): Result {
        val opened = try {
            openZip(zipFile)
        } catch (e: Throwable) {
            return Result.Err(
                "读取压缩包目录失败：${e.message ?: "未知错误"}。" +
                    "请用 Windows 右键「压缩为 ZIP 文件」重新打包一次。"
            )
        } ?: return Result.Err(
            "这个文件不是有效的 zip（可能是 rar / 7z）。" +
                "请用 Windows 右键「压缩为 ZIP 文件」重新打包。"
        )
        val zf = opened.zf
        // size **只用于排序，不用于过滤**：entry 若未记录未压缩大小，
        // getSize() 返回 -1，拿它当 `> 0` 的门槛会把真存档滤成空。
        val all = opened.all
        android.util.Log.i(
            TAG,
            "zip charset=${opened.cs} entries=${all.size} names=" +
                all.take(6).joinToString { it.name.takeLast(46) }
        )
        zf.use {
            // ⚠ 这段不能用 runCatching { ... }.getOrElse { emptyList() }。
            // 枚举条目会在**解码 entry 名**时抛（中文存档名、flag bit 未置的 zip 都可能撞），
            // 吞成空列表之后，用户看到的是「压缩包里没找到存档文件」——一句完全误导的
            // 诊断，真实原因（解码失败）被藏起来了。宁可把异常原样端出去。
            val kept = all.filter { !it.isDirectory && !isExcluded(it.name) }
            android.util.Log.i(TAG, "kept=${kept.size}/${all.size}")
            if (kept.isEmpty()) {
                // 列出实际条目，让「压缩的不是这个」这类问题一眼可辨，
                // 而不是靠一句笼统的「没找到」让用户猜。
                val show = all.take(6).joinToString { it.name.takeLast(46) }
                return Result.Err(
                    "压缩包里没找到存档文件（内含 ${all.size} 项：$show）。" +
                        "请压缩 `%APPDATA%\\StardewValley\\Save_Game\\<农场名>\\` 整个文件夹，而不是上级目录。"
                )
            }
            val candidates = kept.sortedByDescending { it.size.coerceAtLeast(0L) }

            // 依次尝试，直到有一份能解析出 <SaveGame>。
            // 与其赌「最大的那个就是主存档」，不如让解析器自己判定——
            // 空存档、合并档、命名异常都会被跳过，用户无感。
            var lastErr: Result.Err? = null
            for (entry in candidates) {
                val r = try {
                    zf.getInputStream(entry).use { parseStream(it, map) }
                } catch (e: Throwable) {
                    Result.Err("解析失败：${e.message ?: "未知错误"}")
                }
                // ⚠ 不能写 `r::class.simpleName`：release 走 R8 混淆，Ok/Err 会变成 x0/x1，
                //    这行就废了（分不出成败，诊断日志本身要先可读）。改成显式判定 + 带条数。
                android.util.Log.i(
                    TAG,
                    "entry ${entry.name.takeLast(46)} -> " +
                        if (r is Result.Ok) "Ok keys=${r.keys.size} unmapped=${r.unmapped}" else "Err"
                )
                if (r is Result.Ok) {
                    // v2.81.0：解析成功了 → 把**主存档本体**交出去（世界状态要再读一遍同一份）。
                    // ⚠ 在 zf 关闭前同步调用：回调里要能自己再开一次流。
                    //   回调抛异常也不影响导入结果（世界状态是附加功能）。
                    runCatching {
                        onMainSave?.invoke(
                            object : MainSaveSource {
                                override fun open(): InputStream = zf.getInputStream(entry)
                                override val bytes: Long = entry.size
                            }
                        )
                    }
                    // v2.74.0：同一个 entry 再读一遍，取农场报告统计（见 withReport 的取舍说明）
                    return withReport(r) { zf.getInputStream(entry) }
                }
                if (r is Result.Err) lastErr = r
            }
            return lastErr ?: Result.Err("压缩包里所有文件都解析不出存档内容")
        }
    }

    private fun isExcluded(name: String): Boolean {
        val base = name.substringAfterLast('/')
        if (base in ZIP_EXCLUDE_EXACT) return true
        return ZIP_EXCLUDE_SUFFIX.any { base.endsWith(it, ignoreCase = true) }
    }

    // ── 流式解析 ────────────────────────────────────────────────────────

    private const val TAG = "SaveImport"

    /**
     * 读一个**叶子元素**的文本，调用时解析器必须停在该元素的 START_TAG。
     *
     * ⚠ 两处踩过的坑，改这里之前先看完：
     *
     * ① **不能直接读 `parser.text`**：START_TAG 事件上 `getText()` 恒为 `null`
     *    （XmlPullParser 契约：只有 TEXT 事件才有文本）。直接读的话 key/value
     *    **永远赋不上值**，所有容器产出 0 条，用户看到的是「没有可勾选的进度」——
     *    不报错、不崩，就是空。这是 v2.65.0 首个设备实测就撞上的 bug。
     *
     * ② **也不能用 `nextText()`**：它会把本元素的 END_TAG **一并吃掉**。而下面的
     *    END_TAG 分支靠 `parser.depth` 匹配 item / container / player 来收状态，
     *    少一个事件就会错位。
     *
     * 所以是手工推进一格到 TEXT：读到文本后**停在那里**，让下一轮循环的
     * `parser.next()` 正常拿到 END_TAG，深度匹配不受影响。
     * 元素为空（`<int></int>`）时会直接碰到 END_TAG，返回 null——同样无害。
     */
    private fun readLeaf(p: XmlPullParser): String? = try {
        if (p.next() == XmlPullParser.TEXT) p.text else null
    } catch (e: Exception) {
        null
    }

    private class RawEntry(val key: String, val value: Int?)

    /**
     * 当前元素是不是博物馆位置。
     *
     * 存档里形如 `<GameLocation xsi:type="LibraryMuseum">`。**遍历属性**而不按名直取：
     * 本解析器关掉了 `FEATURE_PROCESS_NAMESPACES`，属性名按字面存成 `xsi:type`，
     * 但不同 XML 实现对「带前缀的裸属性名」的处理有细微差别（有的会剥前缀），
     * 遍历一次最稳，也顺带容忍将来存档改成别的命名空间前缀。
     */
    private fun isLibraryMuseum(p: XmlPullParser): Boolean {
        for (i in 0 until p.getAttributeCount()) {
            if (p.getAttributeName(i) == "xsi:type" &&
                p.getAttributeValue(i)?.contains("LibraryMuseum") == true
            ) return true
        }
        return false
    }

    private class Bucket(val container: Container, val entries: MutableList<RawEntry> = mutableListOf())

    /**
     * XmlPullParser 状态机。
     *
     * 只跟踪「深度 + 当前是否在五个目标容器内」，其余一律跳过。
     * 不建完整树、不把 35MB 读成 String。
     */
    private fun parseStream(input: InputStream, map: Map<String, Map<String, String>>): Result {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(skipBom(input), "UTF-8")

        // ⚠ 分桶**必须按 groupId**，不能按 tag：`museumMinerals` 与 `museumArtifacts`
        //   共用 `museumPieces` 一个 XML 标签，按 tag 分会让两个桶互相覆盖成一个，
        //   而且各组数量看起来仍然合理——这种错极难在预览里看出来。
        val buckets = CONTAINERS.associateBy { it.groupId }
            .mapValues { Bucket(it.value) }
        // 回退容器另开一批桶（不进 CONTAINERS，避免分母被多算一遍）
        val fallbacks = MUSEUM_FALLBACK.mapValues { (gid, tag) ->
            Bucket(CONTAINERS.first { it.groupId == gid }.withTag(tag))
        }

        var playerDepth = -1
        var museumDepth = -1
        /** 当前激活的桶。museumPieces 一个标签要同时喂矿物与古物两个桶，故为列表。 */
        var active: List<Bucket> = emptyList()
        var currentDepth = -1
        var itemDepth = -1
        var wantKey = false
        var wantValue = false
        var key: String? = null
        var value: Int? = null
        var farmerName: String? = null
        var sawRoot = false

        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            val name = parser.name ?: continue
            val d = parser.depth
            when (ev) {
                XmlPullParser.START_TAG -> {
                    if (!sawRoot) {
                        if (name != "SaveGame") return Result.Err("这不是星露谷的存档文件（根节点是 <$name>）")
                        sawRoot = true
                        continue
                    }
                    if (active.isEmpty()) {
                        if (name == "player") {
                            playerDepth = d
                        } else if (name == "GameLocation" && isLibraryMuseum(parser)) {
                            // 博物馆位置在 locations 下，playerDepth 圈不到，单独记深度
                            museumDepth = d
                        } else if (playerDepth >= 0 && name == "name" && d == playerDepth + 1) {
                            // 角色名：只取 player 的直接子节点，避开物品/建筑里同名的 name
                            farmerName = readLeaf(parser)?.trim()
                        } else if (playerDepth >= 0 && d > playerDepth) {
                            // 主容器与博物馆回退容器（mineralsFound / archaeologyFound）
                            // 都可能出现在 player 子树里，各回各的桶
                            val main = CONTAINERS.firstOrNull { !it.outsidePlayer && it.tag == name }
                            if (main != null) {
                                active = listOf(buckets.getValue(main.groupId))
                                currentDepth = d
                            } else {
                                val fb = MUSEUM_FALLBACK.entries
                                    .firstOrNull { it.value == name && fallbacks.containsKey(it.key) }
                                if (fb != null) {
                                    active = listOf(fallbacks.getValue(fb.key))
                                    currentDepth = d
                                }
                            }
                        } else if (museumDepth >= 0 && name == "museumPieces" && d > museumDepth) {
                            // 一个标签喂两个分组：都收，再靠各自的映射表分流
                            active = CONTAINERS.filter { it.outsidePlayer && it.tag == name }
                                .map { buckets.getValue(it.groupId) }
                            currentDepth = d
                        }
                        // player 之外的其他同名节点（如 locations 里没带 xsi:type 的）一律不管
                    } else if (name == "item" && itemDepth < 0) {
                        itemDepth = d
                        key = null
                        value = null
                    } else if (itemDepth >= 0 && name == "key") {
                        // ⚠ 这里**不能**无条件置位（v2.66.0 装机当场发现博物馆整组 0 条）：
                        //   museumPieces 的 <key> 是 <Vector2><X>28</X><Y>10</Y></Vector2>，
                        //   里面没有 int/string，wantKey 会一直挂着；
                        //   于是紧接着 <value> 里的 ID 被 `if (wantKey)` 抢先拿走存进 key，
                        //   value 永远是 null，而 idFromValue 读的正是 value —— 全组丢弃。
                        //   按容器语义决定要不要 key：只有「键就是 ID」的容器才置位。
                        if (active.firstOrNull()?.container?.idFromValue != true) wantKey = true
                    } else if (itemDepth >= 0 && name == "value") {
                        wantValue = true
                    } else if (name == "int" || name == "string") {
                        // 键有两种形态：<int>113</int> 与 <string>111</string>。
                        // 两个分支都要接——只认一种会让另一类存档静默全空。
                        // museumPieces 的 <key> 是 <Vector2>，里面是 <X>/<Y>，
                        // 都不会命中本分支，所以展柜坐标不会被误当成 ID（实测四档）。
                        val text = readLeaf(parser)
                        if (text != null) {
                            if (wantKey) {
                                key = text.trim()
                                wantKey = false
                            } else if (wantValue && value == null) {
                                value = text.trim().toIntOrNull()
                                wantValue = false
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (d == itemDepth) {
                        // idFromValue 的组（博物馆）：<key> 是展柜坐标，ID 在 <value> 里
                        val id = if (active.firstOrNull()?.container?.idFromValue == true) {
                            value?.toString()
                        } else {
                            key
                        }
                        if (id != null) active.forEach { it.entries.add(RawEntry(id, value)) }
                        itemDepth = -1
                        key = null
                        value = null
                        wantKey = false
                        wantValue = false
                    } else if (d == currentDepth) {
                        active = emptyList()
                        currentDepth = -1
                    } else if (d == museumDepth) {
                        museumDepth = -1
                    } else if (d == playerDepth) {
                        playerDepth = -1
                    }
                }
            }
        }
        if (!sawRoot) return Result.Err("文件是空的，或不是 XML")

        // 博物馆回退：读不到捐赠记录时改用「找到过」。捐赠恒为找到的子集
        // （四档实测无例外），所以回退只多不少、不会勾错，比整组空掉好。
        var museumFellBack = false
        for ((gid, fb) in fallbacks) {
            val donated = buckets[gid]?.entries?.size ?: 0
            if (donated == 0 && fb.entries.isNotEmpty()) {
                buckets.getValue(gid).entries.addAll(fb.entries)
                museumFellBack = true
            }
        }

        // 诊断：区分「解析器没读到」与「映射表对不上」这两种完全不同的故障。
        // raw 全 0 = 状态机问题（就该查 parser.text / depth 那几个坑）；
        // raw 有值但 hit=0 = 映射表问题。有了这一行，下次不用靠猜。
        android.util.Log.i(
            TAG,
            "raw " + CONTAINERS.joinToString(" ") { "${it.groupId}=${buckets[it.groupId]?.entries?.size ?: 0}" } +
                " museumPieces=${buckets["museumMinerals"]?.entries?.size ?: 0}" +
                " fellBack=$museumFellBack farmer=$farmerName"
        )

        // ── 查表 → 打勾键 ──
        val keys = linkedSetOf<String>()
        val stats = mutableListOf<GroupStat>()
        var unmapped = 0
        for (c in CONTAINERS) {
            val tables = c.sections.map { map[it] ?: emptyMap() }
            val denom = tables.flatMap { it.values }.toHashSet().size
            // hit 按**唯一条目**计：分母是去重条目数，hit 若按存档条数累加，
            // 存档一旦出现重复键就会显示 hit > denom 的荒谬数字（口径必须一致）。
            val hitNames = linkedSetOf<String>()
            for (e in buckets[c.groupId]?.entries.orEmpty()) {
                if (c.requirePositive && (e.value ?: 0) <= 0) continue
                val probe = if (c.stripObjectPrefix) e.key.removePrefix("(O)").trim() else e.key
                val name = tables.firstNotNullOfOrNull { it[probe] }
                if (name == null) {
                    // 博物馆两组的 entries 是同一批捐赠物：只在该分组查不到就记一次，
                    // 会把「捐了一件宝石」在矿物表和古物表各记一遍，unmapped 直接翻倍
                    // （KKK 实测会虚报到 165）。统一放到循环后按「两张表都查不到」计一次。
                    if (!c.outsidePlayer) unmapped++
                    continue
                }
                val k = codexCheckKey(c.groupId, name)
                if (k == null) unmapped++ else {
                    keys.add(k)
                    hitNames.add(name)
                }
            }
            stats.add(GroupStat(c.title, c.groupId, hitNames.size, denom))
        }
        // 博物馆未识别项：同一批 ID 两张表都查不到才算一条
        val museumEntries = buckets["museumMinerals"]?.entries.orEmpty()
        if (museumEntries.isNotEmpty()) {
            val minT = map["mineralById"].orEmpty()
            val artT = map["artifactById"].orEmpty()
            unmapped += museumEntries.count { e ->
                e.key !in minT && e.key !in artT
            }
        }
        return Result.Ok(keys, stats, unmapped, farmerName, museumFellBack)
    }

    /** 跳 UTF-8 BOM。四份真实存档全部带 BOM，不跳 XmlPullParser 会炸。 */
    private fun skipBom(input: InputStream): InputStream {
        val pb = PushbackInputStream(input, 3)
        val b = ByteArray(3)
        val n = pb.read(b, 0, 3)
        val isBom = n == 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()
        if (!isBom && n > 0) pb.unread(b, 0, n)
        return pb
    }

    // ── 映射表 ──────────────────────────────────────────────────────────

    /** 分区映射表：分区名 → {存档标识: App 条目名}。进程内缓存，解析一次就够。 */
    @Volatile
    private var mapCache: Map<String, Map<String, String>>? = null

    private fun loadMap(ctx: Context): Map<String, Map<String, String>> {
        mapCache?.let { return it }
        val text = ctx.assets.open("data/saveImportMap.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val out = LinkedHashMap<String, Map<String, String>>()
        for (section in CONTAINERS.flatMap { it.sections }.distinct()) {
            val o = root.optJSONObject(section) ?: JSONObject()
            val m = LinkedHashMap<String, String>(o.length())
            o.keys().forEach { k -> m[k] = o.optString(k) }
            out[section] = m
        }
        return out.also { mapCache = it }
    }
}
