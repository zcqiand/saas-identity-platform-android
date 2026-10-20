package io.xr.harness.core

import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SessionStore 三态机 JVM 单测（swift SessionStoreTests 同构）：内存 prefs + 内存 token 存，无 Android 依赖。 */
class SessionStoreTest {

  class MemPrefs : PreferencesStore {
    val map = mutableMapOf<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
      map[key] = value
    }

    override fun remove(key: String) {
      map.remove(key)
    }
  }

  class MemTokens : TokenStoring {
    var access: String? = null
    var refresh: String? = null

    override fun loadAccess(): String? = access

    override fun loadRefresh(): String? = refresh

    override fun save(accessToken: String?, refreshToken: String?) {
      access = accessToken
      refresh = refreshToken
    }
  }

  private val prefs = MemPrefs()
  private val tokens = MemTokens()
  private val store = SessionStore(prefs, tokens)

  @Test
  fun `fresh store needs setup`() {
    assertEquals(SessionState.NeedsSetup, store.state)
    assertFalse(store.isConfigured)
  }

  @Test
  fun `saveConfig blank clientId fails fast and persists nothing`() {
    assertThrows(IllegalStateException::class.java) {
      store.saveConfig("http://10.0.2.2:5104", "  ")
    }
    assertFalse(store.isConfigured)
    assertTrue(prefs.map.isEmpty())
  }

  @Test
  fun `saveConfig blank baseUrl fails fast`() {
    assertThrows(IllegalStateException::class.java) { store.saveConfig("   ", "saas-android") }
    assertFalse(store.isConfigured)
  }

  @Test
  fun `saveConfig persists and state becomes needsLogin`() {
    // 归一化（去尾斜杠）归 ConfigScreen/ApiGlue，SessionStore 只 trim 存原值
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    assertTrue(store.isConfigured)
    assertEquals("http://10.0.2.2:5104", store.baseUrl)
    assertEquals("saas-android", store.clientId)
    assertEquals(SessionState.NeedsLogin, store.state)
  }

  @Test
  fun `adoptLogin persists session and tokens and moves to ready`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", "t-1", "tok-1", "ref-1")
    assertEquals(SessionState.Ready(username = "alice", tenantId = "t-1"), store.state)
    assertEquals("tok-1", tokens.access)
    assertEquals("ref-1", tokens.refresh)
  }

  @Test
  fun `ready state requires token`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", "t-1", "tok-1", "ref-1")
    assertEquals(SessionState.Ready(username = "alice", tenantId = "t-1"), store.state)
    // 模拟 token 丢失（存储被清/过期清除）：身份仍在 prefs，但无 token 即回 needsLogin
    tokens.save(null, null)
    assertEquals(SessionState.NeedsLogin, store.state)
  }

  @Test
  fun `adoptLogin with null tenant yields ready without tenant`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", null, "tok-1", null)
    assertEquals(SessionState.Ready(username = "alice", tenantId = null), store.state)
  }

  @Test
  fun `clearSession wipes tokens too and returns to needsLogin`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", "t-1", "tok-1", "ref-1")
    store.clearSession()
    assertEquals(SessionState.NeedsLogin, store.state)
    assertTrue(store.isConfigured)
    assertEquals(null, tokens.access)
    assertEquals(null, tokens.refresh)
  }

  @Test
  fun `adoptTenant switches current tenant in place`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", "t-1", "tok-1", "ref-1")
    store.adoptTenant("t-2")
    assertEquals(SessionState.Ready(username = "alice", tenantId = "t-2"), store.state)
    assertEquals("t-2", store.tenantId)
  }

  @Test
  fun `adoptOAuthToken swaps pair and aligns tenant`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", "t-1", "tk-1", "rt-1")

    store.adoptOAuthToken(
        accessToken = "tk-2",
        refreshToken = "rt-2",
        tenantId = "t-9",
        expiresIn = 3600,
    )

    assertEquals("tk-2", tokens.access)
    assertEquals("rt-2", tokens.refresh)
    assertEquals("t-9", store.tenantId)
    assertEquals(SessionState.Ready(username = "alice", tenantId = "t-9"), store.state)
  }

  @Test
  fun `adoptOAuthToken rejects when not logged in`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    assertThrows(IllegalStateException::class.java) {
      store.adoptOAuthToken(
          accessToken = "tk-1",
          refreshToken = "rt-1",
          tenantId = "t-1",
          expiresIn = 3600,
      )
    }
    assertEquals(null, tokens.access, "未登录拒绝入账，token 对不许被写")
  }

  @Test
  fun `adoptOAuthToken rejects empty access keeping old pair`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", "t-1", "tk-1", "rt-1")
    assertThrows(IllegalStateException::class.java) {
      store.adoptOAuthToken(
          accessToken = "  ",
          refreshToken = "rt-2",
          tenantId = "t-1",
          expiresIn = 3600,
      )
    }
    assertEquals("tk-1", tokens.access, "空 access 拒绝入账，旧 token 对保持")
    assertEquals("rt-1", tokens.refresh)
  }

  @Test
  fun `adoptOAuthToken records expiresAt from expiresIn`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", "t-1", "tk-1", "rt-1")

    val before = OffsetDateTime.now()
    store.adoptOAuthToken(
        accessToken = "tk-2",
        refreshToken = "rt-2",
        tenantId = "t-9",
        expiresIn = 3600,
    )

    val exp = store.expiresAt
    assertNotNull(exp, "OAuth 入账必须记录过期时刻（swift 同构，REQ-2026-002 Q2 展示依据）")
    assertTrue(
        ChronoUnit.SECONDS.between(before, exp) in 3599..3601,
        "expiresAt 应为 now + expiresIn（实测 $exp）",
    )
  }

  @Test
  fun `adoptTenant records switch expiresAt`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", "t-1", "tok-1", "ref-1")

    val switchedAt = OffsetDateTime.parse("2023-11-14T22:13:20Z")
    store.adoptTenant("t-2", switchedAt)

    assertEquals(switchedAt, store.expiresAt)
    assertEquals("t-2", store.tenantId)
  }

  @Test
  fun `clearSession wipes expiresAt`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", "t-1", "tk-1", "rt-1")
    store.adoptOAuthToken(
        accessToken = "tk-2",
        refreshToken = "rt-2",
        tenantId = "t-1",
        expiresIn = 3600,
    )
    assertNotNull(store.expiresAt)

    store.clearSession()

    assertEquals(null, store.expiresAt, "登出清密后过期时刻不许残留")
  }

  @Test
  fun `clearConfig returns to needsSetup`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", null, "tok-1", null)
    store.clearConfig()
    assertEquals(SessionState.NeedsSetup, store.state)
    assertFalse(store.isConfigured)
    assertEquals(null, tokens.access)
  }
}
