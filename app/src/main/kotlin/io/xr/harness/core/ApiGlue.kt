package io.xr.harness.core

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import saas.identity.shared.models.ErrorResponse

/**
 * 登录/请求失败的类型化错误——ViewModel 据此映射用户可读文案（swift AuthError 同构）。 生成 client 非 2xx 不抛（expectSuccess
 * 未开），HTTP 侧错误由 mapHttpError 归一到这里。
 */
sealed class AuthError(message: String) : Exception(message) {
  class ClientNotConfigured : AuthError("clientId 未配置，请先在配置页填写")

  class InvalidCredentials : AuthError("用户名或密码无效")

  class Locked : AuthError("尝试次数过多，账号已锁定，请稍后再试")

  class Server(message: String) : AuthError(message)

  class Unreachable(cause: Throwable) :
      AuthError("无法连接服务器：${cause.message ?: cause::class.simpleName}")
}

sealed interface LoginOutcome {
  data class Success(val username: String, val accessToken: String?, val tenantId: String?) :
      LoginOutcome

  data class Failure(val error: AuthError) : LoginOutcome
}

/** token 内存持有（swift TokenStoring 的 Android 对应位；持久化归后刀）。 */
class TokenHolder {
  @Volatile var accessToken: String? = null
}

/** 组合根（swift APIClient 同构）：normalize + 生成 ApiClient 组装，手写代码零手写 API 面（§4）。 */
object ApiGlue {
  val json = Json { ignoreUnknownKeys = true }

  private const val SCHEME_DELIMITER_LEN = 3

  /** swift normalizeBaseURL 同构：trim、去尾斜杠、scheme+host 必需，非法即抛。 */
  fun normalizeBaseURL(raw: String): String {
    val noTrail = raw.trim().trimEnd('/')
    val schemeEnd = noTrail.indexOf("://")
    require(schemeEnd > 0) { "baseURL 缺 scheme：$raw" }
    val rest = noTrail.substring(schemeEnd + SCHEME_DELIMITER_LEN)
    require(rest.isNotEmpty() && !rest.startsWith('/')) { "baseURL 缺 host：$raw" }
    return noTrail
  }

  /**
   * 生成 Api 类组装配置：token 经 ktor Auth bearer 插件注入——生成层 `authentications` 为 null（jvm-ktor client
   * 模式未注册鉴权方案），`setBearerToken` 是死路，实证 2026-10-19。 生成 Api 类（AuthApi 等）即 ApiClient 子类，构造传 baseUrl +
   * 本配置即可，无需外层 ApiClient。
   */
  fun bearerClientConfig(tokens: TokenHolder): (HttpClientConfig<*>) -> Unit = { config ->
    config.install(Auth) {
      bearer {
        loadTokens { tokens.accessToken?.let { BearerTokens(accessToken = it, refreshToken = "") } }
      }
    }
  }

  /** 非 2xx → AuthError：401/429 定型，其余解 ErrorResponse.message 透显，解不动报状态码。 */
  fun mapHttpError(status: Int, bodyText: String?): AuthError =
      when (status) {
        HttpStatusCode.Unauthorized.value -> AuthError.InvalidCredentials()
        HttpStatusCode.TooManyRequests.value -> AuthError.Locked()
        else -> {
          val message =
              bodyText
                  ?.let {
                    runCatching { json.decodeFromString<ErrorResponse>(it).message }.getOrNull()
                  }
                  ?.takeIf { it.isNotBlank() }
          AuthError.Server(message ?: "服务器错误（HTTP $status）")
        }
      }
}
