@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.app

import io.ktor.client.statement.bodyAsText
import io.xr.harness.core.ApiGlue
import io.xr.harness.core.AuthError
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.ui.OAuthViewModel
import saas.identity.shared.api.OauthApi
import saas.identity.shared.infrastructure.HttpResponse
import saas.identity.shared.models.AuthorizeCodeRequest
import saas.identity.shared.models.OAuthAuthorize200Response
import saas.identity.shared.models.TokenRequest
import saas.identity.shared.models.TokenResponse

/**
 * OAuth 3 缝（swift OAuthViewModel.Seams 同位，M04.F03）：唯一摸 OauthApi 的地方。 非 2xx 手解（生成 client 不抛）→
 * mapHttpError 抛出；传输异常归一 Unreachable（guard 统一护栏，RolesWire 同构）。 换码/刷新成功后同步内存 TokenHolder（LoginWire
 * 先例：持久化位 store.adoptOAuthToken 归 VM，即时生效位归 wire），后续 bearer 调用即刻用新 token。 公共 client 免 clientSecret。
 */
fun oauthSeams(
    store: SessionStore,
    tokens: TokenHolder,
): OAuthViewModel.Seams {
  return OAuthViewModel.Seams(
      authorize = { clientId, redirectUri, scope, state ->
        guard {
          authorizeCall(
              store,
              tokens,
              AuthorizeCodeRequest(
                  clientId = clientId,
                  redirectUri = redirectUri,
                  responseType = AuthorizeCodeRequest.ResponseType.code,
                  state = state,
                  scope = scope,
              ),
          )
        }
      },
      exchangeCode = { code, clientId, redirectUri ->
        guard {
          val token =
              exchangeCall(
                  store,
                  tokens,
                  TokenRequest(
                      grantType = TokenRequest.GrantType.authorization_code,
                      clientId = clientId,
                      code = code,
                      redirectUri = redirectUri,
                  ),
              )
          tokens.accessToken = token.accessToken
          tokens.refreshToken = token.refreshToken
          token
        }
      },
      refreshToken = { currentRefreshToken, clientId ->
        guard {
          val token =
              refreshCall(
                  store,
                  tokens,
                  TokenRequest(
                      grantType = TokenRequest.GrantType.refresh_token,
                      clientId = clientId,
                      refreshToken = currentRefreshToken,
                  ),
              )
          tokens.accessToken = token.accessToken
          tokens.refreshToken = token.refreshToken
          token
        }
      },
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

private suspend fun authorizeCall(
    store: SessionStore,
    tokens: TokenHolder,
    request: AuthorizeCodeRequest,
): OAuthAuthorize200Response {
  val resp = callOauth(store, tokens).oAuthAuthorize(request)
  requireOk(resp)
  return resp.body()
}

private suspend fun exchangeCall(
    store: SessionStore,
    tokens: TokenHolder,
    request: TokenRequest,
): TokenResponse {
  val resp = callOauth(store, tokens).oAuthToken(request)
  requireOk(resp)
  return resp.body()
}

private suspend fun refreshCall(
    store: SessionStore,
    tokens: TokenHolder,
    request: TokenRequest,
): TokenResponse {
  val resp = callOauth(store, tokens).oAuthToken(request)
  requireOk(resp)
  return resp.body()
}

/** OauthApi 构造（唯一摸 OauthApi 全量面的入口；baseUrl 缺失显式抛）。 */
private suspend fun callOauth(
    store: SessionStore,
    tokens: TokenHolder,
): OauthApi {
  val baseUrl = store.baseUrl ?: throw AuthError.ClientNotConfigured()
  return OauthApi(
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
