@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.app

import io.ktor.client.statement.bodyAsText
import io.xr.harness.core.ApiGlue
import io.xr.harness.core.AuthError
import io.xr.harness.core.LoginOutcome
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.core.WhoamiUser
import saas.identity.shared.api.AuthApi
import saas.identity.shared.api.MeApi
import saas.identity.shared.models.LoginRequest
import saas.identity.shared.models.LoginResponse

/**
 * 真 seams（swift App 层 APIGlue 同构）：唯一摸生成 Api 类的地方。 非 2xx 手解（生成 client 不抛，expectSuccess 未开）→
 * ApiGlue.mapHttpError； 传输层异常归一 AuthError.Unreachable；login 一律返回 outcome（零 throw，VM 据型映射文案）， 成功把
 * access/refresh 交 TokenHolder（内存）。
 */
fun loginSeam(
    store: SessionStore,
    tokens: TokenHolder,
): suspend (String, String, String) -> LoginOutcome {
  return { username, password, clientId ->
    val baseUrl = store.baseUrl
    if (baseUrl == null) {
      LoginOutcome.Failure(AuthError.ClientNotConfigured())
    } else {
      try {
        val authApi =
            AuthApi(
                baseUrl = ApiGlue.normalizeBaseURL(baseUrl),
                httpClientConfig = ApiGlue.bearerClientConfig(tokens),
            )
        val resp =
            authApi.sessionsLogin(
                LoginRequest(username = username, password = password, clientId = clientId)
            )
        if (!resp.success) {
          val bodyText = runCatching { resp.response.bodyAsText() }.getOrNull()
          LoginOutcome.Failure(ApiGlue.mapHttpError(resp.status, bodyText))
        } else {
          handleLoginBody(resp.body(), tokens)
        }
      } catch (e: AuthError) {
        LoginOutcome.Failure(e)
      } catch (e: Exception) {
        LoginOutcome.Failure(AuthError.Unreachable(e))
      }
    }
  }
}

/** 登录 200 体收编：access 缺失显式失败（§3 禁兜底空会话），成功侧 token 交内存持有。 */
private fun handleLoginBody(body: LoginResponse, tokens: TokenHolder): LoginOutcome {
  val access = body.accessToken
  if (access == null) {
    return LoginOutcome.Failure(AuthError.Server("登录响应缺 accessToken"))
  }
  tokens.accessToken = access
  tokens.refreshToken = body.refreshToken
  return LoginOutcome.Success(
      username = body.user.username,
      accessToken = access,
      refreshToken = body.refreshToken,
      tenantId = body.currentTenantId?.toString(),
  )
}

/**
 * whoami seam（restore 自愈用）：MeApi.meWhoami 校验 token，成功归一 WhoamiUser。 非 2xx（含 401 过期）或任何异常 → null， 由
 * VM 清密回登录。saas CurrentUser 无 username 字段——回退 store.username ?: email。
 */
fun whoamiSeam(
    store: SessionStore,
    tokens: TokenHolder,
): suspend () -> WhoamiUser? {
  return {
    val baseUrl = store.baseUrl
    if (baseUrl == null) {
      null
    } else {
      try {
        val meApi =
            MeApi(
                baseUrl = ApiGlue.normalizeBaseURL(baseUrl),
                httpClientConfig = ApiGlue.bearerClientConfig(tokens),
            )
        val resp = meApi.meWhoami()
        if (!resp.success) {
          null
        } else {
          val body = resp.body()
          WhoamiUser(
              username = store.username ?: body.email ?: "",
              displayName = body.email,
              tenantId = body.currentTenantId?.toString(),
          )
        }
      } catch (e: Exception) {
        null
      }
    }
  }
}

/**
 * logout seam：服务端会话吊销（尽力而为，失败不阻断本地清密——VM 侧先调本 seam 再 clearSession）。 本地清密归
 * SessionStore.clearSession，token 失效交给服务端过期。
 */
fun logoutSeam(
    store: SessionStore,
    tokens: TokenHolder,
): suspend () -> Unit {
  return {
    val baseUrl = store.baseUrl
    if (baseUrl != null) {
      try {
        AuthApi(
                baseUrl = ApiGlue.normalizeBaseURL(baseUrl),
                httpClientConfig = ApiGlue.bearerClientConfig(tokens),
            )
            .sessionsLogout()
      } catch (e: Exception) {
        // 吊销失败不阻断登出
      }
    }
  }
}
