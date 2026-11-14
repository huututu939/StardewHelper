package com.stardew.assistant.data

import android.content.Context
import org.json.JSONObject

/**
 * v2.74.0：存档里的英文内部名 → 中文显示名。
 *
 * ## 数据从哪来
 * `assets/data/reportNames.json`，由 `tools/gen_report_names.py` 从 v9 真源数据生成
 * （78 怪 + 37 NPC + 别名），**生成物已提交**，构建不依赖 v9 目录。
 *
 * ## 查不到怎么办
 * **原样显示英文**。MOD 角色、wiki 没收录的怪都会走到这里。
 * 编一个中文名比显示英文更糟——那是假信息，而这份报告是给别人看的。
 */
object ReportNames {

    @Volatile
    private var cache: Map<String, String>? = null

    @Volatile
    private var favCache: Map<String, String>? = null

    private fun load(ctx: Context): Map<String, String> {
        cache?.let { return it }
        val m = LinkedHashMap<String, String>()
        runCatching {
            val o = JSONObject(ctx.assets.open("data/reportNames.json").bufferedReader().use { it.readText() })
            o.optJSONObject("monsters")?.let { jo ->
                jo.keys().forEach { k -> m[norm(k)] = jo.optString(k) }
            }
            o.optJSONObject("npcs")?.let { jo ->
                jo.keys().forEach { k -> m[norm(k)] = jo.optString(k) }
            }
        }
        return m.also { cache = it }
    }

    private fun loadFav(ctx: Context): Map<String, String> {
        favCache?.let { return it }
        val m = LinkedHashMap<String, String>()
        runCatching {
            val o = JSONObject(ctx.assets.open("data/reportNames.json").bufferedReader().use { it.readText() })
            o.optJSONObject("favorite")?.let { jo ->
                jo.keys().forEach { k -> m[norm(k)] = jo.optString(k) }
            }
        }
        return m.also { favCache = it }
    }

    /**
     * 查表用的键：去空白 → 小写 → 下划线转空格 → 去掉「 (dangerous)」后缀。
     *
     * `dangerous` 后缀是 v9 compendium 自己加的（区分普通/危险两只），
     * 存档里的键没有这个后缀，不去掉就查不到。
     */
    private fun norm(s: String): String = s.trim().lowercase()
        .replace('_', ' ')
        .replace(" (dangerous)", "")
        .replace("(dangerous)", "")
        .trim()

    /** 怪物英文名 → 中文名。查不到返回**原名**。 */
    fun monster(ctx: Context, en: String): String = load(ctx)[norm(en)] ?: en

    /** 村民英文名 → 中文名。查不到返回**原名**。 */
    fun npc(ctx: Context, en: String): String = load(ctx)[norm(en)] ?: en

    /**
     * 村民英文名（norm 后小写键）→ 中文名**全表**。
     *
     * v2.99.0 / c179：图鉴「你的状态」折算村民好感用 —— 导入存档时把
     * friendshipData 的英文 key 批量换成 App 中文名，只查这一张表。
     * 只收 npcs 段（load() 还混着怪物表，那部分不该出现在村民折算里）。
     */
    fun npcTable(ctx: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            val o = JSONObject(ctx.assets.open("data/reportNames.json").bufferedReader().use { it.readText() })
            o.optJSONObject("npcs")?.let { jo ->
                jo.keys().forEach { k -> out[norm(k)] = jo.optString(k) }
            }
        }
        return out
    }

    /**
     * 「最喜欢的东西」→ 中文名。
     *
     * ⚠ 这个值是**玩家自己输入的自由文本**（不是枚举），所以绝大多数情况查不到，
     * 返回 null 让调用方**显示原样**或干脆不显示这一行。
     * 只有 `ConcernedApe` 有官方含义（吃星之花时猿猴会说谢谢），所以才收录。
     */
    fun favorite(ctx: Context, raw: String): String? = loadFav(ctx)[norm(raw)]
}
