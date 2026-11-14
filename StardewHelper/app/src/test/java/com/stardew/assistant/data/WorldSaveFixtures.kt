package com.stardew.assistant.data

import org.json.JSONArray
import org.kxml2.io.KXmlParser
import java.io.File
import java.io.FileInputStream

/**
 * 单元测试共用的夹具：找四份真实存档、解析世界状态、读 App 自己的表。
 *
 * 放在 test 源集里，**不进 APK**。
 */
object WorldSaveFixtures {

    /**
     * 存档解压目录（默认）。可用系统属性 `worldstate.saves` 覆盖。
     *
     * ⚠ 2026-11-10 开源清理：原值写死本机绝对路径，
     *   clone 到别人机器上必然指不到东西。改成**相对仓根**的路径 ——
     *   别人的仓里没有真实存档（仓库也不含玩家存档，隐私原因），
     *   所以这个目录本来就不该存在；测试会因 Assume 跳过（**不会假装通过**），
     *   要跑真存档测试的人用 `-Dworldstate.saves=...` 指自己的目录。
     */
    const val DEFAULT_SAVES_DIR = "存档/_analysis_tmp"

    /** 四档存档的房主角色名 → 档名（用来把存档对上号，不依赖文件名编码）。 */
    val HOST_LABELS = mapOf(
        "梦秋尘" to "穿越",
        "Yuki" to "重逢",
        "Amy" to "ff",
        "K1ra Y0shik4g" to "KKK",
    )

    /** App 自己的 assets 目录（单元测试的工作目录是模块目录 `app/`）。 */
    fun assetFile(name: String): File = File("src/main/assets/data/$name")

    /** 读 App 生成的名称映射表。 */
    fun loadWorldItemMap(): WorldItemMap =
        WorldItemMap.parse(assetFile("worldItemMap.json").readText())

    /**
     * App 的 `crops.json` → 中文名 → 售价。
     *
     * ⚠ **售价只有这一个来源**：上游索引的 `sell_price` 实测有错（甜瓜 App 250 / 索引 120），
     * 所以任何产值计算都从这里取价，不碰上游。
     */
    fun loadSellPrices(): Map<String, Int> {
        val arr = JSONArray(assetFile("crops.json").readText())
        val out = LinkedHashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!o.isNull("sellPrice")) out[o.getString("name")] = o.getInt("sellPrice")
        }
        return out
    }

    /**
     * 找出每个存档目录里的**主存档**。
     *
     * 优先用「与目录同名的文件」（四份存档都是这个布局）；
     * 退路是排除 `_old` / `_SVBAK` / `_SVEMERG` / `SaveGameInfo` / `BACKUP_SAVE`
     * 之后最大的那个 —— 这几个都是游戏自己写的备份/合并档，**都比主存档大**，
     * 「取最大的」必然取错。
     */
    fun discoverSaves(base: File): List<File> {
        val out = ArrayList<File>()
        val dirs = base.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        for (dir in dirs) {
            val same = File(dir, dir.name)
            if (same.isFile && same.length() > 1024) {
                out.add(same)
                continue
            }
            val cands = dir.listFiles()?.filter { f ->
                f.isFile && f.length() > 1024 &&
                    !f.name.endsWith("_old") && !f.name.endsWith("_SVBAK") &&
                    !f.name.endsWith("_SVEMERG") && f.name != "SaveGameInfo" &&
                    f.name != "BACKUP_SAVE"
            } ?: emptyList()
            cands.maxByOrNull { it.length() }?.let { out.add(it) }
        }
        return out
    }

    /** 解析一份存档的世界状态（用 kxml2 的 XmlPullParser 实现，与 Android 同一个接口）。 */
    fun parseSave(file: File): WorldState = WorldStateParser.parse(
        WorldStateParser.Opener {
            configureSaveParser(KXmlParser(), FileInputStream(file))
        }
    )

    /** 存档根目录（系统属性优先）。 */
    fun savesDir(): File = File(System.getProperty("worldstate.saves") ?: DEFAULT_SAVES_DIR)

    /** 解析全部存档 → 档名 → 世界状态。 */
    fun parseAll(): Map<String, WorldState> {
        val out = LinkedHashMap<String, WorldState>()
        for (f in discoverSaves(savesDir())) {
            val st = parseSave(f)
            val host = st.players.firstOrNull { !it.farmhand }?.name
            out[HOST_LABELS[host] ?: host ?: "?"] = st
        }
        return out
    }
}
