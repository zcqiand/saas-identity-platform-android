@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.app

import io.ktor.client.statement.bodyAsText
import io.xr.harness.core.ApiGlue
import io.xr.harness.core.AuthError
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.ui.AdminTenantsViewModel
import kotlin.uuid.Uuid
import saas.identity.shared.api.AdminTenantsApi
import saas.identity.shared.infrastructure.HttpResponse
import saas.identity.shared.models.AdminTenantsListTenants200Response
import saas.identity.shared.models.CreateTenantRequest
import saas.identity.shared.models.Tenant
import saas.identity.shared.models.UpdateTenantRequest

/**
 * 平台 admin 租户 4 缝（swift AdminTenantsViewModel.Seams 同位，M00.F01）：唯一摸 AdminTenantsApi 的地方。 list 不传分页
 * （null 全量，0-indexed 同族口径）； UUID id 寻址（Q1），缝收 Uuid、生成 API 面收 String 在此转换； 非 2xx 手解（生成 client 不抛）→
 * mapHttpError 抛出；传输异常归一 Unreachable（guard 统一护栏，RolesWire 同构）。
 */
fun adminTenantsSeams(
    store: SessionStore,
    tokens: TokenHolder,
): AdminTenantsViewModel.Seams {
  return AdminTenantsViewModel.Seams(
      list = { guard { listCall(store, tokens) } },
      create = { request -> guard { createCall(store, tokens, request) } },
      update = { id, request -> guard { updateCall(store, tokens, id, request) } },
      delete = { id -> guard { deleteCall(store, tokens, id) } },
  )
}

/** 缝护栏：AuthError 透传 + 任意传输层异常归一 Unreachable（RolesWire 同位）。 */
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
): AdminTenantsListTenants200Response {
  val resp = callTenants(store, tokens).adminTenantsListTenants()
  requireOk(resp)
  return resp.body()
}

private suspend fun createCall(
    store: SessionStore,
    tokens: TokenHolder,
    request: CreateTenantRequest,
): Tenant {
  val resp = callTenants(store, tokens).adminTenantsCreateTenant(request)
  requireOk(resp)
  return resp.body()
}

private suspend fun updateCall(
    store: SessionStore,
    tokens: TokenHolder,
    id: Uuid,
    request: UpdateTenantRequest,
): Tenant {
  val resp = callTenants(store, tokens).adminTenantsUpdateTenant(id.toString(), request)
  requireOk(resp)
  return resp.body()
}

private suspend fun deleteCall(
    store: SessionStore,
    tokens: TokenHolder,
    id: Uuid,
) {
  val resp = callTenants(store, tokens).adminTenantsDeleteTenant(id.toString())
  requireOk(resp)
}

/** AdminTenantsApi 构造（唯一摸 AdminTenantsApi 全量面的入口；baseUrl 缺失显式抛）。 */
private suspend fun callTenants(
    store: SessionStore,
    tokens: TokenHolder,
): AdminTenantsApi {
  val baseUrl = store.baseUrl ?: throw AuthError.ClientNotConfigured()
  return AdminTenantsApi(
      baseUrl = ApiGlue.normalizeBaseURL(baseUrl),
      httpClientConfig = ApiGlue.bearerClientConfig(tokens),
  )
}

/** 非 2xx 手解（生成 client 不抛）→ mapHttpError（RolesWire 同位，private per-file）。 */
private suspend fun <T> requireOk(resp: HttpResponse<T>) {
  if (!resp.success) {
    val bodyText = runCatching { resp.response.bodyAsText() }.getOrNull()
    throw ApiGlue.mapHttpError(resp.status, bodyText)
  }
}
