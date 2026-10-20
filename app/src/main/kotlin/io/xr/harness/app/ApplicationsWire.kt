package io.xr.harness.app

import io.ktor.client.statement.bodyAsText
import io.xr.harness.core.ApiGlue
import io.xr.harness.core.AuthError
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.ui.TenantApplicationsViewModel
import saas.identity.shared.api.TenantApplicationsApi
import saas.identity.shared.infrastructure.HttpResponse
import saas.identity.shared.models.SubscribeTenantApplicationRequest
import saas.identity.shared.models.TenantApplication
import saas.identity.shared.models.TenantApplicationsListTenantApplications200Response
import saas.identity.shared.models.UpdateTenantApplicationRequest

/**
 * 租户应用订阅 4 缝（swift TenantApplicationsViewModel.Seams 同位，M00.F05）：唯一摸 TenantApplicationsApi 的地方。
 * 租户/clientId 字符串寻址；list 不传分页（nil 全量）； 非 2xx 手解（生成 client 不抛）→ mapHttpError 抛出； 传输异常归一
 * Unreachable（guard 统一护栏，RolesWire 同构）。
 */
fun tenantApplicationsSeams(
    store: SessionStore,
    tokens: TokenHolder,
): TenantApplicationsViewModel.Seams {
  return TenantApplicationsViewModel.Seams(
      list = { tenantId -> guard { listCall(store, tokens, tenantId) } },
      subscribe = { tenantId, request ->
        guard { subscribeCall(store, tokens, tenantId, request) }
      },
      update = { tenantId, clientId, request ->
        guard { updateCall(store, tokens, tenantId, clientId, request) }
      },
      remove = { tenantId, clientId -> guard { removeCall(store, tokens, tenantId, clientId) } },
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
    tenantId: String,
): TenantApplicationsListTenantApplications200Response {
  val resp = callApplications(store, tokens).tenantApplicationsListTenantApplications(tenantId)
  requireOk(resp)
  return resp.body()
}

private suspend fun subscribeCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    request: SubscribeTenantApplicationRequest,
): TenantApplication {
  val resp =
      callApplications(store, tokens)
          .tenantApplicationsSubscribeTenantApplication(tenantId, request)
  requireOk(resp)
  return resp.body()
}

private suspend fun updateCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    clientId: String,
    request: UpdateTenantApplicationRequest,
): TenantApplication {
  val resp =
      callApplications(store, tokens)
          .tenantApplicationsUpdateTenantApplication(tenantId, clientId, request)
  requireOk(resp)
  return resp.body()
}

private suspend fun removeCall(
    store: SessionStore,
    tokens: TokenHolder,
    tenantId: String,
    clientId: String,
) {
  val resp =
      callApplications(store, tokens).tenantApplicationsRemoveTenantApplication(tenantId, clientId)
  requireOk(resp)
}

/** TenantApplications Api 构造（唯一摸 TenantApplicationsApi 全量面的入口；baseUrl 缺失显式抛）。 */
private suspend fun callApplications(
    store: SessionStore,
    tokens: TokenHolder,
): TenantApplicationsApi {
  val baseUrl = store.baseUrl ?: throw AuthError.ClientNotConfigured()
  return TenantApplicationsApi(
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
