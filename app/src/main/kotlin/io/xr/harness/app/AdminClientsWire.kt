package io.xr.harness.app

import io.ktor.client.statement.bodyAsText
import io.xr.harness.core.ApiGlue
import io.xr.harness.core.AuthError
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.ui.AdminClientsViewModel
import saas.identity.shared.api.AdminClientsApi
import saas.identity.shared.infrastructure.HttpResponse
import saas.identity.shared.models.AdminClientsListClients200Response
import saas.identity.shared.models.AdminClientsSetClientStatusRequest
import saas.identity.shared.models.CreateOAuthClientRequest
import saas.identity.shared.models.OAuthClient
import saas.identity.shared.models.UpdateOAuthClientRequest

/**
 * OAuth 客户端管理 6 缝（swift AdminClientsViewModel.Seams 同位，M04.F01/F02）：唯一摸 AdminClientsApi(全量) 的地方。
 * clientId 字符串寻址（非 UUID）；list 不传分页（live 实证 0-indexed，nil 全量）； 非 2xx 手解（生成 client 不抛）→ mapHttpError
 * 抛出；传输异常归一 Unreachable（guard 统一护栏，形状与 MenuWire 同构）。
 */
fun adminClientsSeams(
    store: SessionStore,
    tokens: TokenHolder,
): AdminClientsViewModel.Seams {
  return AdminClientsViewModel.Seams(
      listClients = { guard { listCall(store, tokens) } },
      getClient = { clientId -> guard { getClientCall(store, tokens, clientId) } },
      createClient = { request -> guard { createCall(store, tokens, request) } },
      updateClient = { clientId, request ->
        guard { updateCall(store, tokens, clientId, request) }
      },
      deleteClient = { clientId -> guard { deleteCall(store, tokens, clientId) } },
      setClientStatus = { clientId, status ->
        guard { setStatusCall(store, tokens, clientId, status) }
      },
  )
}

/** 缝护栏：AuthError 透传 + 任意传输层异常归一 Unreachable（ReceiptWire/MenuWire 同位）。 */
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
): AdminClientsListClients200Response {
  val resp = callAdminClients(store, tokens).adminClientsListClients()
  requireOk(resp)
  return resp.body()
}

private suspend fun getClientCall(
    store: SessionStore,
    tokens: TokenHolder,
    clientId: String,
): OAuthClient {
  val resp = callAdminClients(store, tokens).adminClientsGetClient(clientId)
  requireOk(resp)
  return resp.body()
}

private suspend fun createCall(
    store: SessionStore,
    tokens: TokenHolder,
    request: CreateOAuthClientRequest,
): OAuthClient {
  val resp = callAdminClients(store, tokens).adminClientsCreateClient(request)
  requireOk(resp)
  return resp.body()
}

private suspend fun updateCall(
    store: SessionStore,
    tokens: TokenHolder,
    clientId: String,
    request: UpdateOAuthClientRequest,
): OAuthClient {
  val resp = callAdminClients(store, tokens).adminClientsUpdateClient(clientId, request)
  requireOk(resp)
  return resp.body()
}

private suspend fun deleteCall(
    store: SessionStore,
    tokens: TokenHolder,
    clientId: String,
) {
  val resp = callAdminClients(store, tokens).adminClientsDeleteClient(clientId)
  requireOk(resp)
  return resp.body()
}

private suspend fun setStatusCall(
    store: SessionStore,
    tokens: TokenHolder,
    clientId: String,
    status: Int,
): OAuthClient {
  val resp =
      callAdminClients(store, tokens)
          .adminClientsSetClientStatus(
              clientId,
              AdminClientsSetClientStatusRequest(status = status),
          )
  requireOk(resp)
  return resp.body()
}

/** AdminClients Api 构造（唯一摸 AdminClientsApi 全量面的入口；baseUrl 缺失显式抛）。 */
private suspend fun callAdminClients(
    store: SessionStore,
    tokens: TokenHolder,
): AdminClientsApi {
  val baseUrl = store.baseUrl ?: throw AuthError.ClientNotConfigured()
  return AdminClientsApi(
      baseUrl = ApiGlue.normalizeBaseURL(baseUrl),
      httpClientConfig = ApiGlue.bearerClientConfig(tokens),
  )
}

/** 非 2xx 手解（生成 client 不抛）→ mapHttpError（MenuWire 同位，private per-file）。 */
private suspend fun <T> requireOk(resp: HttpResponse<T>) {
  if (!resp.success) {
    val bodyText = runCatching { resp.response.bodyAsText() }.getOrNull()
    throw ApiGlue.mapHttpError(resp.status, bodyText)
  }
}
