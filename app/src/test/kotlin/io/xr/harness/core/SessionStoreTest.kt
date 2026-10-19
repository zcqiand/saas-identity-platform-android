package io.xr.harness.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SessionStore 三态机 JVM 单测（swift SessionStoreTests 同构）：内存 prefs，无 Android 依赖。 */
class SessionStoreTest {

  private class MemPrefs : PreferencesStore {
    val map = mutableMapOf<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
      map[key] = value
    }

    override fun remove(key: String) {
      map.remove(key)
    }
  }

  private val prefs = MemPrefs()
  private val store = SessionStore(prefs)

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
  fun `adoptLogin moves to ready`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", "t-1")
    assertEquals(SessionState.Ready(username = "alice", tenantId = "t-1"), store.state)
  }

  @Test
  fun `adoptLogin with null tenant yields ready without tenant`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", null)
    assertEquals(SessionState.Ready(username = "alice", tenantId = null), store.state)
  }

  @Test
  fun `clearSession returns to needsLogin but keeps config`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", "t-1")
    store.clearSession()
    assertEquals(SessionState.NeedsLogin, store.state)
    assertTrue(store.isConfigured)
  }

  @Test
  fun `clearConfig returns to needsSetup`() {
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin("alice", null)
    store.clearConfig()
    assertEquals(SessionState.NeedsSetup, store.state)
    assertFalse(store.isConfigured)
  }
}
