package com.stardew.assistant.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * v2.86.0：Steam 云存档的**下载目录**接入。
 *
 * ## ⚠ 为什么是 SAF 目录树，而且必须是**子目录**（v2.86.0 实测后定的）
 *
 * ### ① SAF 授权 Download **根目录**：Android 11+ **明令禁止**
 * 官方（Android 开发者官网《Android 11 存储空间更新》）原文：
 * > 您无法再使用 `ACTION_OPEN_DOCUMENT_TREE` 意图动作请求访问以下目录：
 * > 内部存储卷的根目录 / SD 卡根目录 / **Download 目录**。
 *
 * 实测完全一致：SAF 选择器里 Download **看得见**，但「USE THIS FOLDER」
 * **永远是灰的**（文案「Can't use this folder」）⇒ 用户永远拿不到授权。
 *
 * ### ② SAF 授权 Download 的**子目录**：✅ **完全可用**（实测）
 * 官方同页也说「**Ask users to create or pick a subfolder**」——
 * 子目录不在禁止名单里。实测：进到 `Download/星露存档/` 后，
 * 灰按钮消失、「USE THIS FOLDER」可点、授权确认框正常、持久授权拿得到。
 *
 * ⇒ 所以引导页教用户**在 Download 下建一个子文件夹**（系统选择器里就有
 *   「CREATE NEW FOLDER」按钮，当场就能建），并把浏览器的下载位置改成那里。
 *
 * ### ③ MediaStore 查下载文件：❌ **死路**（实测）
 * `MediaStore.Files` 集合里确实有下载的文件（实测 `relative_path=Download/`），
 * 但**普通 App 查它只能看到自己创建的条目** —— 浏览器/用户放的文件
 * `owner_package_name=NULL`，被分区存储挡掉。实测：App 侧 query **返回 0 行**，
 * 且**不报 SecurityException**（所以查不到时没有任何报错可循）。
 * ⇒ 这条路留着只会「安静地扫不到」，反而掩盖问题，故**不实现**。
 *
 * ## 兜底：手动选文件
 * 系统文件选择器（`ACTION_OPEN_DOCUMENT`）的「最近文件」里**能看到下载的文件**
 * （实测能看到 `send_save.bat (5)`）⇒ 「我已下载，手动选择文件」这条路始终可用。
 *
 * ## ⚠ 两条读通道，用户**二选一**（v2.91.0 补回，与远端 2.90.0 对齐）
 *
 * ### ① 「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`，Android 11+）
 * 用户在**系统设置**里手动授权，App 直接列 `Download` 等目录。
 * 好处：不用改浏览器的下载位置，下到哪都认得出。
 * ⚠ 这个权限**不会在安装时弹窗**，也不该替用户决定 —— App 只负责
 *   检测状态 + 跳设置页，勾选永远由用户自己做。
 *
 * ### ② SAF 目录树（**零权限**，上面的「办法二」）
 * 用户授权一个**子目录**，系统只把那个子树给我们。
 *
 * 两条通道都是**只读**：不写、不删、不移动下载目录里的任何东西。
 *
 * ## 合规边界
 * - **只读**：不写、不删、不移动下载目录里的任何东西。
 * - **不申请 `READ_EXTERNAL_STORAGE`**（读别的 App 的文件用不上它，
 *   Android 13+ 也已经拿不到）。
 * - **不碰 Steam 账号**：本文件里没有任何 token / 密码 / Cookie 相关的东西，
 *   也没有任何网络代码。账号环节 100% 在 Steam 官网里、由用户本人完成。
 */
object SteamDownloadDir {

    private const val TAG = "SteamDownloadDir"

    /** 「所有文件访问」这条通道单独的日志 tag（跟 SAF 那条区分开）。 */
    private const val TAG_ALL_FILES = "AllFilesAccess"

    /**
     * 各家手机 / 各语言下「下载文件夹」的常见叫法。
     *
     * 走「所有文件访问」时直接列文件系统，得自己认这些名字。
     */
    private val ALL_FILES_DIR_NAMES = listOf(
        "Download", "Downloads", "下载", "下载文件", "下载文件夹",
    )

    /**
     * 已授权的下载目录（SAF 树 Uri）。
     *
     * 用 SharedPreferences 而不是 DataStore：SAF 授权是**设备态**的
     * （换手机后那个 Uri 也没意义了），跟 `updateSeenVersion` 那类同理。
     */
    private const val PREFS = "steam_import_prefs"
    private const val KEY_TREE_URI = "tree_uri"

