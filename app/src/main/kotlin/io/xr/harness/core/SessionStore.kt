package io.xr.harness.core

/**
 * 会话三态机（swift SessionStore 同构）：needsSetup → needsLogin → ready。 baseURL/clientId 为非机密配置，经可注入
 * PreferencesStore 持久化（App 层落 SharedPreferences）； token 本切片只进内存（TokenHolder），Keychain 级持久化归后刀。
 */
interface PreferencesStore {
  fun getString(key: String): String?

  fun putString(key: String, value: String)

  fun remove(key: String)
}

sealed interface SessionState {
  data object NeedsSetup : SessionState

  data object NeedsLogin : SessionState

  data class Ready(val username: String, val tenantId: String?) : SessionState
}

class SessionStore(private val prefs: PreferencesStore) {

  val state: SessionState
    get() =
        when {
          !isConfigured -> SessionState.NeedsSetup
          username.isNullOrBlank() -> SessionState.NeedsLogin
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

  /** 保存配置 fail-fast（swift saveConfig 同构）：空值即抛，禁止兜底（suite-hard-rules §1）。 */
  fun saveConfig(baseUrl: String, clientId: String) {
    val url = baseUrl.trim()
    val cid = clientId.trim()
    check(url.isNotEmpty()) { "baseURL 不能为空" }
    check(cid.isNotEmpty()) { "clientId 不能为空" }
    prefs.putString(KEY_BASE_URL, url)
    prefs.putString(KEY_CLIENT_ID, cid)
    prefs.remove(KEY_USERNAME)
    prefs.remove(KEY_TENANT_ID)
  }

  fun clearConfig() {
    prefs.remove(KEY_BASE_URL)
    prefs.remove(KEY_CLIENT_ID)
    clearSession()
  }

  fun adoptLogin(username: String, tenantId: String?) {
    prefs.putString(KEY_USERNAME, username)
    if (tenantId != null) {
      prefs.putString(KEY_TENANT_ID, tenantId)
    } else {
      prefs.remove(KEY_TENANT_ID)
    }
  }

  fun clearSession() {
    prefs.remove(KEY_USERNAME)
    prefs.remove(KEY_TENANT_ID)
  }

  companion object {
    const val KEY_BASE_URL = "session.baseURL"
    const val KEY_CLIENT_ID = "session.clientId"
    const val KEY_USERNAME = "session.username"
    const val KEY_TENANT_ID = "session.tenantId"
  }
}
