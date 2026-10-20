package io.xr.harness.app

import android.content.Context
import io.xr.harness.core.PreferencesStore

/** PreferencesStore 的 App 层实现（swift UserDefaults 对应位）。core 层不摸 Android 框架。 */
class SharedPreferencesStore(context: Context, fileName: String = "session") : PreferencesStore {
  private val sp = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

  override fun getString(key: String): String? = sp.getString(key, null)

  override fun putString(key: String, value: String) {
    sp.edit().putString(key, value).apply()
  }

  override fun remove(key: String) {
    sp.edit().remove(key).apply()
  }
}

/**
 * TokenStoring 的明文实现已由 EncryptedTokenStore 替代移除（刀 19，swift KeychainTokenStore 对位）： 明文落盘是已知
 * 限制——Keystore 级加密（EncryptedSharedPreferences 已废弃）以 TokenCipher 缝 + AndroidKeyStore AES-GCM 落地。
 */
