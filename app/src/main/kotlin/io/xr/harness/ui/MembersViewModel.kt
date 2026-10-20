@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.ui

import io.xr.harness.core.AuthError
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import saas.identity.shared.models.CreateSysUserRequest
import saas.identity.shared.models.SysRole
import saas.identity.shared.models.TenantMemberStatus
import saas.identity.shared.models.TenantMemberUserView
import saas.identity.shared.models.TenantMemberView
import saas.identity.shared.models.TenantMembersInviteTenantUserRequest
import saas.identity.shared.models.TenantMembersListTenantUsers200Response
import saas.identity.shared.models.TenantRolesListSysRoles200Response
import saas.identity.shared.models.UpdateSysUserRequest

/**
 * 成员管理 VM（swift MembersViewModel 同构，M01.F02 + M00.F02，REQ-2026-003/009）。 租户上下文经构造期 provider 注入， nil
 * = fail-fast 红字且缝不被碰； load 并发拉成员+角色； assignRoles = PUT 全量覆盖后 getMember 权威回读原位替换 （PUT 响应 status
 * 不可信）； 生命周期成功原位维护列表不重拉，失败只落 Failed 红字（HTTP 码随 message），同一 VM 可直接重试。
 */
class MembersViewModel(
    private val tenantId: () -> String?,
    private val seams: Seams,
) {
  data class Seams(
      val listMembers: suspend (String) -> TenantMembersListTenantUsers200Response,
      val listRoles: suspend (String) -> TenantRolesListSysRoles200Response,
      val assignRoles: suspend (String, String, List<String>) -> TenantMemberUserView = { _, _, _ ->
        throw AuthError.Server("角色分配缝未注入")
      },
      val getMember: suspend (String, String) -> TenantMemberUserView = { _, _ ->
        throw AuthError.Server("成员详情缝未注入")
      },
      val createMember: suspend (String, CreateSysUserRequest) -> TenantMemberUserView = { _, _ ->
        throw AuthError.Server("新建成员缝未注入")
      },
      val updateMember: suspend (String, String, UpdateSysUserRequest) -> TenantMemberUserView =
          { _, _, _ ->
            throw AuthError.Server("编辑成员缝未注入")
          },
      val changeStatus: suspend (String, String, TenantMemberStatus) -> TenantMemberUserView =
          { _, _, _ ->
            throw AuthError.Server("成员状态缝未注入")
          },
      val deleteMember: suspend (String, String) -> Unit = { _, _ ->
        throw AuthError.Server("移除成员缝未注入")
      },
      val inviteMember: suspend (String, TenantMembersInviteTenantUserRequest) -> TenantMemberView =
          { _, _ ->
            throw AuthError.Server("邀请成员缝未注入")
          },
  )

  sealed interface Phase {
    data object Idle : Phase

    data object Busy : Phase

    data class Failed(val message: String) : Phase
  }

  private val _phase = MutableStateFlow<Phase>(Phase.Idle)
  val phase: StateFlow<Phase> = _phase.asStateFlow()

  private val _members = MutableStateFlow<List<TenantMemberUserView>>(emptyList())
  val members: StateFlow<List<TenantMemberUserView>> = _members.asStateFlow()

  private val _roles = MutableStateFlow<List<SysRole>>(emptyList())
  val roles: StateFlow<List<SysRole>> = _roles.asStateFlow()

  /** 并发拉成员 + 角色清单（swift async let 同构），成功整表替换。 */
  suspend fun load(): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法加载成员")
    return runOp("加载成员失败") {
      coroutineScope {
        val membersCall = async { seams.listMembers(tenant) }
        val rolesCall = async { seams.listRoles(tenant) }
        _members.value = membersCall.await().items
        _roles.value = rolesCall.await().items
      }
    }
  }

  /** 分配角色：PUT 全量覆盖提交勾选集合，再 getMember 回读原位替换（PUT 响应 status 不可信）。 */
  suspend fun assignRoles(roleIds: List<String>, memberID: Uuid): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法分配角色")
    return runOp("分配角色失败") {
      seams.assignRoles(tenant, memberID.toString(), roleIds)
      replaceRow(seams.getMember(tenant, memberID.toString()))
    }
  }

  /** 新建成员：email 后端必填（REQ-009 Q1），UI 层校验；成功追加行不重拉。 */
  suspend fun create(username: String, password: String, email: String?, mobile: String?): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法新建成员")
    return runOp("新建成员失败") {
      val created =
          seams.createMember(
              tenant,
              CreateSysUserRequest(
                  username = username,
                  password = password,
                  email = email,
                  mobile = mobile,
              ),
          )
      _members.value = _members.value + created
    }
  }

  /** 编辑成员：PATCH 部分段（email/mobile），返回体原位替换行。 */
  suspend fun update(memberID: Uuid, email: String?, mobile: String?): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法编辑成员")
    return runOp("保存成员失败") {
      replaceRow(
          seams.updateMember(
              tenant,
              memberID.toString(),
              UpdateSysUserRequest(email = email, mobile = mobile),
          )
      )
    }
  }

  /** 切换成员状态（挂起/恢复/停用）：以返回体原位替换行。 */
  suspend fun changeStatus(memberID: Uuid, to: TenantMemberStatus): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法切换成员状态")
    return runOp("切换成员状态失败") { replaceRow(seams.changeStatus(tenant, memberID.toString(), to)) }
  }

  /** 移除成员：204 无响应体，原位移除行。危险确认在 UI 层。 */
  suspend fun remove(memberID: Uuid): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法移除成员")
    return runOp("移除成员失败") {
      seams.deleteMember(tenant, memberID.toString())
      _members.value = _members.value.filterNot { it.id == memberID }
    }
  }

  /** 邀请成员：响应是嵌套 TenantMemberView，扁平化追加（口径对不上显式失败，不静默兜底）。 */
  suspend fun invite(email: String?, mobile: String?): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法邀请成员")
    return runOp("邀请成员失败") {
      val view =
          seams.inviteMember(
              tenant,
              TenantMembersInviteTenantUserRequest(email = email, mobile = mobile),
          )
      val row = flatRow(view) ?: throw AuthError.Server("邀请响应 status 无法映射到成员口径")
      _members.value = _members.value + row
    }
  }

  /** 刷新单成员：getMember 权威回读原位替换（角色分配回读共用）。 */
  suspend fun refresh(memberID: Uuid): Boolean {
    val tenant = tenantId() ?: return failFast("未选择租户，无法刷新成员")
    return runOp("刷新成员失败") { replaceRow(seams.getMember(tenant, memberID.toString())) }
  }

  private fun replaceRow(updated: TenantMemberUserView) {
    _members.value = _members.value.map { if (it.id == updated.id) updated else it }
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

  companion object {
    /**
     * 嵌套邀请视图 → 扁平成员行：id/username/status/时间取 user.*，tenantId 取 member.tenantId，roleIds 取 view.roles。
     * user.status 在成员口径里对不上（生成层两枚举值集差异）即返回 null，由调用方显式失败（Q4 不静默兜底）。
     */
    fun flatRow(view: TenantMemberView): TenantMemberUserView? {
      val status =
          TenantMemberStatus.entries.firstOrNull { it.value == view.user.status.value }
              ?: return null
      return TenantMemberUserView(
          id = view.user.id,
          tenantId = view.member.tenantId,
          username = view.user.username,
          status = status,
          roleIds = view.roles,
          createdAt = view.user.createdAt,
          updatedAt = view.user.updatedAt,
          email = view.user.email,
      )
    }
  }
}
