package io.xr.harness.ui

import io.xr.harness.core.AuthError
import io.xr.harness.core.LoginOutcome
import io.xr.harness.core.PreferencesStore
import io.xr.harness.core.SessionState
import io.xr.harness.core.SessionStore
import io.xr.harness.core.SwitchOutcome
import io.xr.harness.core.TenantItem
import io.xr.harness.core.TokenStoring
import io.xr.harness.core.WhoamiUser
import java.time.OffsetDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AuthViewModel fake-seam JVM 单测（swift AuthViewModelTests 同构）：不真连，ktor ADR-0048 姿态。 */
class AuthViewModelTest {

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

  private fun configuredStore(): SessionStore =
      SessionStore(MemPrefs(), MemTokens()).apply {
        saveConfig("http://10.0.2.2:5104", "saas-android")
      }

  @Test
  fun `login success adopts session with tokens and returns true`() {
    val store = configuredStore()
    var seenClientId: String? = null
    val seams =
        AuthViewModel.Seams(
            login = { username, _, clientId ->
              assertEquals("alice", username)
              seenClientId = clientId
              LoginOutcome.Success(
                  username = "alice",
                  accessToken = "tok",
                  refreshToken = "ref",
                  tenantId = "t-1",
              )
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

  @Test
  fun `clientId unconfigured fails fast without calling seam`() {
    val store = SessionStore(MemPrefs(), MemTokens())
    var called = false
    val seams =
        AuthViewModel.Seams(
            login = { _, _, _ ->
              called = true
              LoginOutcome.Success("alice", "tok", null, null)
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
              LoginOutcome.Success("alice", "tok", null, null)
            },
        )
    val vm = AuthViewModel(store, seams)

    val ok = kotlinx.coroutines.runBlocking { vm.login("  ", "dev123456") }

    assertFalse(ok)
    assertFalse(called)
    assertEquals(AuthPhase.Idle, vm.phase.value)
  }

  @Test
  fun `restore with valid token and whoami stays ready and publishes user`() {
    val store = configuredStore()
    store.adoptLogin("alice", "t-1", "tok-1", "ref-1")
    var whoamiCalled = false
    val seams =
        AuthViewModel.Seams(
            whoami = {
              whoamiCalled = true
              WhoamiUser(username = "alice", displayName = "Alice", tenantId = "t-9")
            },
        )
    val vm = AuthViewModel(store, seams)

    kotlinx.coroutines.runBlocking { vm.restore() }

    assertTrue(whoamiCalled)
    assertEquals(SessionState.Ready(username = "alice", tenantId = "t-1"), vm.session.value)
    assertEquals(WhoamiUser("alice", "Alice", "t-9"), vm.user.value)
  }

  @Test
  fun `restore with invalid session clears to needsLogin`() {
    val store = configuredStore()
    store.adoptLogin("alice", "t-1", "stale-tok", null)
    val seams = AuthViewModel.Seams(whoami = { null })
    val vm = AuthViewModel(store, seams)

    kotlinx.coroutines.runBlocking { vm.restore() }

    assertEquals(SessionState.NeedsLogin, vm.session.value)
    assertNull(vm.user.value)
  }

  @Test
  fun `restore without session skips whoami`() {
    val store = configuredStore()
    var whoamiCalled = false
    val seams =
        AuthViewModel.Seams(
            whoami = {
              whoamiCalled = true
              WhoamiUser("alice", null, null)
            },
        )
    val vm = AuthViewModel(store, seams)

    kotlinx.coroutines.runBlocking { vm.restore() }

    assertFalse(whoamiCalled)
    assertEquals(SessionState.NeedsLogin, vm.session.value)
  }

  @Test
  fun `logout clears session tokens and user`() {
    val store = configuredStore()
    store.adoptLogin("alice", "t-1", "tok-1", "ref-1")
    var logoutCalled = false
    val seams =
        AuthViewModel.Seams(
            logout = { logoutCalled = true },
            whoami = { WhoamiUser("alice", null, "t-1") },
        )
    val vm = AuthViewModel(store, seams)
    kotlinx.coroutines.runBlocking { vm.restore() }
    assertEquals(WhoamiUser("alice", null, "t-1"), vm.user.value)

    kotlinx.coroutines.runBlocking { vm.logout() }

    assertTrue(logoutCalled)
    assertEquals(SessionState.NeedsLogin, vm.session.value)
    assertNull(vm.user.value)
  }

  @Test
  fun `reconfigure clears config back to needsSetup`() {
    val store = configuredStore()
    store.adoptLogin("alice", null, "tok-1", null)
    val vm = AuthViewModel(store)

    vm.reconfigure()

    assertEquals(SessionState.NeedsSetup, vm.session.value)
  }

  @Test
  fun `switch tenant success adopts tenant refetches user and returns true`() {
    val store = configuredStore()
    store.adoptLogin("alice", "t-1", "tok-1", "ref-1")
    var switchedTo: String? = null
    val seams =
        AuthViewModel.Seams(
            switchTenant = { tenantId ->
              switchedTo = tenantId
              SwitchOutcome.Success(
                  tenantId = "t-2",
                  expiresAt = OffsetDateTime.parse("2023-11-14T22:13:20Z"),
              )
            },
            whoami = { WhoamiUser("alice", "Alice", "t-2") },
        )
    val vm = AuthViewModel(store, seams)
    kotlinx.coroutines.runBlocking { vm.restore() }

    val ok = kotlinx.coroutines.runBlocking { vm.switchTenant("t-2") }

    assertTrue(ok)
    assertEquals("t-2", switchedTo)
    assertEquals(SessionState.Ready(username = "alice", tenantId = "t-2"), vm.session.value)
    assertEquals("t-2", store.tenantId)
    assertEquals(
        OffsetDateTime.parse("2023-11-14T22:13:20Z"),
        store.expiresAt,
        "切租户入账须随响应记录过期时刻（swift adoptSwitch 同构）",
    )
    assertEquals(WhoamiUser("alice", "Alice", "t-2"), vm.user.value)
    assertEquals(AuthPhase.Idle, vm.phase.value)
  }

  @Test
  fun `switch tenant failure shows failed phase and keeps session untouched`() {
    val store = configuredStore()
    store.adoptLogin("alice", "t-1", "tok-1", "ref-1")
    val seams =
        AuthViewModel.Seams(
            switchTenant = { _ -> SwitchOutcome.Failure(AuthError.Server("403 无权访问目标租户")) },
        )
    // 不 restore（whoami 默认 seam 会清密）：本用例只验切换失败不触碰 store
    val vm = AuthViewModel(store, seams)

    val ok = kotlinx.coroutines.runBlocking { vm.switchTenant("t-2") }

    assertFalse(ok)
    assertEquals(AuthPhase.Failed("403 无权访问目标租户"), vm.phase.value)
    assertEquals(SessionState.Ready(username = "alice", tenantId = "t-1"), vm.session.value)
    assertEquals("t-1", store.tenantId)
  }

  @Test
  fun `switch tenant seam throwing maps to unreachable failure`() {
    val store = configuredStore()
    store.adoptLogin("alice", "t-1", "tok-1", "ref-1")
    val seams = AuthViewModel.Seams(switchTenant = { _ -> throw BoomError("boom") })
    val vm = AuthViewModel(store, seams)

    val ok = kotlinx.coroutines.runBlocking { vm.switchTenant("t-2") }

    assertFalse(ok)
    assertTrue((vm.phase.value as AuthPhase.Failed).message.contains("无法连接"))
  }

  @Test
  fun `loadTenants populates tenant list`() {
    val seams =
        AuthViewModel.Seams(
            listTenants = {
              listOf(TenantItem("t-1", null, "角色 owner"), TenantItem("t-2", null, "角色 member"))
            },
        )
    val vm = AuthViewModel(configuredStore(), seams)

    kotlinx.coroutines.runBlocking { vm.loadTenants() }

    assertEquals(
        listOf(TenantItem("t-1", null, "角色 owner"), TenantItem("t-2", null, "角色 member")),
        vm.tenants.value,
    )
  }

  @Test
  fun `loadTenants failure yields empty list without crashing`() {
    val seams = AuthViewModel.Seams(listTenants = { throw BoomError("boom") })
    val vm = AuthViewModel(configuredStore(), seams)

    kotlinx.coroutines.runBlocking { vm.loadTenants() }

    assertTrue(vm.tenants.value.isEmpty())
  }

  @Test
  fun `logout clears tenants too`() {
    val store = configuredStore()
    store.adoptLogin("alice", "t-1", "tok-1", "ref-1")
    val seams =
        AuthViewModel.Seams(
            logout = {},
            whoami = { WhoamiUser("alice", null, "t-1") },
            listTenants = { listOf(TenantItem("t-1", null, null)) },
        )
    val vm = AuthViewModel(store, seams)
    kotlinx.coroutines.runBlocking { vm.restore() }
    kotlinx.coroutines.runBlocking { vm.loadTenants() }
    assertTrue(vm.tenants.value.isNotEmpty())

    kotlinx.coroutines.runBlocking { vm.logout() }

    assertTrue(vm.tenants.value.isEmpty())
  }
}

/** 非 AuthError 的任意异常样本（detekt 禁测试里抛泛 RuntimeException，自定专用型）。 */
private class BoomError(message: String) : Exception(message)
