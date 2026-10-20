package io.xr.harness.app

import io.ktor.client.statement.bodyAsText
import io.xr.harness.core.ApiGlue
import io.xr.harness.core.AuthError
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.ui.RolesViewModel
import saas.identity.shared.api.TenantRoleMenusApi
import saas.identity.shared.api.TenantRolesApi
import saas.identity.shared.infrastructure.HttpResponse
import saas.identity.shared.models.CreateSysRoleRequest
import saas.identity.shared.models.RoleMenuGrant
import saas.identity.shared.models.SetSysRoleMenusRequest
import saas.identity.shared.models.SysRole
import saas.identity.shared.models.TenantRolesListSysRoles200Response
import saas.identity.shared.models.UpdateSysRoleRequest

/**
 * 租户角色 8 缝（swift RolesViewModel.Seams 同位，M00.F03/M00.F04）：唯一摸 TenantRolesApi/TenantRoleMenusApi
 * 的地方。 租户/角色 id 字符串寻址；list 不传分页（nil 全量）； 非 2xx 手解（生成 client 不抛）→ mapHttpError 抛出；传输异常归一
 * Unreachable（guard 统一护栏，MembersWire 同构）。
 */
fun rolesSeams(
    store: SessionStore,
    tokens: TokenHolder,
): RolesViewModel.Seams {
  return RolesViewModel.Seams(
      listRoles = { tenantId -> guard { listCall(store, tokens, tenantId) } },
      createRole = { tenantId, request -> guard { createCall(store, tokens, tenantId, request) } },
      getRole = { tenantId, roleId -> guard { getCall(store, tokens, tenantId, roleId) } },
      updateRole = { tenantId, roleId, request ->
        guard { updateCall(store, tokens, tenantId, roleId, request) }
      },
      deleteRole = { tenantId, roleId -> guard { deleteCall(store, tokens, tenantId, roleId) } },
      listGrants = { tenantId, roleId ->
        guard { listGrantsCall(store, tokens, tenantId, roleId) }
      },
      setGrants = { tenantId, roleId, request ->
        guard { setGrantsCall(store, tokens, tenantId, roleId, request) }
      },
      clearGrants = { tenantId, roleId ->
        guard { clearGrantsCall(store, tokens, tenantId, roleId) }
      },
  )
}

/** 缝护栏：AuthError 透传 + 任意传输层异常归一 Unreachable（MembersWire 同位）。 */
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
): TenantRolesListSysRoles200Response {
  val resp = callRoles(store, tokens).tenantRolesListSysRoles(tenantId)
  requireOk(resp)
  return resp.body()
}

private suspend fun createCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    request: CreateSysRoleRequest,
): SysRole {
  val resp = callRoles(store, tokens).tenantRolesCreateSysRole(tenantId, request)
  requireOk(resp)
  return resp.body()
}

private suspend fun getCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    roleId: String,
): SysRole {
  val resp = callRoles(store, tokens).tenantRolesGetSysRole(tenantId, roleId)
  requireOk(resp)
  return resp.body()
}

private suspend fun updateCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    roleId: String,
    request: UpdateSysRoleRequest,
): SysRole {
  val resp = callRoles(store, tokens).tenantRolesUpdateSysRole(tenantId, roleId, request)
  requireOk(resp)
  return resp.body()
}

private suspend fun deleteCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    roleId: String,
) {
  val resp = callRoles(store, tokens).tenantRolesDeleteSysRole(tenantId, roleId)
  requireOk(resp)
  return resp.body()
}

private suspend fun listGrantsCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    roleId: String,
): RoleMenuGrant {
  val resp = callRoleMenus(store, tokens).tenantRoleMenusListSysRoleMenus(tenantId, roleId)
  requireOk(resp)
  return resp.body()
}

private suspend fun setGrantsCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    roleId: String,
    request: SetSysRoleMenusRequest,
): RoleMenuGrant {
  val resp = callRoleMenus(store, tokens).tenantRoleMenusSetSysRoleMenus(tenantId, roleId, request)
  requireOk(resp)
  return resp.body()
}

private suspend fun clearGrantsCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    roleId: String,
) {
  val resp = callRoleMenus(store, tokens).tenantRoleMenusClearSysRoleMenus(tenantId, roleId)
  requireOk(resp)
  return resp.body()
}

/** TenantRoles Api 构造（唯一摸 TenantRolesApi 全量面的入口；baseUrl 缺失显式抛）。 */
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

/** TenantRoleMenus Api 构造（授权三缝专用；baseUrl 缺失显式抛）。 */
private suspend fun callRoleMenus(
    store: SessionStore,
    tokens: TokenHolder,
): TenantRoleMenusApi {
  val baseUrl = store.baseUrl ?: throw AuthError.ClientNotConfigured()
  return TenantRoleMenusApi(
      baseUrl = ApiGlue.normalizeBaseURL(baseUrl),
      httpClientConfig = ApiGlue.bearerClientConfig(tokens),
  )
}

/** 非 2xx 手解（生成 client 不抛）→ mapHttpError（MembersWire 同位，private per-file）。 */
private suspend fun <T> requireOk(resp: HttpResponse<T>) {
  if (!resp.success) {
    val bodyText = runCatching { resp.response.bodyAsText() }.getOrNull()
    throw ApiGlue.mapHttpError(resp.status, bodyText)
  }
}
