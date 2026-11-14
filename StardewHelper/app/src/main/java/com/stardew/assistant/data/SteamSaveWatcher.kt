package com.stardew.assistant.data

/**
 * v2.86.0：下载中的**文件完整性**判定（纯逻辑，无 Android 依赖，可进 JVM 单测）。
 *
 * ## 为什么需要这一层
 * 浏览器下载存档是**边下边写**的。ContentObserver 回调会在下载过程中**连续触发**，
 * 每次都读到一份「写了一半」的文件。如果这时候就去解析：
 * - 运气好 → 解析失败，给用户一句「识别失败」（用户其实下好了）；
 * - 运气坏 → 恰好读到了前半截里出现的 `<player>`，**把半成品当成主存档导进去**，
 *   用户会发现「我的进度怎么少了一半」——这比报错糟糕得多。
 *
 * ## 三道闸
 * ① **临时后缀**：Chrome 下载中叫 `xxx.crdownload`，直接排除（见 [SteamSaveSniffer]）。
 * ② **大小两次稳定**：间隔 [STABLE_INTERVAL_MS] 连读两次大小，**相同**才算写完。
 *    正在写的文件大小一直在变 → 不稳定 → 继续等。
 * ③ **内容可解析**：读文件头能嗅出根标签 `<SaveGame>` 且含 `<player>`（见 [SteamSaveSniffer]）。
 *
 * ①② 是「结构判据」，③ 是「内容判据」。三个都过才认。
 *
 * ## 重试
 * 嗅探失败**不立刻判死** —— 下载刚写完的瞬间，文件系统可能还没把元数据刷干净。
 * 所以失败时按 [RETRY_DELAYS_MS] 的间隔重试若干次，全失败才报「没识别到」。
 *
 * ## 为什么状态机是纯逻辑
 * 「等多久」「重试几次」「什么算稳定」这些是**最需要被测**的部分，
 * 但它们在真机上很难稳定复现（要真的下载一个 36MB 文件）。
 * 所以把它们抽成纯函数：输入是「两次观察到的 (大小, 修改时间)」，
 * 输出是「继续等 / 认定写完 / 认定失败」。单测直接喂序列即可。
 */
object SteamSaveWatcher {

    /**
     * 判定「大小已稳定」要间隔多久（毫秒）。
     *
     * 1 秒：写入中的文件在这 1 秒内大小几乎必然变化（除非网速极慢正好卡住）；
     * 而真的写完了，1 秒内不会再变。
     */
    const val STABLE_INTERVAL_MS = 1000L

    /**
     * 嗅探失败后的重试间隔（毫秒）。
     *
     * 1 秒一次、共 [RETRY_COUNT] 次 = 总共给 3 秒的「文件系统刷元数据」窗口。
     */
    val RETRY_DELAYS_MS = listOf(1000L, 1000L, 1000L)

    /** 总共尝试几次（首次 + 重试）。 */
    val RETRY_COUNT = RETRY_DELAYS_MS.size + 1

    /**
     * 观察结果。
     *
     * @param size 观察到的字节数（-1 = 读不到）
     * @param lastModified 最后修改时间（毫秒，-1 = 读不到）
     */
    data class Observation(val size: Long, val lastModified: Long)

    /**
     * 状态机判定。
     */
    sealed interface Verdict {
        /** 继续等（大小还在变，或还在重试窗口内）。 */
        data class Wait(val reason: String, val attempt: Int) : Verdict

        /** 认定写完且是主存档。 */
        object Ready : Verdict

        /** 认定不是主存档（可以给出可读原因）。 */
        data class Reject(val reason: String) : Verdict
    }

    /**
     * 核心判定：给定两次观察 + 嗅探结论 + 当前重试次数，给出下一步。
     *
     * @param prev 上一次的观察（第一次为 null）
     * @param cur 这次的观察
     * @param sniff 内容嗅探结论
     * @param attempt 已经嗅探过几次（0 = 还没嗅探过）
     */
    fun judge(
        prev: Observation?,
        cur: Observation,
        sniff: SteamSaveSniffer.Sniff?,
        attempt: Int,
    ): Verdict {
        // ① 读不到（文件被删了 / 权限没了）→ 不再等
        if (cur.size < 0) {
            return Verdict.Reject("文件已消失")
        }
        // ② 大小还在变 → 还在写，继续等
        if (prev != null && prev.size != cur.size) {
            return Verdict.Wait("还在下载中", attempt)
        }
        // ③ 大小稳定了（两次一致），看内容
        if (sniff == null) {
            return Verdict.Wait("还没读到内容", attempt)
        }
        if (sniff.isMainSave) {
            return Verdict.Ready
        }
        // ④ 不是主存档：先给几次重试机会（文件系统元数据可能还没刷完）
        if (attempt < RETRY_COUNT) {
            return Verdict.Wait(sniff.reason, attempt)
        }
        return Verdict.Reject(sniff.reason)
    }

    /**
     * 观察是否「稳定」（两次大小相同）。
     *
     * 单独抽出来是为了让单测能直接验证「下载中 → 稳定」这条时间线。
     */
    fun isStable(prev: Observation?, cur: Observation): Boolean =
        prev != null && prev.size == cur.size && cur.size >= 0

    /**
     * 候选去重：**同一份存档只留一条**。
     *
     * 同一个文件可能被 observer 与回扫各发现一次；同一份存档也可能同时有
     * 「主存档」与「它的副本」。判据 = 文件名 + 大小 + 修改时间。
     */
    fun dedupe(candidates: List<SteamSaveSniffer.Candidate>): List<SteamSaveSniffer.Candidate> {
        val seen = HashSet<String>()
        return candidates.filter { c ->
            val key = "${c.name}|${c.size}|${c.lastModified}"
            seen.add(key)
        }
    }

    /**
     * 存档更新的唯一标识（用于「同一文件不重复导入、不重复通知」）。
     *
     * = 文件名 + 大小 + 最后修改时间。
     * ⚠ **不含内容哈希**：主存档 2~36MB，算 sha1 要几百毫秒，
     * 而 observer 回调会连着触发好几次 —— 每回调一次算一次哈希太浪费。
     * 文件名+大小+修改时间三者同时相同，就足以认定是同一个文件。
     */
    fun fingerprint(name: String, size: Long, lastModified: Long): String =
        "$name|$size|$lastModified"
}
