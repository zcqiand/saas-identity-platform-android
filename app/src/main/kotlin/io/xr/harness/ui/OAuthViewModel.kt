@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.ui

import io.xr.harness.core.AuthError
import io.xr.harness.core.SessionStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import saas.identity.shared.models.OAuthAuthorize200Response
import saas.identity.shared.models.TokenResponse

/**
 * OAuth 授权 VM（swift OAuthViewModel 同构，M04.F03）：授权码签发（CSRF state 自生成且回传校验，不一致丢码）→ 换码 → 刷新，三 grant 共用
 * redirectURI 与 clientId（store 配置，ADR-0019）。 成功入账走 SessionStore.adoptOAuthToken（登录态收编 + 租户对齐）；
 * 失败红字清签发态，同一 VM 可直接重试。
 */
class OAuthViewModel(
    private val store: SessionStore,
    private val seams: Seams,
) {
  data class Seams(
      val authorize: suspend (String, String, String, String) -> OAuthAuthorize200Response =
          { _, _, _, _ ->
            throw AuthError.Server("授权码签发缝未注入")
          },
      val exchangeCode: suspend (String, String, String) -> TokenResponse = { _, _, _ ->
        throw AuthError.Server("换码缝未注入")
      },
      val refreshToken: suspend (String, String) -> TokenResponse = { _, _ ->
        throw AuthError.Server("刷新缝未注入")
      },
  )

  sealed interface Phase {
    data object Idle : Phase

    data object Busy : Phase

    data class Failed(val message: String) : Phase
  }

  companion object {
    /** OAuth 回调 URI（自定义 scheme，公共 client 免 secret）。 */
    const val REDIRECT_URI = "saasidentity://oauth/callback"

    /** 默认 scope（openid 恒传）。 */
    const val DEFAULT_SCOPE = "openid"
  }

  private val _phase = MutableStateFlow<Phase>(Phase.Idle)
  val phase: StateFlow<Phase> = _phase.asStateFlow()

  /** 最近一次签发的授权码（换码输入；失败即清，被丢弃的 code 不许流向 token 端点）。 */
  private val _lastCode = MutableStateFlow<String?>(null)
  val lastCode: StateFlow<String?> = _lastCode.asStateFlow()

  /** 最近一次签发的 state（本端生成并经后端回传校验的值，展示用）。 */
  private val _lastState = MutableStateFlow<String?>(null)
  val lastState: StateFlow<String?> = _lastState.asStateFlow()

  private var pendingState: String? = null

  /** 签发授权码（I01）：clientId 取 store 配置（未配置静默失败，swift guard 同位）； state 自生成防 CSRF，回传不一致 fail-fast 丢码。 */
  suspend fun authorize(): Boolean {
    val clientId = store.clientId?.takeIf { it.isNotBlank() } ?: return false
    val state = UUID.randomUUID().toString()
    pendingState = state
    return try {
      _phase.value = Phase.Busy
      val response = seams.authorize(clientId, REDIRECT_URI, DEFAULT_SCOPE, state)
      if (response.state != state) {
        clearIssued()
        _phase.value = Phase.Failed("state 回传不一致（疑似 CSRF），已丢弃授权码")
        false
      } else {
        _lastCode.value = response.code
        _lastState.value = response.state
        _phase.value = Phase.Idle
        true
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: AuthError) {
      clearIssued()
      _phase.value = Phase.Failed(e.message ?: "签发授权码失败（未知错误）")
      false
    } catch (e: Exception) {
      clearIssued()
      _phase.value = Phase.Failed("签发授权码失败：${e.message ?: "未知错误"}")
      false
    }
  }

  /** 换码（I02）：需已签发授权码；成功 adoptOAuthToken 入账（对齐 token 所带租户）。 */
  suspend fun exchangeCode(): Boolean {
    val clientId = store.clientId?.takeIf { it.isNotBlank() } ?: return false
    val code = _lastCode.value ?: return failFast("尚无授权码，请先签发")
    return runOp("换码失败") {
      val token = seams.exchangeCode(code, clientId, REDIRECT_URI)
      store.adoptOAuthToken(token.accessToken, token.refreshToken, token.tenantId.toString())
    }
  }

  /** 刷新（I03）：需 store 持 refresh token；成功 adoptOAuthToken 换对入账。 */
  suspend fun refreshToken(): Boolean {
    val clientId = store.clientId?.takeIf { it.isNotBlank() } ?: return false
    val current =
        store.refreshToken?.takeIf { it.isNotBlank() }
            ?: return failFast("尚无 refresh token，请先换 token")
    return runOp("刷新 token 失败") {
      val token = seams.refreshToken(current, clientId)
      store.adoptOAuthToken(token.accessToken, token.refreshToken, token.tenantId.toString())
    }
  }

  private fun clearIssued() {
    _lastCode.value = null
    _lastState.value = null
    pendingState = null
  }

  private fun failFast(message: String): Boolean {
    _phase.value = Phase.Failed(message)
    return false
  }

  private inline fun runOp(failureLabel: String, block: () -> Unit): Boolean =
      try {
        _phase.value = Phase.Busy
        block()
        _phase.value = Phase.Idle
        true
      } catch (e: CancellationException) {
        throw e
      } catch (e: AuthError) {
        _phase.value = Phase.Failed(e.message ?: "$failureLabel（未知错误）")
        false
      } catch (e: Exception) {
        _phase.value = Phase.Failed("$failureLabel：${e.message ?: "未知错误"}")
        false
      }
}
