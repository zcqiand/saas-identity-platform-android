package io.xr.harness.core

/**
 * 会话三态机（swift SessionStore 同构）：needsSetup → needsLogin → ready。 baseURL/clientId 为非机密配置，经可注入
 * PreferencesStore 持久化（App 层落 SharedPreferences）； token 经可注入 TokenStoring 持久化（App 层实现， Keystore
 * 级加密归后刀），登出/重配即清密。
 */
interface PreferencesStore {
  fun getString(key: String): String?

  fun putString(key: String, value: String)

  fun remove(key: String)
}

/** token 存取缝（swift TokenStoring 同构）：core 不摸存储实现，测试用内存 fake。 */
interface TokenStoring {
  fun loadAccess(): String?

  fun loadRefresh(): String?

  fun save(accessToken: String?, refreshToken: String?)
}

sealed interface SessionState {
  data object NeedsSetup : SessionState

  data object NeedsLogin : SessionState

  data class Ready(val username: String, val tenantId: String?) : SessionState
}

class SessionStore(private val prefs: PreferencesStore, private val tokens: TokenStoring) {

  val state: SessionState
    get() =
        when {
          !isConfigured -> SessionState.NeedsSetup
          username.isNullOrBlank() || !hasToken -> SessionState.NeedsLogin
          else -> SessionState.Ready(username = username!!, tenantId = tenantId)
        }

  val isConfigured: Boolean
    get() = !baseUrl.isNullOrBlank() && !clientId.isNullOrBlank()

  val baseUrl: String?
    get() = prefs.getString(KEY_BASE_URL)

  val clientId: String?
    get() = prefs.getString(KEY_CLIENT_ID)

  val username: String?
    get() = prefs.getString(KEY_USERNAME)

  val tenantId: String?
    get() = prefs.getString(KEY_TENANT_ID)

  val hasToken: Boolean
    get() = tokens.loadAccess() != null

  /** 保存配置 fail-fast（swift saveConfig 同构）：空值即抛，禁止兜底（suite-hard-rules §1）。 */
  fun saveConfig(baseUrl: String, clientId: String) {
    val url = baseUrl.trim()
    val cid = clientId.trim()
    check(url.isNotEmpty()) { "baseURL 不能为空" }
    check(cid.isNotEmpty()) { "clientId 不能为空" }
    prefs.putString(KEY_BASE_URL, url)
    prefs.putString(KEY_CLIENT_ID, cid)
    clearSession()
  }

  fun clearConfig() {
    prefs.remove(KEY_BASE_URL)
    prefs.remove(KEY_CLIENT_ID)
    clearSession()
  }

  /** 收编登录结果：会话身份进 prefs，token 进 TokenStoring（access 必非空——seam 已 fail-fast）。 */
  fun adoptLogin(username: String, tenantId: String?, accessToken: String, refreshToken: String?) {
    prefs.putString(KEY_USERNAME, username)
    if (tenantId != null) {
      prefs.putString(KEY_TENANT_ID, tenantId)
    } else {
      prefs.remove(KEY_TENANT_ID)
    }
    tokens.save(accessToken, refreshToken)
  }

  fun clearSession() {
    prefs.remove(KEY_USERNAME)
    prefs.remove(KEY_TENANT_ID)
    tokens.save(null, null)
  }

  companion object {
    const val KEY_BASE_URL = "session.baseURL"
    const val KEY_CLIENT_ID = "session.clientId"
    const val KEY_USERNAME = "session.username"
    const val KEY_TENANT_ID = "session.tenantId"
  }
}
