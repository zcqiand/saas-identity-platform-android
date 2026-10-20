@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.ui

import io.xr.harness.core.AuthError
import io.xr.harness.core.SessionStore
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import saas.identity.shared.models.OAuthAuthorize200Response
import saas.identity.shared.models.TokenResponse

/**
 * OAuthViewModel fake-seam JVM 单测（swift OAuthViewModelTests 同构，M04.F03）： CSRF state
 * 自生成且回传校验，不一致丢码并阻断 token 端点； 换码/刷新入账走 SessionStore.adoptOAuthToken（真实 store，内存 prefs/tokens）；
 * 失败可重试且旧会话不动。
 */
class OAuthViewModelTest {

  private val tenantA = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
  private val tenantB = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

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

  class MemTokens : io.xr.harness.core.TokenStoring {
    var access: String? = null
    var refresh: String? = null

    override fun loadAccess(): String? = access

    override fun loadRefresh(): String? = refresh

    override fun save(accessToken: String?, refreshToken: String?) {
      access = accessToken
      refresh = refreshToken
    }
  }

  /** swift makeStore 同构：已配 client 的登录态 store（tk-1/rt-1，租户 A）。 */
  private fun makeStore(prefs: MemPrefs, tokens: MemTokens): SessionStore {
    val store = SessionStore(prefs, tokens)
    store.saveConfig("http://10.0.2.2:5104", "saas-android")
    store.adoptLogin(
        "alice",
        tenantId = tenantA.toString(),
        accessToken = "tk-1",
        refreshToken = "rt-1",
    )
    return store
  }

  private fun token(
      access: String,
      refresh: String,
      tenantId: Uuid = tenantA,
  ): TokenResponse =
      TokenResponse(
          accessToken = access,
          refreshToken = refresh,
          tokenType = "Bearer",
          expiresIn = 3600,
          userId = "u-1",
          clientId = "saas-android",
          tenantId = tenantId,
      )

  @Test
  fun `authorize records code and echoed state`() = runBlocking {
    val prefs = MemPrefs()
    val tokens = MemTokens()
    val store = makeStore(prefs, tokens)
    var sentState: String? = null
    val vm =
        OAuthViewModel(
            store = store,
            seams =
                OAuthViewModel.Seams(
                    authorize = { _, _, _, state ->
                      sentState = state
                      OAuthAuthorize200Response(code = "auth-code-1", state = state)
                    },
                ),
        )

    val ok = vm.authorize()

    assertTrue(ok)
    assertEquals("auth-code-1", vm.lastCode.value)
    assertEquals(sentState, vm.lastState.value, "展示的 state 必须是本端生成并由后端回传的那个")
    assertEquals("tk-1", tokens.access, "授权码签发不动会话")
    assertEquals("rt-1", tokens.refresh)
  }

  @Test
  fun `authorize state mismatch drops code and blocks exchange`() = runBlocking {
    val prefs = MemPrefs()
    val tokens = MemTokens()
    val store = makeStore(prefs, tokens)
    var exchangeCalled = false
    val vm =
        OAuthViewModel(
            store = store,
            seams =
                OAuthViewModel.Seams(
                    authorize = { _, _, _, _ ->
                      OAuthAuthorize200Response(code = "evil-code", state = "forged")
                    },
                    exchangeCode = { _, _, _ ->
                      exchangeCalled = true
                      token("tk-2", "rt-2")
                    },
                ),
        )

    val ok = vm.authorize()

    assertFalse(ok)
    assertTrue(vm.phase.value is OAuthViewModel.Phase.Failed, "state 回传不一致必须报红字")
    assertNull(vm.lastCode.value, "不一致的 code 必须被丢弃")
    assertNull(vm.lastState.value)

    val exchange = vm.exchangeCode()
    assertFalse(exchange, "被丢弃的 code 不许流向 token 端点")
    assertFalse(exchangeCalled)
  }

  @Test
  fun `exchange success adopts token pair`() = runBlocking {
    val prefs = MemPrefs()
    val tokens = MemTokens()
    val store = makeStore(prefs, tokens)
    val vm =
        OAuthViewModel(
            store = store,
            seams =
                OAuthViewModel.Seams(
                    authorize = { _, _, _, state ->
                      OAuthAuthorize200Response(code = "auth-code-1", state = state)
                    },
                    exchangeCode = { code, clientId, redirectUri ->
                      assertEquals("auth-code-1", code)
                      assertEquals("saas-android", clientId)
                      assertEquals(OAuthViewModel.REDIRECT_URI, redirectUri)
                      token("tk-2", "rt-2", tenantId = tenantB)
                    },
                ),
        )
    vm.authorize()

    val ok = vm.exchangeCode()

    assertTrue(ok)
    assertEquals("tk-2", tokens.access)
    assertEquals("rt-2", tokens.refresh)
    assertEquals(tenantB.toString(), store.tenantId, "OAuth 入账须对齐 token 所带租户")
  }

