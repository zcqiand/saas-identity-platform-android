package io.xr.harness.ui

import io.xr.harness.core.AuthError
import io.xr.harness.core.LoginOutcome
import io.xr.harness.core.SessionState
import io.xr.harness.core.SessionStore
import io.xr.harness.core.SwitchOutcome
import io.xr.harness.core.TenantItem
import io.xr.harness.core.WhoamiUser
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
 * loginSeam），单测用 fake seam（ADR-0048 姿态）。restore() 启动自愈：有 token 才乐观 Ready，whoami 失败即清密回登录。
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
      val whoami: suspend () -> WhoamiUser? = { throw IllegalStateException("seam 未注入: whoami") },
      val switchTenant: suspend (tenantId: String) -> SwitchOutcome = { _ ->
        throw IllegalStateException("seam 未注入: switchTenant")
      },
      val listTenants: suspend () -> List<TenantItem> = {
        throw IllegalStateException("seam 未注入: listTenants")
      },
  )

  private val _phase = MutableStateFlow<AuthPhase>(AuthPhase.Idle)
  val phase: StateFlow<AuthPhase> = _phase.asStateFlow()

  private val _session = MutableStateFlow(store.state)
  val session: StateFlow<SessionState> = _session.asStateFlow()

  private val _user = MutableStateFlow<WhoamiUser?>(null)
  val user: StateFlow<WhoamiUser?> = _user.asStateFlow()

  private val _tenants = MutableStateFlow<List<TenantItem>>(emptyList())
  val tenants: StateFlow<List<TenantItem>> = _tenants.asStateFlow()

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
        store.adoptLogin(
            outcome.username,
            outcome.tenantId,
            outcome.accessToken,
            outcome.refreshToken,
        )
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

  /** 启动自愈：持久化会话先乐观上屏，whoami 校验——失败（401/网络）清密回 needsLogin（不静默留死会话）。 */
  suspend fun restore() {
    _session.value = store.state
    if (store.state !is SessionState.Ready) {
      return
    }
    val who =
        try {
          seams.whoami()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          null
        }
    if (who == null) {
      store.clearSession()
      _session.value = store.state
      return
    }
    _user.value = who
  }

  /**
   * 切租户（swift vm.switchTenant 同构）：换发 token 由 seam 入账，成功后身份位收编 + whoami 重拉； 失败红字报错，store 未被 触碰（seam
   * 内 adopt 前置校验），可重试。
   */
  suspend fun switchTenant(tenantId: String): Boolean {
    _phase.value = AuthPhase.Busy
    val outcome =
        try {
          seams.switchTenant(tenantId)
        } catch (e: CancellationException) {
          throw e
        } catch (e: AuthError) {
          SwitchOutcome.Failure(e)
        } catch (e: Exception) {
          SwitchOutcome.Failure(AuthError.Unreachable(e))
        }
    return when (outcome) {
      is SwitchOutcome.Success -> {
        store.adoptTenant(outcome.tenantId, outcome.expiresAt)
        _session.value = store.state
        refreshUser()
        _phase.value = AuthPhase.Idle
        true
      }
      is SwitchOutcome.Failure -> {
        _phase.value = AuthPhase.Failed(outcome.error.message ?: "切换租户失败")
        false
      }
    }
  }

  /** 租户成员真值（swift 进页 meListMyTenants 同位）：失败静默空列表（离线不炸，无重试轰炸）。 */
  suspend fun loadTenants() {
    _tenants.value =
        try {
          seams.listTenants()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          emptyList()
        }
  }

  suspend fun logout() {
    runCatching { seams.logout() }
    store.clearSession()
    _user.value = null
    _tenants.value = emptyList()
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

  /** whoami 重拉（切租户后用户位刷新）：失败不清会话——切换已成功，新 token 有效。 */
  private suspend fun refreshUser() {
    val who =
        try {
          seams.whoami()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          null
        }
    if (who != null) {
      _user.value = who
    }
  }
}