    /** 已授权的下载目录 Uri；没授权过就是 null。 */
    fun treeUri(ctx: Context): Uri? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    /** 记下已授权的下载目录。 */
    fun setTreeUri(ctx: Context, uri: Uri?) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().apply {
            if (uri == null) remove(KEY_TREE_URI) else putString(KEY_TREE_URI, uri.toString())
        }.apply()
    }

    /**
     * 记住用户对下载目录的持久授权。
     *
     * ⚠ **必须**调 `takePersistableUriPermission`，否则授权只在本次进程有效，
     *   App 被杀后再启动就拿不到了（那正是「自动导入」最需要它的时候）。
     */
    fun persistPermission(ctx: Context, uri: Uri): Boolean = runCatching {
        DocumentsContract.getTreeDocumentId(uri)
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    }.getOrElse {
        Log.w(TAG, "持久授权失败：${it.message}")
        false
    }

    /** SAF 授权还在吗（用户可能在系统设置里把它撤销了）。 */
    fun hasTreePermission(ctx: Context): Boolean {
        val uri = treeUri(ctx) ?: return false
        return ctx.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }

    /**
     * Android 11+ 的「所有文件访问」开没开。
     *
     * ⚠ 这个权限**不在安装时弹窗** —— 它在系统设置里，用户自己去点。
     *   App 只能检测状态、跳设置页，**不能替用户决定**。
     *
     * ⚠ 低于 Android 11 没有这个开关（那时候靠 `READ_EXTERNAL_STORAGE`，
     *   而本 App 刻意不申请它），所以直接 false。
     */
    fun hasAllFilesAccess(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return false
        return runCatching { android.os.Environment.isExternalStorageManager() }
            .getOrElse {
                Log.w(TAG_ALL_FILES, "检测所有文件访问权限失败：${it.message}")
                false
            }
    }

    /**
     * 有没有一条可用的「自动识别」通道。
     *
     * 两条通道任一可用即可 ——「所有文件访问」（用户手动开的）
     * 或 SAF 目录授权。
     */
    fun canAutoDetect(ctx: Context): Boolean =
        hasAllFilesAccess(ctx) || hasTreePermission(ctx)

    // ── 扫描 ────────────────────────────────────────────────

    /**
     * 扫下载目录，找**候选存档**。
     *
     * ## 三条通道，任一可用即扫（v2.91.0 补回，与远端 2.90.0 对齐）
     * ① 「所有文件访问」：直接列文件系统里的下载目录（用户开了这个权限才有）
     * ② SAF 目录树：用户授权的子目录
     * ③ MediaStore：按 `relative_path` 找下载目录（同样要 ① 的权限才有内容）
     *
     * ⚠ 三条通道**可能扫到同一个文件**（同一个存档既在 SAF 子目录里，
     *   又能被 MediaStore 查到）→ 按「文件名|大小|修改时间」去重。
     *
     * @param onlyMain true = 只返回主存档；false = 连非主存档也返回（带排除原因）
     * @return 候选列表（已排序：主存档优先 → 新 → 大）
     */
    fun scan(ctx: Context, onlyMain: Boolean = true): List<SteamSaveSniffer.Candidate> {
        val out = ArrayList<SteamSaveSniffer.Candidate>()

        // ① 所有文件访问：直接列文件系统的下载目录
        if (hasAllFilesAccess(ctx)) {
            out += runCatching { scanAllFilesAccess(ctx, onlyMain) }
                .getOrElse {
                    Log.w(TAG, "扫描下载目录（所有文件访问）失败：${it.message}")
                    emptyList()
                }
        }

        // ② SAF 目录树：用户授权的那个子目录
        val tree = treeUri(ctx)
        if (tree != null && hasTreePermission(ctx)) {
            out += runCatching { scanTree(ctx, tree, onlyMain) }
                .getOrElse {
                    Log.w(TAG, "扫描 SAF 下载目录失败：${it.message}")
                    emptyList()
                }
        }

        // ③ MediaStore：分区存储里按 relative_path 找
        if (hasAllFilesAccess(ctx)) {
            out += runCatching { scanMediaStore(ctx, onlyMain) }
                .getOrElse {
                    Log.w(TAG, "扫描 MediaStore 失败：${it.message}")
                    emptyList()
                }
        }

        val seen = HashSet<String>()
        val dedup = out.filter { seen.add("${it.name}|${it.size}|${it.lastModified}") }
        return dedup.sortedWith { a, b -> SteamSaveSniffer.compareCandidates(a, b) }
    }

    /**
     * 扫描状态摘要（引导页显示用）：两条通道各自的状态 + 扫到几个文件。
     *
     * ⚠ 空列表时补一句「没找到」—— 光看「扫到 0 个文件」用户会以为是 App 坏了，
     *   实际可能只是浏览器把文件下到了别处。
     */
    fun scanSummary(ctx: Context): String {
        val all = hasAllFilesAccess(ctx)
        val tree = hasTreePermission(ctx)
        val found = runCatching { scan(ctx, onlyMain = false) }
            .getOrElse { emptyList() }
        val sb = StringBuilder()
        sb.append(if (all) "已开所有文件访问" else "未开所有文件访问")
        sb.append("｜")
        sb.append(if (tree) "已授权文件夹" else "未授权文件夹")
        sb.append("｜扫到 ")
        sb.append(found.size)
        sb.append(" 个文件")
        for (c in found.take(6)) {
            sb.append("　")
            sb.append(c.name)
            sb.append(if (c.isMainSave) "（主存档✓）" else "（${c.reason}）")
        }
        if (found.isEmpty()) {
            sb.append("　—— 没找到。检查浏览器的下载位置是不是在「下载」文件夹里")
        }
        return sb.toString()
    }

    /**
     * 走「所有文件访问」：直接列文件系统的下载目录。
     *
     * 候选目录名覆盖了各家手机 / 各语言下「下载文件夹」的叫法。
     * 深度只到 2 层 —— Steam 云存档下载下来就是一个平铺的文件，
     * 再深的目录里不会有什么，递归下去反而在大目录里卡住。
     */
    private fun scanAllFilesAccess(
        ctx: Context,
        onlyMain: Boolean,
    ): List<SteamSaveSniffer.Candidate> {
        val out = ArrayList<SteamSaveSniffer.Candidate>()
        val root = android.os.Environment.getExternalStorageDirectory() ?: return out
        val dirs = ALL_FILES_DIR_NAMES
            .map { File(root, it) }
            .filter { it.isDirectory }
        val files = ArrayList<File>()
        for (dir in dirs) {
            try {
                dir.walkTopDown()
                    .maxDepth(2)
                    .filter { it.isFile }
                    .forEach { files.add(it) }
            } catch (t: Throwable) {
                Log.w(TAG_ALL_FILES, "列目录失败 ${dir.path}：${t.message}")
            }
        }
        for (f in files) {
            val sniff = sniffFile(f)
            if (onlyMain && !sniff.isMainSave) continue
            out += SteamSaveSniffer.Candidate(
                uri = Uri.fromFile(f).toString(),
                name = f.name,
                size = f.length(),
                lastModified = f.lastModified(),
                isMainSave = sniff.isMainSave,
                reason = sniff.reason,
            )
        }
        return out
    }

    /**
     * 走 MediaStore：按 `relative_path` 找出下载目录下的文件。
     *
     * ⚠ 分区存储下普通 App 只能看到**自己创建**的条目（别的 App 放的文件
     *   owner 为 NULL），所以这条通道**只有开了「所有文件访问」才有内容**。
     */
    private fun scanMediaStore(
        ctx: Context,
        onlyMain: Boolean,
    ): List<SteamSaveSniffer.Candidate> {
        val out = ArrayList<SteamSaveSniffer.Candidate>()
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        ctx.contentResolver.query(
            collection,
            arrayOf(
                android.provider.MediaStore.Files.FileColumns._ID,
                android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
                android.provider.MediaStore.Files.FileColumns.SIZE,
                "date_modified",
                "relative_path",
            ),
            "relative_path LIKE ?",
            arrayOf("%ownload%"),
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns._ID)
            val nameIdx = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.SIZE)
            val modIdx = cursor.getColumnIndex("date_modified")
            while (cursor.moveToNext()) {
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                if (name.isNullOrBlank()) continue
                val id = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                if (id <= 0) continue
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L
                // ⚠ date_modified 单位是**秒**，要 ×1000 才是毫秒
                val modSec = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else -1L
                val mod = if (modSec > 0) modSec * 1000L else -1L
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                val sniff = sniffUri(ctx, uri, name)
                if (onlyMain && !sniff.isMainSave) continue
                out += SteamSaveSniffer.Candidate(
                    uri = uri.toString(),
                    name = name,
                    size = size,
                    lastModified = mod,
                    isMainSave = sniff.isMainSave,
                    reason = sniff.reason,
                )
            }
        }
        return out
    }

    /**
     * 读一个**普通文件**的头部并嗅探。
     *
     * ⚠ 只读 [SteamSaveSniffer.SNIFF_BYTES] 字节 —— 主存档有 2~36MB，
     *   整文件读进内存既慢又没必要，而根标签与 `<player>` 都在最前面几百字节。
     */
    private fun sniffFile(file: File): SteamSaveSniffer.Sniff {
        val name = file.name
        if (SteamSaveSniffer.isTempName(name) || SteamSaveSniffer.isExcludedName(name)) {
            return SteamSaveSniffer.sniff(ByteArray(0), name)
        }
        return runCatching {
            val head = ByteArray(SteamSaveSniffer.SNIFF_BYTES)
            var read = 0
            file.inputStream().use { input ->
                while (read < head.size) {
                    val r = input.read(head, read, head.size - read)
                    if (r <= 0) break
                    read += r
                }
            }
            SteamSaveSniffer.sniff(head.copyOf(read), name)
        }.getOrElse {
            SteamSaveSniffer.Sniff(false, "", "读取失败：${it.message}")
        }
    }

    /**
     * 扫 SAF 目录树。
     *
     * 只列这棵树下的**直接子项** —— Steam 云存档下载下来就是一个平铺的文件，
     * 浏览器不会给它建子目录。递归会在大目录里卡住。
     */
    private fun scanTree(
        ctx: Context,
        tree: Uri,
        onlyMain: Boolean,
    ): List<SteamSaveSniffer.Candidate> {
        val out = ArrayList<SteamSaveSniffer.Candidate>()
        val resolver = ctx.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val docId = if (idIdx >= 0) cursor.getString(idIdx) else null
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                if (docId == null || name.isNullOrBlank()) continue
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                // ⚠ **只要目录**（mime 是目录）—— 目录不是存档，跳过。
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L
                val mod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else -1L
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                val sniff = sniffUri(ctx, uri, name)
                if (onlyMain && !sniff.isMainSave) continue
                out += SteamSaveSniffer.Candidate(
                    uri = uri.toString(),
                    name = name,
                    size = size,
                    lastModified = mod,
                    isMainSave = sniff.isMainSave,
                    reason = sniff.reason,
                )
            }
        }
        return out
    }

    /**
     * 读一个 Uri 的文件头并嗅探。
     *
     * ⚠ 只读 [SteamSaveSniffer.SNIFF_BYTES] 字节 —— 主存档有 2~36MB，
     *   整文件读进内存既慢又没必要，而根标签与 `<player>` 都在最前面几百字节。
     */
    fun sniffUri(ctx: Context, uri: Uri, name: String): SteamSaveSniffer.Sniff {
        // 文件名就能排除的（临时分片 / 备份 / 摘要）先排掉，不浪费一次 IO。
        if (SteamSaveSniffer.isTempName(name) || SteamSaveSniffer.isExcludedName(name)) {
            return SteamSaveSniffer.sniff(ByteArray(0), name)
        }
        return runCatching {
            val head = ByteArray(SteamSaveSniffer.SNIFF_BYTES)
            var read = 0
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                while (read < head.size) {
                    val r = input.read(head, read, head.size - read)
                    if (r <= 0) break
                    read += r
                }
            } ?: return SteamSaveSniffer.Sniff(false, "", "读不到文件")
            SteamSaveSniffer.sniff(head.copyOf(read), name)
        }.getOrElse {
            SteamSaveSniffer.Sniff(false, "", "读取失败：${it.message}")
        }
    }

    // ── 监听 ────────────────────────────────────────────────

    /**
     * 注册下载目录的变更监听。
     *
     * @param onChanged 目录有变化时回调（**在主线程**）
     * @return 注册好的 observer（用完要 unregister）
     */
    fun observe(ctx: Context, onChanged: () -> Unit): List<ContentObserver> {
        val tree = treeUri(ctx) ?: return emptyList()
        if (!hasTreePermission(ctx)) return emptyList()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, changed: Uri?) {
                onChanged()
            }
        }
        return runCatching {
            ctx.contentResolver.registerContentObserver(
                tree,       // 监听这棵树
                true,       // notifyForDescendants = true：子项变化也要通知
                observer,
            )
            listOf(observer)
        }.getOrElse {
            Log.w(TAG, "注册 observer 失败：${it.message}")
            emptyList()
        }
    }

    /** 解除监听。 */
    fun unobserve(ctx: Context, observers: List<ContentObserver>?) {
        observers ?: return
        observers.forEach { obs ->
            runCatching { ctx.contentResolver.unregisterContentObserver(obs) }
        }
    }

    // ── 复制到私有目录 ──────────────────────────────────────

    /**
     * 把下载目录里的候选**复制**进 App 私有目录。
     *
     * ⚠ **只复制，不动原文件** —— 下载目录是用户的，App 不该删不该改。
     *   复制进私有目录后再解析（SAF 的 Uri 不能直接给 [SaveImport] 用
     *   ——它要的是能 seek 的流，而 content Uri 的 seek 行为不保证）。
     *
     * @return 复制后的本地文件（调用方负责删）
     */
    fun copyToCache(ctx: Context, uri: Uri, tag: String): File? = runCatching {
        val dir = File(ctx.cacheDir, "steam_import").apply { mkdirs() }
        val dest = File(dir, "$tag.tmp")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
            }
        } ?: return null
        dest
    }.getOrElse {
        Log.w(TAG, "复制到缓存失败：${it.message}")
        null
    }

    /** 清掉缓存目录里残留的临时文件。 */
    fun clearCache(ctx: Context) {
        runCatching {
            File(ctx.cacheDir, "steam_import").listFiles()?.forEach { it.delete() }
        }
    }
}
