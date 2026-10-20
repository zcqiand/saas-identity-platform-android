@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.ui

import io.xr.harness.core.AuthError
import java.time.OffsetDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import saas.identity.shared.models.CreateSysRoleRequest
import saas.identity.shared.models.RoleMenuGrant
import saas.identity.shared.models.SetSysRoleMenusRequest
import saas.identity.shared.models.SysRole
import saas.identity.shared.models.TenantRolesListSysRoles200Response
import saas.identity.shared.models.UpdateSysRoleRequest

/**
 * 租户角色 VM（swift RolesViewModel 同构，M00.F03 CRUD + M00.F04 菜单授权，REQ-2026-010/011）。 不并入
 * MembersViewModel——成员页角色清单是勾选数据源语义，本 VM 是 CRUD + 授权语义（REQ-010 Q2）。 租户上下文经构造期 provider 注入，nil =
 * fail-fast 红字且缝不被碰； 生命周期成功原位替换/追加行（update 响应可信，REQ-010 探针 readback 实证）； 授权 PUT
 * 全量替换且授权态以响应为准（readback 顺序不保证，消费端 Set 语义）； 失败只红字 HTTP 码随 message， 同一 VM 可直接重试。
 */
class RolesViewModel(
    private val tenantId: () -> String?,
    private val seams: Seams,
) {
  data class Seams(
      val listRoles: suspend (String) -> TenantRolesListSysRoles200Response,
      val createRole: suspend (String, CreateSysRoleRequest) -> SysRole = { _, _ ->
        throw AuthError.Server("新建角色缝未注入")
      },
      val getRole: suspend (String, String) -> SysRole = { _, _ ->
        throw AuthError.Server("角色详情缝未注入")
      },
      val updateRole: suspend (String, String, UpdateSysRoleRequest) -> SysRole = { _, _, _ ->
        throw AuthError.Server("编辑角色缝未注入")
      },
      val deleteRole: suspend (String, String) -> Unit = { _, _ ->
        throw AuthError.Server("移除角色缝未注入")
      },
      val listGrants: suspend (String, String) -> RoleMenuGrant = { _, _ ->
        throw AuthError.Server("授权查询缝未注入")
      },
      val setGrants: suspend (String, String, SetSysRoleMenusRequest) -> RoleMenuGrant =
          { _, _, _ ->
            throw AuthError.Server("授权保存缝未注入")
          },
      val clearGrants: suspend (String, String) -> Unit = { _, _ ->
        throw AuthError.Server("授权清空缝未注入")
      },
  )

  sealed interface Phase {
    data object Idle : Phase

    data object Busy : Phase

    data class Failed(val message: String) : Phase
  }

  private val _phase = MutableStateFlow<Phase>(Phase.Idle)
  val phase: StateFlow<Phase> = _phase.asStateFlow()

  private val _roles = MutableStateFlow<List<SysRole>>(emptyList())
  val roles: StateFlow<List<SysRole>> = _roles.asStateFlow()

  /** 已选角色的菜单授权（M00.F04 详情页段；menuIds 后端顺序不保证——消费端一律 Set 语义）。 */
  private val _grants = MutableStateFlow<RoleMenuGrant?>(null)
  val grants: StateFlow<RoleMenuGrant?> = _grants.asStateFlow()

  /** 进页加载（AC-1）：拉角色全量（list 不传分页参，0-indexed 同族口径）。 */
  suspend fun load(): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法加载角色")
    return runOp("加载角色失败") { _roles.value = seams.listRoles(tenant).items }
  }

  /**
   * 新建角色（I02）：clientId/roleCode/roleName 必填（live 实证缺 clientId 400）。重名 roleCode 后端 500 空
   * body（REQ-010 Q1）——红字如实呈现，不做前置重名校验。成功追加行（AC-2）。
   */
  suspend fun create(
      clientId: String,
      roleCode: String,
      roleName: String,
      description: String?,
  ): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法新建角色")
    return runOp("新建角色失败") {
      val created =
          seams.createRole(
              tenant,
              CreateSysRoleRequest(
                  clientId = clientId,
                  roleCode = roleCode,
                  roleName = roleName,
                  description = description,
              ),
          )
      _roles.value = _roles.value + created
    }
  }

  /** 编辑角色（I04）：PATCH partial 只提交改动字段；roleCode 契约上不可改。响应可信；成功原位替换行（AC-3）。 */
  suspend fun update(roleID: Uuid, roleName: String?, description: String?): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法编辑角色")
    return runOp("保存角色失败") {
      replaceRow(
          seams.updateRole(
              tenant,
              roleID.toString(),
              UpdateSysRoleRequest(roleName = roleName, description = description),
          )
      )
    }
  }

  /** 状态启停（AC-4）：status 1=启用 0=停用（Int 契约口径，同 sys_role）。 */
  suspend fun setStatus(roleID: Uuid, to: Int): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法切换角色状态")
    return runOp("切换角色状态失败") {
      replaceRow(
          seams.updateRole(
              tenant,
              roleID.toString(),
              UpdateSysRoleRequest(roleName = null, description = null, status = to),
          )
      )
    }
  }

  /**
   * 移除角色（I05，危险操作）：DELETE 204 空 body → 直接移除本地行（AC-5）。 isPreset 可删性后端规则未探——拦截时红字 如实呈现（REQ-010 非范围）。
   */
  suspend fun remove(roleID: Uuid): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法移除角色")
    return runOp("移除角色失败") {
      seams.deleteRole(tenant, roleID.toString())
      _roles.value = _roles.value.filterNot { it.id == roleID }
    }
  }

  /** 回读单角色（I03）：详情页刷新共用，成功以权威行原位替换。 */
  suspend fun refresh(roleID: Uuid): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法刷新角色")
    return runOp("刷新角色失败") { replaceRow(seams.getRole(tenant, roleID.toString())) }
  }

  /** 读角色授权（I02）：GET …/roles/{roleId}/menus → RoleMenuGrant 聚合；menuIds 顺序不保证（消费端 Set 语义）。 */
  suspend fun loadGrants(roleID: Uuid): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法加载菜单授权")
    return runOp("加载菜单授权失败") { _grants.value = seams.listGrants(tenant, roleID.toString()) }
  }

  /** 保存授权（I03）：PUT 幂等全量替换，授权态以响应为准（响应顺序不保证照存不重排——REQ-011 探针实证）。 */
  suspend fun saveGrants(roleID: Uuid, menuIds: List<String>): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法保存菜单授权")
    return runOp("保存菜单授权失败") {
      _grants.value =
          seams.setGrants(tenant, roleID.toString(), SetSysRoleMenusRequest(menuIds = menuIds))
    }
  }

  /** 清空授权（I04，危险操作）：DELETE 204 空 body——本地聚合落地为空（同后端 GET 语义 menuIds=[]；updatedAt 本地时钟 仅作占位，无消费方）。 */
  suspend fun clearGrants(roleID: Uuid): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法清空菜单授权")
    return runOp("清空菜单授权失败") {
      seams.clearGrants(tenant, roleID.toString())
      _grants.value =
          RoleMenuGrant(
              roleId = roleID,
              tenantId = Uuid.parse(tenant),
              menuIds = emptyList(),
              updatedAt = OffsetDateTime.now(),
          )
    }
  }

  private fun replaceRow(updated: SysRole) {
    _roles.value = _roles.value.map { if (it.id == updated.id) updated else it }
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
