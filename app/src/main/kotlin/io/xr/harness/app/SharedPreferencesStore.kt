package io.xr.harness.app

import android.content.Context
import io.xr.harness.core.PreferencesStore
import io.xr.harness.core.TokenStoring

/** PreferencesStore 的 App 层实现（swift UserDefaults 对应位）。core 层不摸 Android 框架。 */
class SharedPreferencesStore(context: Context) : PreferencesStore {
  private val sp = context.getSharedPreferences("session", Context.MODE_PRIVATE)

  override fun getString(key: String): String? = sp.getString(key, null)

  override fun putString(key: String, value: String) {
    sp.edit().putString(key, value).apply()
  }

  override fun remove(key: String) {
    sp.edit().remove(key).apply()
  }
}

/**
 * TokenStoring 的 App 层实现：独立 prefs 文件（"session.tokens"），与配置/身份 prefs 隔离。 明文落盘是本期已知限制—— Keystore
 * 级加密（EncryptedSharedPreferences 已废弃，替代方案待定）登记后刀。登出/重配由 core 层统一 save(null, null) 清密。
 */
class SharedPreferencesTokenStore(context: Context) : TokenStoring {
  private val sp = context.getSharedPreferences("session.tokens", Context.MODE_PRIVATE)

  override fun loadAccess(): String? = sp.getString(KEY_ACCESS, null)

  override fun loadRefresh(): String? = sp.getString(KEY_REFRESH, null)

  override fun save(accessToken: String?, refreshToken: String?) {
    sp.edit()
        .apply {
          if (accessToken == null) {
            remove(KEY_ACCESS)
          } else {
            putString(KEY_ACCESS, accessToken)
          }
          if (refreshToken == null) {
            remove(KEY_REFRESH)
          } else {
            putString(KEY_REFRESH, refreshToken)
          }
        }
        .apply()
  }

  companion object {
    private const val KEY_ACCESS = "tokens.access"
    private const val KEY_REFRESH = "tokens.refresh"
  }
}
