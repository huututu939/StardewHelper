package com.stardew.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 独立 DataStore 文件：信任设备名单（不动 stardew_prefs，出问题不连坐）。 */
private val Context.trustedDataStore by preferencesDataStore(name = "trusted_devices")

/**
 * v2.142（c231，内部轮）：「信任设备」——存档局域网自动同步的安全底座。
 *
 * ## 这是什么
 * 电脑端 SMAPI mod 首次配对（人工敲一次 6 位码）后，App 给它发一个 Token；
 * 之后 mod 每次保存自动上传时带 DeviceId + Token，App 校验通过就**不再弹任何
 * 确认**，直接收包导入。Token 可随时在 App 内撤销（撤销立即生效）。
 *
 * ## 信任边界（如实写，别把它说得比实际强）
 * 旧设计白纸黑字写着「不写任何认证 token」（见 [SaveReceiver] KDoc）——那是
 * v2.75 验证码方案的真实边界。自动同步需要免人工确认，**这个功能必然要求
 * 「持 Token 者可静默推送」**，所以这是一次有意的边界变更，不是遗忘：
 * - Token = 「能往你手机塞存档」的能力。**局域网是明文 HTTP**，Token 在请求头里
 *   可被同网段嗅探——和 v2.75 的验证码暴露面同级（码也在头里），没有变得更差，
 *   但「拿到即可长期重放」比一次性码强，所以下面的缓解都要当真；
 * - App 端**只存 Token 的 SHA-256**（[hashToken]），原文用完即丢——数据库泄露
 *   也还原不出凭证；比对走 [MessageDigest.isEqual]（常量时间）；
 * - 授权动作仍是**人工敲码**那一次：配对必须有人把电脑上的码敲进手机，
 *   没有任何人机交互就没有新 Token；
 * - 撤销立即生效（每次请求都重读名单），撤销后 mod 收 401 自动回退配对；
 * - 收到的东西照样**只当不可信输入**：是不是 zip、能不能解析出 `<SaveGame>`，
 *   仍由 [SaveImport] 说了算——Token 只解决「谁可以推」，不解决「推来的可信」。
 *
 * ## 为什么不加密存储
 * 考虑过 Keystore AES-GCM 包一层，但既然库里只有哈希、原文不存在，「加密」
 * 没有对象——哈希存储本身就是比加密更强的姿态（泄露不可逆）。不引 security-crypto
 * 依赖，保持零新增三方库。
 *
 * ## 线程
 * 写入（配对/撤销）只发生在连接工作线程；[verifyBlocking]/[pairBlocking] 用
 * runBlocking 供 [SaveReceiver] 的连接线程同步调用——**不要在主线程调它们**。
 */
object TrustedDevices {

    /** 一台已配对的电脑。**只存哈希**（[hash]），不存 Token 原文。 */
    data class Device(
        val id: String,
        val name: String,
        val hash: String,
        val pairedAt: Long,
    )

    private val KEY_LIST = stringPreferencesKey("devices")

    /** 名单上限：防异常请求把名单刷爆；超限丢最旧的（实际场景就一台 PC）。 */
    const val MAX_DEVICES = 10

    /** Token 长度（字节）：任务书要求 ≥32。Base64 后 44 字符。 */
    private const val TOKEN_BYTES = 32

    private val RNG = SecureRandom()

    // ── 纯逻辑（不依赖 Android，JVM 单测覆盖）────────────────────────

