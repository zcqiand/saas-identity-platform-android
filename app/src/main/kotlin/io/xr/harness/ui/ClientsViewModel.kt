package io.xr.harness.ui

import io.xr.harness.core.AuthError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import saas.identity.shared.models.OAuthClient

/**
 * 客户端清单 VM（swift AdminClientsViewModel 只读子集）：菜单管理页的 client 选择器供数面。 全量清单不传分页（live 实证 0-indexed，传 nil
 * 全量）；CRUD 归后刀。
 */
class ClientsViewModel(internal val seams: Seams = Seams()) {
  data class Seams(
      val listClients: suspend () -> List<OAuthClient> = {
        throw IllegalStateException("seam 未注入: listClients")
      },
  )

  sealed interface Phase {
    data object Idle : Phase

    data object Busy : Phase

    data class Failed(val message: String) : Phase
  }

  private val _phase = MutableStateFlow<Phase>(Phase.Idle)
  val phase: StateFlow<Phase> = _phase.asStateFlow()

  private val _clients = MutableStateFlow<List<OAuthClient>>(emptyList())
  val clients: StateFlow<List<OAuthClient>> = _clients.asStateFlow()

  /** 拉取 client 全量清单。 */
  suspend fun load(): Boolean {
    _phase.value = Phase.Busy
    return try {
      _clients.value = seams.listClients()
      _phase.value = Phase.Idle
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _phase.value = Phase.Failed(messageOf(e))
      false
    }
  }

  private fun messageOf(e: Exception): String =
      if (e is AuthError) e.message ?: "请求失败" else "请求失败（${e.message}）"
}
