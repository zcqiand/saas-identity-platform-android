package io.xr.harness.app

import android.content.Context
import io.xr.harness.core.PreferencesStore

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
