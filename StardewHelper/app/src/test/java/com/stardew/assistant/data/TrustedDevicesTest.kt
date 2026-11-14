package com.stardew.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * c231：信任设备的**纯逻辑**单测（哈希/Token/清洗/JSON 编解码）。
 * 存储层（DataStore 读写、pair/verify/revoke 编排）依赖 Android Context，
 * 由 verify 钉点 + 真机验收覆盖；这里只钉安全核心。
 */
class TrustedDevicesTest {

    // ── hashToken：确定性 + 形状 + 雪崩 ──

    @Test
    fun hash_is_deterministic_sha256_base64() {
        val h1 = TrustedDevices.hashToken("abc")
        val h2 = TrustedDevices.hashToken("abc")
        assertEquals(h1, h2)
        assertEquals(44, h1.length)                       // 32 字节 → Base64 44 字符
        assertEquals(32, Base64.getDecoder().decode(h1).size)
        assertNotEquals(TrustedDevices.hashToken("abd"), h1)  // 雪崩：一字之差哈希全变
    }

    // ── newToken：形状 + 随机性 ──

    @Test
    fun newToken_is_32byte_base64_and_unique() {
        val a = TrustedDevices.newToken()
        val b = TrustedDevices.newToken()
        assertEquals(44, a.length)
        assertEquals(32, Base64.getDecoder().decode(a).size)
        assertNotEquals(a, b)
    }

    // ── verifyAgainstHash：对 / 错 / 篡改，全不抛异常 ──

    @Test
    fun verify_only_with_exact_token() {
        val tok = TrustedDevices.newToken()
        val hash = TrustedDevices.hashToken(tok)
        assertTrue(TrustedDevices.verifyAgainstHash(tok, hash))
        assertFalse(TrustedDevices.verifyAgainstHash(tok + "x", hash))
        assertFalse(TrustedDevices.verifyAgainstHash(tok, TrustedDevices.hashToken("other")))
        // 哈希库被篡改 → 拒绝（Base64 解码出来的值变了）
        assertFalse(TrustedDevices.verifyAgainstHash(tok, hash.dropLast(1) + "A"))
    }

    // ── sanitizeName：剥不安全字符 + 回退 + 截断 ──

    @Test
    fun sanitize_strips_unsafe_and_falls_back() {
        val id = "12345678-abcd"
        assertEquals("MyPC", TrustedDevices.sanitizeName("My\"PC\\", id))
        assertEquals("DESKTOP-ABC123", TrustedDevices.sanitizeName("DESKTOP-ABC123", id))
        // 中文名逐字被剥 → 全空 → 回退 PC-<id 前 8 位>
        assertEquals("PC-12345678", TrustedDevices.sanitizeName("我的电脑", id))
        assertEquals("PC-12345678", TrustedDevices.sanitizeName("   ", id))
        assertEquals(40, TrustedDevices.sanitizeName("x".repeat(100), id).length)
    }

    // ── JSON 编解码：往返一致 + 坏数据容错 ──

    @Test
    fun json_roundtrip_is_lossless() {
        val list = listOf(
            TrustedDevices.Device("id-1", "PC-A", "hash-1", 111L),
            TrustedDevices.Device("id-2", "PC-B", "hash-2", 222L),
        )
        assertEquals(list, TrustedDevices.decodeJson(TrustedDevices.encodeJson(list)))
    }

    @Test
    fun decode_tolerates_garbage_and_drops_broken_entries() {
        assertEquals(emptyList<TrustedDevices.Device>(), TrustedDevices.decodeJson("not json"))
        assertEquals(emptyList<TrustedDevices.Device>(), TrustedDevices.decodeJson("{}"))
        // 缺 id/hash 的条目丢弃；好条目保留且 name 缺失走 PC 回退
        val decoded = TrustedDevices.decodeJson(
            """{"devices":[{"id":"ok","hash":"h","at":5},{"name":"nolabel"}]}""",
        )
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded[0].id)
        assertEquals(5L, decoded[0].pairedAt)
        assertEquals("PC-ok", decoded[0].name)
    }
}
