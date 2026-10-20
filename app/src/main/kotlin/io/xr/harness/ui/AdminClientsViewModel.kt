package io.xr.harness.ui

import io.xr.harness.core.AuthError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import saas.identity.shared.models.AdminClientsListClients200Response
import saas.identity.shared.models.CreateOAuthClientRequest
import saas.identity.shared.models.OAuthClient
import saas.identity.shared.models.UpdateOAuthClientRequest

/**
 * OAuth 客户端管理 VM（swift AdminClientsViewModel 同构，M04.F01/F02，REQ-2026-005/006）。 列表 nil 全量拉取（live
 * 实证分页 0-indexed，不传分页）； create/update/setStatus 成功后原位维护列表不重拉； 失败只落 Failed 红字（HTTP 码随
 * message），列表不动，同一 VM 可直接重试。
 */
class AdminClientsViewModel(private val seams: Seams) {
  data class Seams(
      val listClients: suspend () -> AdminClientsListClients200Response,
      val getClient: suspend (String) -> OAuthClient,
      val createClient: suspend (CreateOAuthClientRequest) -> OAuthClient = {
        throw AuthError.Server("当前后端未提供创建客户端能力")
      },
      val updateClient: suspend (String, UpdateOAuthClientRequest) -> OAuthClient = { _, _ ->
        throw AuthError.Server("当前后端未提供更新客户端能力")
      },
      val deleteClient: suspend (String) -> Unit = { _ ->
        throw AuthError.Server("当前后端未提供删除客户端能力")
      },
      val setClientStatus: suspend (String, Int) -> OAuthClient = { _, _ ->
        throw AuthError.Server("当前后端未提供状态切换能力")
      },
  )

  sealed interface Phase {
    data object Idle : Phase

    data class Failed(val message: String) : Phase
  }

  private val _phase = MutableStateFlow<Phase>(Phase.Idle)
  val phase: StateFlow<Phase> = _phase.asStateFlow()

  private val _clients = MutableStateFlow<List<OAuthClient>>(emptyList())
  val clients: StateFlow<List<OAuthClient>> = _clients.asStateFlow()

  /** 全量拉取客户端列表（不传分页），成功整表替换，失败清空可重试。 */
  suspend fun load(): Boolean =
      runOp("加载客户端失败") {
        val page = seams.listClients()
        _clients.value = page.items
      }

  /** 创建客户端：成功追加行，不重拉（AC-4）。 */
  suspend fun create(request: CreateOAuthClientRequest): Boolean =
      runOp("创建客户端失败") {
        val created = seams.createClient(request)
        _clients.value = _clients.value + created
      }

  /** 更新客户端：按 clientId 原位替换行，不重拉（AC-3）。 */
  suspend fun update(clientId: String, request: UpdateOAuthClientRequest): Boolean =
      runOp("保存客户端失败") {
        val updated = seams.updateClient(clientId, request)
        replaceRow(updated)
      }

  /** 删除客户端：按 clientId 原位移除行（AC-4）。危险确认在 UI 层。 */
  suspend fun delete(clientId: String): Boolean =
      runOp("删除客户端失败") {
        seams.deleteClient(clientId)
        _clients.value = _clients.value.filterNot { it.clientId == clientId }
      }

  /** 启用/停用（status 1=启用 0=停用）：以返回体原位替换行，不重拉（AC-3）。 */
  suspend fun setStatus(clientId: String, status: Int): Boolean =
      runOp("切换客户端状态失败") { replaceRow(seams.setClientStatus(clientId, status)) }

  private fun replaceRow(updated: OAuthClient) {
    _clients.value = _clients.value.map { if (it.clientId == updated.clientId) updated else it }
  }

  private inline fun runOp(failureLabel: String, block: () -> Unit): Boolean =
      try {
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