    /** Token 原文 → SHA-256 → Base64。库里有且只有这个。 */
    fun hashToken(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .let { Base64.getEncoder().encodeToString(it) }

    /** 生成新 Token：32 字节加密随机 → Base64。**只返回这一次**，App 不留原文。 */
    fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        RNG.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * 恒定时间比对：呈现的 Token 原文 vs 库里的哈希。
     * 先哈希再比——两边都是摘要，长度恒定，不泄露任何前缀信息。
     */
    fun verifyAgainstHash(raw: String, hash: String): Boolean =
        MessageDigest.isEqual(
            hashToken(raw).toByteArray(Charsets.UTF_8),
            hash.toByteArray(Charsets.UTF_8),
        )

    /**
     * 电脑名清洗：HTTP 头只该有 ASCII 可打印字符；引号/反斜杠一并去掉
     * （它们会进 JSON 文案）。空结果回退 `PC-<deviceId 前 8 位>`。
     */
    fun sanitizeName(raw: String?, deviceId: String): String {
        val cleaned = (raw ?: "")
            .filter { it in ' '..'~' && it != '"' && it != '\\' }
            .trim()
            .take(40)
        return cleaned.ifEmpty { "PC-" + deviceId.take(8) }
    }

    /** 名单 ↔ JSON（org.json，与备份导入同一套解析习惯）。坏数据一律回退空名单。 */
    fun encodeJson(list: List<Device>): String {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("hash", d.hash)
                    .put("at", d.pairedAt),
            )
        }
        return JSONObject().put("devices", arr).toString()
    }

    fun decodeJson(text: String): List<Device> = runCatching {
        val arr = JSONObject(text).getJSONArray("devices")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            val hash = o.optString("hash")
            if (id.isEmpty() || hash.isEmpty()) return@mapNotNull null
            Device(
                id = id,
                name = o.optString("name").ifEmpty { "PC-" + id.take(8) },
                hash = hash,
                pairedAt = o.optLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    // ── 存储（DataStore 薄壳）────────────────────────────────────────

    /** 读名单。撤销/校验都走它——**每次请求现读**，撤销立即生效就是这么来的。 */
    private suspend fun load(ds: DataStore<Preferences>): List<Device> =
        decodeJson(ds.data.first().get(KEY_LIST) ?: "")

    private suspend fun save(ds: DataStore<Preferences>, list: List<Device>) {
        ds.edit { it[KEY_LIST] = encodeJson(list) }
    }

    private suspend fun dataStoreOf(ctx: Context) = ctx.trustedDataStore

    /** 当前全部信任设备（管理 UI 用）。 */
    suspend fun all(ctx: Context): List<Device> = load(dataStoreOf(ctx))

    /**
     * 首次配对：给 [deviceId] 发新 Token（原文只出现这一次，进 HTTP 响应体）。
     * 同 id 重复配对 = 换发新 Token，旧的即刻作废；超限丢最旧的。
     */
    suspend fun pair(ctx: Context, deviceId: String, rawName: String?): String {
        val token = newToken()
        val ds = dataStoreOf(ctx)
        val rest = load(ds).filterNot { it.id == deviceId }
        val list = (listOf(
            Device(
                id = deviceId,
                name = sanitizeName(rawName, deviceId),
                hash = hashToken(token),
                pairedAt = System.currentTimeMillis(),
            ),
        ) + rest).take(MAX_DEVICES)
        save(ds, list)
        return token
    }

    /** 校验 DeviceId + Token。任何一步不匹配都拒绝（不区分「设备不存在」与「码不对」）。 */
    suspend fun verify(ctx: Context, deviceId: String, rawToken: String): Boolean =
        load(dataStoreOf(ctx))
            .firstOrNull { it.id == deviceId }
            ?.let { verifyAgainstHash(rawToken, it.hash) }
            ?: false

    /** 撤销信任。返回是否真的撤了一条（给 UI 提示用）。撤销立即生效。 */
    suspend fun revoke(ctx: Context, deviceId: String): Boolean {
        val ds = dataStoreOf(ctx)
        val old = load(ds)
        val rest = old.filterNot { it.id == deviceId }
        if (rest.size == old.size) return false
        save(ds, rest)
        return true
    }

    // ── 连接线程入口（SaveReceiver 的工作线程调用；别在主线程用）──────

    fun verifyBlocking(ctx: Context, deviceId: String, rawToken: String): Boolean =
        runBlocking { verify(ctx, deviceId, rawToken) }

    fun pairBlocking(ctx: Context, deviceId: String, rawName: String?): String =
        runBlocking { pair(ctx, deviceId, rawName) }
}