  @Test
  fun `exchange records expiresAt in store`() = runBlocking {
    val prefs = MemPrefs()
    val tokens = MemTokens()
    val store = makeStore(prefs, tokens)
    val vm =
        OAuthViewModel(
            store = store,
            seams =
                OAuthViewModel.Seams(
                    authorize = { _, _, _, state ->
                      OAuthAuthorize200Response(code = "auth-code-1", state = state)
                    },
                    exchangeCode = { _, _, _ -> token("tk-2", "rt-2") },
                ),
        )
    vm.authorize()

    val ok = vm.exchangeCode()

    assertTrue(ok)
    assertNotNull(store.expiresAt, "换码入账必须记录过期时刻（token.expiresIn=3600）")
  }

  @Test
  fun `exchange failure is retryable keeping old session`() = runBlocking {
    val prefs = MemPrefs()
    val tokens = MemTokens()
    val store = makeStore(prefs, tokens)
    var reject = true
    val vm =
        OAuthViewModel(
            store = store,
            seams =
                OAuthViewModel.Seams(
                    authorize = { _, _, _, state ->
                      OAuthAuthorize200Response(code = "auth-code-1", state = state)
                    },
                    exchangeCode = { _, _, _ ->
                      if (reject) throw AuthError.Server("请求失败（HTTP 400）")
                      token("tk-2", "rt-2")
                    },
                ),
        )
    vm.authorize()

    val failed = vm.exchangeCode()

    assertFalse(failed)
    assertTrue(
        (vm.phase.value as OAuthViewModel.Phase.Failed).message.contains("400"),
        "红字带 HTTP 码",
    )
    assertEquals("tk-1", tokens.access, "失败旧会话不动")
    assertEquals("rt-1", tokens.refresh)

    reject = false
    val retry = vm.exchangeCode()
    assertTrue(retry, "同一 VM 可直接重试")
    assertEquals("tk-2", tokens.access)
  }

  @Test
  fun `exchange without code fails fast and never calls seam`() = runBlocking {
    var seamCalled = false
    val prefs = MemPrefs()
    val tokens = MemTokens()
    val store = makeStore(prefs, tokens)
    val vm =
        OAuthViewModel(
            store = store,
            seams =
                OAuthViewModel.Seams(
                    exchangeCode = { _, _, _ ->
                      seamCalled = true
                      token("tk-2", "rt-2")
                    },
                ),
        )

    val ok = vm.exchangeCode()

    assertFalse(ok)
    assertFalse(seamCalled, "无授权码必须 fail-fast，缝都不许被碰")
    assertTrue(
        (vm.phase.value as OAuthViewModel.Phase.Failed).message.contains("授权码"),
        "红字说明缺授权码",
    )
  }

  @Test
  fun `refresh rotates token pair`() = runBlocking {
    val prefs = MemPrefs()
    val tokens = MemTokens()
    val store = makeStore(prefs, tokens)
    var sentRefresh: String? = null
    val vm =
        OAuthViewModel(
            store = store,
            seams =
                OAuthViewModel.Seams(
                    refreshToken = { current, _ ->
                      sentRefresh = current
                      token("tk-3", "rt-3")
                    },
                ),
        )

    val ok = vm.refreshToken()

    assertTrue(ok)
    assertEquals("rt-1", sentRefresh, "刷新必须送出当前 refresh token")
    assertEquals("tk-3", tokens.access)
    assertEquals("rt-3", tokens.refresh)
  }

  @Test
  fun `refresh without refresh token fails fast`() = runBlocking {
    var seamCalled = false
    val prefs = MemPrefs()
    val tokens = MemTokens()
    val store = makeStore(prefs, tokens)
    store.adoptLogin(
        "alice",
        tenantId = tenantA.toString(),
        accessToken = "tk-1",
        refreshToken = null,
    )
    val vm =
        OAuthViewModel(
            store = store,
            seams =
                OAuthViewModel.Seams(
                    refreshToken = { _, _ ->
                      seamCalled = true
                      token("tk-3", "rt-3")
                    },
                ),
        )

    val ok = vm.refreshToken()

    assertFalse(ok)
    assertFalse(seamCalled, "无 refresh token 必须 fail-fast，缝都不许被碰")
    assertTrue(
        (vm.phase.value as OAuthViewModel.Phase.Failed).message.contains("refresh"),
        "红字说明缺 refresh token",
    )
  }
}
