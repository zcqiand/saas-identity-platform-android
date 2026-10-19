package io.xr.harness.ui

import io.xr.harness.core.AuthError
import io.xr.harness.core.LoginOutcome
import io.xr.harness.core.SessionState
import io.xr.harness.core.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** swift AuthViewModel.Phase 同构：idle / busy / failed(文案)。 */
sealed interface AuthPhase {
  data object Idle : AuthPhase

  data object Busy : AuthPhase

  data class Failed(val message: String) : AuthPhase
}

/**
 * 登录 ViewModel（swift AuthViewModel 同构）：单向数据流 + Seams 闭包注入。 VM 不摸生成 client——真连细节全在 seam 后（App 层
 * loginSeam），单测用 fake seam（ADR-0048 姿态）。
 */
class AuthViewModel(
    private val store: SessionStore,
    internal val seams: Seams = Seams(),
) {
  data class Seams(
      val login: suspend (username: String, password: String, clientId: String) -> LoginOutcome =
          { _, _, _ ->
            throw IllegalStateException("seam 未注入: login")
          },
      val logout: suspend () -> Unit = { throw IllegalStateException("seam 未注入: logout") },
      val switchTenant: suspend (tenantId: String) -> Unit = { _ ->
        throw IllegalStateException("seam 未注入: switchTenant")
      },
      val listTenants: suspend () -> List<String> = {
        throw IllegalStateException("seam 未注入: listTenants")
      },
  )

  private val _phase = MutableStateFlow<AuthPhase>(AuthPhase.Idle)
  val phase: StateFlow<AuthPhase> = _phase.asStateFlow()

  private val _session = MutableStateFlow(store.state)
  val session: StateFlow<SessionState> = _session.asStateFlow()

  suspend fun login(username: String, password: String): Boolean {
    val clientId = store.clientId
    val name = username.trim()
    val outcome =
        when {
          clientId.isNullOrBlank() -> LoginOutcome.Failure(AuthError.ClientNotConfigured())
          name.isEmpty() || password.isEmpty() -> return false
          else -> invokeLogin(name, password, clientId)
        }
    return when (outcome) {
      is LoginOutcome.Success -> {
        store.adoptLogin(outcome.username, outcome.tenantId)
        _session.value = store.state
        _phase.value = AuthPhase.Idle
        true
      }
      is LoginOutcome.Failure -> {
        _phase.value = AuthPhase.Failed(outcome.error.message ?: "登录失败")
        false
      }
    }
  }

  /** 真调 seam：Busy 置位 + 异常分型归一（取消保持取消；AuthError 直接失败；其余归 Unreachable）。 */
  private suspend fun invokeLogin(name: String, password: String, clientId: String): LoginOutcome {
    _phase.value = AuthPhase.Busy
    return try {
      seams.login(name, password, clientId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: AuthError) {
      LoginOutcome.Failure(e)
    } catch (e: Exception) {
      LoginOutcome.Failure(AuthError.Unreachable(e))
    }
  }

  suspend fun logout() {
    runCatching { seams.logout() }
    store.clearSession()
    _session.value = store.state
    _phase.value = AuthPhase.Idle
  }

  /** 回到配置页（清 clientId 一起清，swift 语义：重配即全清）。 */
  fun reconfigure() {
    store.clearConfig()
    _session.value = store.state
  }

  /** 配置页保存后由 UI 调：store 直改不走 VM，状态流需手动对齐。 */
  fun refreshSession() {
    _session.value = store.state
  }
}
