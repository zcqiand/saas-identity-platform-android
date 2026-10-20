package io.xr.harness.app

import io.ktor.client.statement.bodyAsText
import io.xr.harness.core.ApiGlue
import io.xr.harness.core.AuthError
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.ui.MembersViewModel
import saas.identity.shared.api.TenantMembersApi
import saas.identity.shared.api.TenantRolesApi
import saas.identity.shared.infrastructure.HttpResponse
import saas.identity.shared.models.CreateSysUserRequest
import saas.identity.shared.models.SetTenantMemberRolesRequest
import saas.identity.shared.models.TenantMemberStatus
import saas.identity.shared.models.TenantMemberUserView
import saas.identity.shared.models.TenantMemberView
import saas.identity.shared.models.TenantMembersChangeTenantUserStatusRequest
import saas.identity.shared.models.TenantMembersInviteTenantUserRequest
import saas.identity.shared.models.TenantMembersListTenantUsers200Response
import saas.identity.shared.models.TenantRolesListSysRoles200Response
import saas.identity.shared.models.UpdateSysUserRequest

/**
 * 成员管理 9 缝（swift MembersViewModel.Seams 同位，M01.F02 + M00.F02）：唯一摸 TenantMembersApi/TenantRolesApi
 * 的地方。 租户/成员 id 字符串寻址；list 不传分页（nil 全量）； 非 2xx 手解（生成 client 不抛）→ mapHttpError 抛出；传输异常归一
 * Unreachable（guard 统一护栏，AdminClientsWire 同构）。
 */
fun membersSeams(
    store: SessionStore,
    tokens: TokenHolder,
): MembersViewModel.Seams {
  return MembersViewModel.Seams(
      listMembers = { tenantId -> guard { listCall(store, tokens, tenantId) } },
      listRoles = { tenantId -> guard { rolesCall(store, tokens, tenantId) } },
      assignRoles = { tenantId, userId, roleIds ->
        guard { assignCall(store, tokens, tenantId, userId, roleIds) }
      },
      getMember = { tenantId, userId -> guard { getCall(store, tokens, tenantId, userId) } },
      createMember = { tenantId, request ->
        guard { createCall(store, tokens, tenantId, request) }
      },
      updateMember = { tenantId, userId, request ->
        guard { updateCall(store, tokens, tenantId, userId, request) }
      },
      changeStatus = { tenantId, userId, status ->
        guard { statusCall(store, tokens, tenantId, userId, status) }
      },
      deleteMember = { tenantId, userId -> guard { deleteCall(store, tokens, tenantId, userId) } },
      inviteMember = { tenantId, request ->
        guard { inviteCall(store, tokens, tenantId, request) }
      },
  )
}

/** 缝护栏：AuthError 透传 + 任意传输层异常归一 Unreachable（AdminClientsWire 同位）。 */
private inline fun <T> guard(block: () -> T): T =
    try {
      block()
    } catch (e: AuthError) {
      throw e
    } catch (e: Exception) {
      throw AuthError.Unreachable(e)
    }

private suspend fun listCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
): TenantMembersListTenantUsers200Response {
  val resp = callMembers(store, tokens).tenantMembersListTenantUsers(tenantId)
  requireOk(resp)
  return resp.body()
}

private suspend fun rolesCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
): TenantRolesListSysRoles200Response {
  val resp = callRoles(store, tokens).tenantRolesListSysRoles(tenantId)
  requireOk(resp)
  return resp.body()
}

private suspend fun assignCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    userId: String,
    roleIds: List<String>,
): TenantMemberUserView {
  val resp =
      callMembers(store, tokens)
          .tenantMembersAssignTenantMemberRoles(
              tenantId,
              userId,
              SetTenantMemberRolesRequest(roleIds = roleIds),
          )
  requireOk(resp)
  return resp.body()
}

private suspend fun getCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    userId: String,
): TenantMemberUserView {
  val resp = callMembers(store, tokens).tenantMembersGetTenantUser(tenantId, userId)
  requireOk(resp)
  return resp.body()
}

private suspend fun createCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    request: CreateSysUserRequest,
): TenantMemberUserView {
  val resp = callMembers(store, tokens).tenantMembersCreateTenantUser(tenantId, request)
  requireOk(resp)
  return resp.body()
}

private suspend fun updateCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    userId: String,
    request: UpdateSysUserRequest,
): TenantMemberUserView {
  val resp = callMembers(store, tokens).tenantMembersUpdateTenantUser(tenantId, userId, request)
  requireOk(resp)
  return resp.body()
}

private suspend fun statusCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    userId: String,
    status: TenantMemberStatus,
): TenantMemberUserView {
  val resp =
      callMembers(store, tokens)
          .tenantMembersChangeTenantUserStatus(
              tenantId,
              userId,
              TenantMembersChangeTenantUserStatusRequest(status = status),
          )
  requireOk(resp)
  return resp.body()
}

private suspend fun deleteCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    userId: String,
) {
  val resp = callMembers(store, tokens).tenantMembersDeleteTenantUser(tenantId, userId)
  requireOk(resp)
  return resp.body()
}

private suspend fun inviteCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    request: TenantMembersInviteTenantUserRequest,
): TenantMemberView {
  val resp = callMembers(store, tokens).tenantMembersInviteTenantUser(tenantId, request)
  requireOk(resp)
  return resp.body()
}

/** TenantMembers Api 构造（唯一摸 TenantMembersApi 全量面的入口；baseUrl 缺失显式抛）。 */
private suspend fun callMembers(
    store: SessionStore,
    tokens: TokenHolder,
): TenantMembersApi {
  val baseUrl = store.baseUrl ?: throw AuthError.ClientNotConfigured()
  return TenantMembersApi(
      baseUrl = ApiGlue.normalizeBaseURL(baseUrl),
      httpClientConfig = ApiGlue.bearerClientConfig(tokens),
  )
}

/** TenantRoles Api 构造（角色清单缝专用；baseUrl 缺失显式抛）。 */
private suspend fun callRoles(
    store: SessionStore,
    tokens: TokenHolder,
): TenantRolesApi {
  val baseUrl = store.baseUrl ?: throw AuthError.ClientNotConfigured()
  return TenantRolesApi(
      baseUrl = ApiGlue.normalizeBaseURL(baseUrl),
      httpClientConfig = ApiGlue.bearerClientConfig(tokens),
  )
}

/** 非 2xx 手解（生成 client 不抛）→ mapHttpError（AdminClientsWire 同位，private per-file）。 */
private suspend fun <T> requireOk(resp: HttpResponse<T>) {
  if (!resp.success) {
    val bodyText = runCatching { resp.response.bodyAsText() }.getOrNull()
    throw ApiGlue.mapHttpError(resp.status, bodyText)
  }
}
