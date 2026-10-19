package io.xr.harness.ui

import io.xr.harness.core.AuthError
import io.xr.harness.core.LoginOutcome
import io.xr.harness.core.PreferencesStore
import io.xr.harness.core.SessionState
import io.xr.harness.core.SessionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AuthViewModel fake-seam JVM 单测（swift AuthViewModelTests 同构）：不真连，ktor ADR-0048 姿态。 */
class AuthViewModelTest {

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

  private fun configuredStore(): SessionStore =
      SessionStore(MemPrefs()).apply { saveConfig("http://10.0.2.2:5104", "saas-android") }

  @Test
  fun `login success adopts session and returns true`() {
    val store = configuredStore()
    var seenClientId: String? = null
    val seams =
        AuthViewModel.Seams(
            login = { username, _, clientId ->
              assertEquals("alice", username)
              seenClientId = clientId
              LoginOutcome.Success(username = "alice", accessToken = "tok", tenantId = "t-1")
            },
        )
    val vm = AuthViewModel(store, seams)

    val ok = kotlinx.coroutines.runBlocking { vm.login("alice", "dev123456") }

    assertTrue(ok)
    assertEquals("saas-android", seenClientId)
    assertEquals(SessionState.Ready(username = "alice", tenantId = "t-1"), vm.session.value)
    assertEquals(AuthPhase.Idle, vm.phase.value)
  }

  @Test
  fun `login failure lands in failed phase and stays needsLogin`() {
    val store = configuredStore()
    val seams =
        AuthViewModel.Seams(
            login = { _, _, _ -> LoginOutcome.Failure(AuthError.InvalidCredentials()) },
        )
    val vm = AuthViewModel(store, seams)

    val ok = kotlinx.coroutines.runBlocking { vm.login("alice", "wrong") }

    assertFalse(ok)
    assertEquals(AuthPhase.Failed("用户名或密码无效"), vm.phase.value)
    assertEquals(SessionState.NeedsLogin, vm.session.value)
  }

  @Test
  fun `seam throwing AuthError maps to failed phase`() {
    val store = configuredStore()
    val seams =
        AuthViewModel.Seams(
            login = { _, _, _ -> throw AuthError.Locked() },
        )
    val vm = AuthViewModel(store, seams)

    val ok = kotlinx.coroutines.runBlocking { vm.login("alice", "dev123456") }

    assertFalse(ok)
    assertEquals(AuthPhase.Failed("尝试次数过多，账号已锁定，请稍后再试"), vm.phase.value)
  }

  @Test
  fun `seam throwing unexpected maps to unreachable failure`() {
    val store = configuredStore()
    val seams =
        AuthViewModel.Seams(
            login = { _, _, _ -> throw BoomError("boom") },
        )
    val vm = AuthViewModel(store, seams)

    val ok = kotlinx.coroutines.runBlocking { vm.login("alice", "dev123456") }

    assertFalse(ok)
    assertTrue((vm.phase.value as AuthPhase.Failed).message.contains("无法连接"))
  }

  /** 非 AuthError 的任意异常样本（detekt 禁测试里抛泛 RuntimeException，自定专用型）。 */
  private class BoomError(message: String) : Exception(message)

  @Test
  fun `clientId unconfigured fails fast without calling seam`() {
    val store = SessionStore(MemPrefs())
    var called = false
    val seams =
        AuthViewModel.Seams(
            login = { _, _, _ ->
              called = true
              LoginOutcome.Success("alice", "tok", null)
            },
        )
    val vm = AuthViewModel(store, seams)

    val ok = kotlinx.coroutines.runBlocking { vm.login("alice", "dev123456") }

    assertFalse(ok)
    assertFalse(called)
    assertEquals(AuthPhase.Failed("clientId 未配置，请先在配置页填写"), vm.phase.value)
  }

  @Test
  fun `blank username short-circuits without calling seam`() {
    val store = configuredStore()
    var called = false
    val seams =
        AuthViewModel.Seams(
            login = { _, _, _ ->
              called = true
              LoginOutcome.Success("alice", "tok", null)
            },
        )
    val vm = AuthViewModel(store, seams)

    val ok = kotlinx.coroutines.runBlocking { vm.login("  ", "dev123456") }

    assertFalse(ok)
    assertFalse(called)
    assertEquals(AuthPhase.Idle, vm.phase.value)
  }

  @Test
  fun `logout clears session back to needsLogin`() {
    val store = configuredStore()
    store.adoptLogin("alice", "t-1")
    var logoutCalled = false
    val seams = AuthViewModel.Seams(logout = { logoutCalled = true })
    val vm = AuthViewModel(store, seams)

    kotlinx.coroutines.runBlocking { vm.logout() }

    assertTrue(logoutCalled)
    assertEquals(SessionState.NeedsLogin, vm.session.value)
  }

  @Test
  fun `reconfigure clears config back to needsSetup`() {
    val store = configuredStore()
    store.adoptLogin("alice", null)
    val vm = AuthViewModel(store)

    vm.reconfigure()

    assertEquals(SessionState.NeedsSetup, vm.session.value)
  }
}
