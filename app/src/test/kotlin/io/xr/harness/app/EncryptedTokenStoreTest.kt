package io.xr.harness.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * EncryptedTokenStore JVM 单测（swift KeychainTokenStore 单测姿态同构：设备安全位不碰，测包装逻辑）： 落盘密文不落明文；
 * access/refresh 独立；解密失败 fail-closed 返 null 不崩（密钥轮换/清数据后旧密文作废 = 需重新登录，明文存量同命运）。
 */
class EncryptedTokenStoreTest {

  /** fake cipher：可逆标记变换（enc(...)），可注入坏密文模拟篡改。 */
  class MarkCipher : io.xr.harness.core.TokenCipher {
    override fun encrypt(plain: String): String = "enc($plain)"

    override fun decrypt(cipherText: String): String {
      check(cipherText.startsWith("enc(") && cipherText.endsWith(")")) { "密文格式非法" }
      return cipherText.removePrefix("enc(").removeSuffix(")")
    }
  }

  class MemPrefs : io.xr.harness.core.PreferencesStore {
    val map = mutableMapOf<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
      map[key] = value
    }

    override fun remove(key: String) {
      map.remove(key)
    }
  }

  @Test
  fun `save stores ciphertext and round-trips plaintext`() {
    val prefs = MemPrefs()
    val store = EncryptedTokenStore(prefs, MarkCipher())

    store.save("tk-1", "rt-1")

    assertEquals("enc(tk-1)", prefs.map["tokens.access"], "落盘必须是密文不是明文")
    assertEquals("enc(rt-1)", prefs.map["tokens.refresh"], "落盘必须是密文不是明文")
    assertEquals("tk-1", store.loadAccess())
    assertEquals("rt-1", store.loadRefresh())
  }

  @Test
  fun `access and refresh persist independently`() {
    val prefs = MemPrefs()
    val store = EncryptedTokenStore(prefs, MarkCipher())

    store.save("tk-1", null)
    assertEquals("tk-1", store.loadAccess())
    assertNull(store.loadRefresh())

    store.save(null, "rt-2")
    assertNull(store.loadAccess())
    assertEquals("rt-2", store.loadRefresh())
  }

  @Test
  fun `tampered ciphertext fails closed to null`() {
    val prefs = MemPrefs()
    val store = EncryptedTokenStore(prefs, MarkCipher())
    store.save("tk-1", "rt-1")

    prefs.map["tokens.access"] = "garbage-not-cipher"

    assertNull(store.loadAccess(), "篡改/换钥的密文 fail-closed 返 null，不崩不发死 token")
    assertEquals("rt-1", store.loadRefresh(), "未篡改的另一位不受牵连")
  }

  @Test
  fun `clear saves wipe keys entirely`() {
    val prefs = MemPrefs()
    val store = EncryptedTokenStore(prefs, MarkCipher())
    store.save("tk-1", "rt-1")

    store.save(null, null)

    assertNull(prefs.map["tokens.access"], "清密即删键，不留密文残骸")
    assertNull(prefs.map["tokens.refresh"])
    assertNull(store.loadAccess())
    assertNull(store.loadRefresh())
  }

  @Test
  fun `ciphertext differs from plaintext for realistic token`() {
    val prefs = MemPrefs()
    val store = EncryptedTokenStore(prefs, MarkCipher())
    val token = "eyJhbGciOiJIUzI1NiJ9.payload.signature"

    store.save(token, token)
    val stored = prefs.map["tokens.access"]!!

    assertNotEquals(token, stored, "真实形状 token 也不得明文落盘")
  }
}
