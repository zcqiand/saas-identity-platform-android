package io.xr.harness.app

import io.xr.harness.core.PreferencesStore
import io.xr.harness.core.TokenCipher
import io.xr.harness.core.TokenStoring

/**
 * TokenStoring 的加密实现（swift KeychainTokenStore 对位，M04 面刀 19）：token 经 TokenCipher 加密后落独立 prefs
 * （"session.tokens"，与配置/身份 prefs 隔离）。 解密失败 fail-closed 返 null 不崩——密钥轮换/清数据后旧密文（含升级前 明文存量）作废 =
 * 需重新登录；登出/重配由 core 层统一 save(null, null) 清密。 加解密实现归 KeystoreTokenCipher（设备侧， JVM 单测不碰——swift「单测走
 * fake 不碰 Keychain」同款姿态）。
 */
class EncryptedTokenStore(
    private val prefs: PreferencesStore,
    private val cipher: TokenCipher,
) : TokenStoring {

  override fun loadAccess(): String? = decryptStored(KEY_ACCESS)

  override fun loadRefresh(): String? = decryptStored(KEY_REFRESH)

  override fun save(accessToken: String?, refreshToken: String?) {
    storeEncrypted(KEY_ACCESS, accessToken)
    storeEncrypted(KEY_REFRESH, refreshToken)
  }

  private fun decryptStored(key: String): String? =
      prefs.getString(key)?.let { cipherText ->
        runCatching { cipher.decrypt(cipherText) }.getOrNull()
      }

  private fun storeEncrypted(key: String, value: String?) {
    if (value == null) {
      prefs.remove(key)
    } else {
      prefs.putString(key, cipher.encrypt(value))
    }
  }

  companion object {
    private const val KEY_ACCESS = "tokens.access"
    private const val KEY_REFRESH = "tokens.refresh"
  }
}
