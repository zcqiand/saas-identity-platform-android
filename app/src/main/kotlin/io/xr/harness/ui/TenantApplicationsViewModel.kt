@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.ui

import io.xr.harness.core.AuthError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import saas.identity.shared.models.SubscribeTenantApplicationRequest
import saas.identity.shared.models.TenantApplication
import saas.identity.shared.models.TenantApplicationsListTenantApplications200Response
import saas.identity.shared.models.UpdateTenantApplicationRequest

/**
 * 租户应用订阅 VM（swift TenantApplicationsViewModel 同构，M00.F05，REQ-2026-012）。 寻址契约：PATCH/DELETE 用
 * clientId 字符串列，不是 UUID id。 租户上下文经构造期 provider 注入，nil = fail-fast 红字且缝不被碰； 订阅 expireTime
 * 可空原样上送不兜底（后端曾丢弃）； update PATCH partial——expireTime 不传 = 不改（CT I76 实证），状态启停总带当前 expireTime 提交防误清；
 * 失败只红字 HTTP 码随 message，同一 VM 可直接重试。
 */
class TenantApplicationsViewModel(
    private val tenantId: () -> String?,
    private val seams: Seams,
) {
  data class Seams(
      val list: suspend (String) -> TenantApplicationsListTenantApplications200Response = { _ ->
        throw AuthError.Server("清单缝未注入")
      },
      val subscribe: suspend (String, SubscribeTenantApplicationRequest) -> TenantApplication =
          { _, _ ->
            throw AuthError.Server("订阅缝未注入")
          },
      val update: suspend (String, String, UpdateTenantApplicationRequest) -> TenantApplication =
          { _, _, _ ->
            throw AuthError.Server("更新订阅缝未注入")
          },
      val remove: suspend (String, String) -> Unit = { _, _ -> throw AuthError.Server("移除订阅缝未注入") },
  )

  sealed interface Phase {
    data object Idle : Phase

    data object Busy : Phase

    data class Failed(val message: String) : Phase
  }

  private val _phase = MutableStateFlow<Phase>(Phase.Idle)
  val phase: StateFlow<Phase> = _phase.asStateFlow()

  /** 当前租户订阅清单（AC-1 渲染；失败保持旧值不兜底空数组假象）。 */
  private val _applications = MutableStateFlow<List<TenantApplication>>(emptyList())
  val applications: StateFlow<List<TenantApplication>> = _applications.asStateFlow()

  /** 进页加载（I01）：拉订阅全量（list 不传分页参，0-indexed 同族口径）。 */
  suspend fun load(): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法加载租户应用")
    return runOp("加载租户应用失败") { _applications.value = seams.list(tenant).items }
  }

  /**
   * 订阅（I02）：clientId 必填（oauth_client FK 目标，未知 → 404 如实呈现； 重复订阅后端 400 裸 SQL 泄漏——红字如实呈现， 不做前置重名校验）。
   * expireTime 可空。成功追加行（AC-3）。
   */
  suspend fun subscribe(clientId: String, expireTime: java.time.OffsetDateTime?): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法订阅应用")
    return runOp("订阅应用失败") {
      val created =
          seams.subscribe(
              tenant,
              SubscribeTenantApplicationRequest(
                  clientId = clientId,
                  expireTime = expireTime?.toString(),
              ),
          )
      _applications.value = _applications.value + created
    }
  }

  /** 更新订阅（I03）：PATCH partial。status 0=停用 1=启用（同族口径）；expireTime 不传不改（CT I76 实证）。成功以响应原位替换行（AC-4）。 */
  suspend fun update(
      clientId: String,
      status: Int?,
      expireTime: java.time.OffsetDateTime?,
  ): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法更新订阅")
    return runOp("更新订阅失败") {
      replaceRow(
          seams.update(
              tenant,
              clientId,
              UpdateTenantApplicationRequest(
                  status = status ?: 0,
                  expireTime = expireTime?.toString(),
              ),
          )
      )
    }
  }

  /** 移除订阅（I04，危险操作）：DELETE → 本地按 clientId 移除行（AC-5）。 */
  suspend fun remove(clientId: String): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法移除订阅")
    return runOp("移除订阅失败") {
      seams.remove(tenant, clientId)
      _applications.value = _applications.value.filterNot { it.clientId == clientId }
    }
  }

  private fun replaceRow(updated: TenantApplication) {
    _applications.value =
        _applications.value.map { if (it.clientId == updated.clientId) updated else it }
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
