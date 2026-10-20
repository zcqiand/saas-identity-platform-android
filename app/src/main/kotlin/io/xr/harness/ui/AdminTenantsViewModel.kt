@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.ui

import io.xr.harness.core.AuthError
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import saas.identity.shared.models.AdminTenantsListTenants200Response
import saas.identity.shared.models.CreateTenantRequest
import saas.identity.shared.models.Tenant
import saas.identity.shared.models.UpdateTenantRequest

/**
 * 平台 admin 租户管理 VM（swift AdminTenantsViewModel 同构，M00.F01，REQ-2026-007）：列表 → 创建/更新/删除。 寻址 UUID id 非
 * key（Q1，与 admin/clients 的 clientId 字符串口径相反）； status 字符串枚举 active/suspended（Q2，与 OAuthClient 的 Int
 * 两套口径并存不混用）； tenantKey 重复创建 409（Q3）。 成功原位维护列表（追加/替换/移除，不重拉）；失败只置红字，列表不动可重试。 删除是级联危险操作，确认流在 App
 * 层，本层只执行。
 */
class AdminTenantsViewModel(
    private val seams: Seams,
) {
  data class Seams(
      val list: suspend () -> AdminTenantsListTenants200Response = {
        throw AuthError.Server("清单缝未注入")
      },
      val create: suspend (CreateTenantRequest) -> Tenant = { _ ->
        throw AuthError.Server("创建租户缝未注入")
      },
      val update: suspend (Uuid, UpdateTenantRequest) -> Tenant = { _, _ ->
        throw AuthError.Server("更新租户缝未注入")
      },
      val delete: suspend (Uuid) -> Unit = { _ -> throw AuthError.Server("删除租户缝未注入") },
  )

  sealed interface Phase {
    data object Idle : Phase

    data object Busy : Phase

    data class Failed(val message: String) : Phase
  }

  private val _phase = MutableStateFlow<Phase>(Phase.Idle)
  val phase: StateFlow<Phase> = _phase.asStateFlow()

  /** 租户全量清单（AC-1 渲染；失败保持旧值不兜底空数组假象）。 */
  private val _tenants = MutableStateFlow<List<Tenant>>(emptyList())
  val tenants: StateFlow<List<Tenant>> = _tenants.asStateFlow()

  /** 拉取租户全量清单（I01：list 不传分页，0-indexed 传 null 全量同族口径）。 */
  suspend fun load(): Boolean = runOp("加载租户失败") { _tenants.value = seams.list().items }

  /** 创建租户（I02，tenantKey 重复 409 Q3）。成功追加行（不重拉，AC-2）。 */
  suspend fun create(request: CreateTenantRequest): Boolean =
      runOp("创建租户失败") { _tenants.value = _tenants.value + seams.create(request) }

  /** 更新租户（I03，partial 请求：name / status 字符串枚举）。成功原位替换该行（不重拉，AC-3）。 */
  suspend fun update(id: Uuid, request: UpdateTenantRequest): Boolean =
      runOp("更新租户失败") { replaceRow(seams.update(id, request)) }

  /** 删除租户（I04，级联危险操作：App 层确认后才进这里）。成功原位移除该行（AC-2）。 */
  suspend fun deleteTenant(id: Uuid): Boolean =
      runOp("删除租户失败") {
        seams.delete(id)
        _tenants.value = _tenants.value.filterNot { it.id == id }
      }

  private fun replaceRow(updated: Tenant) {
    _tenants.value = _tenants.value.map { if (it.id == updated.id) updated else it }
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
