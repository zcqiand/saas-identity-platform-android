package io.xr.harness.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.xr.harness.core.TokenCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * TokenCipher 的 AndroidKeyStore 实现（swift Keychain 硬件安全位对位）：AES-256-GCM 密钥生在 AndroidKeyStore
 * （不可导出），密文 = Base64(iv || GCM 密文)。 设备侧实现，JVM 单测不碰（swift KeychainTokenStore「单测走 fake 不碰
 * Keychain」同款姿态）；EncryptedTokenStore 的行为由其单测钉死。
 */
class KeystoreTokenCipher : TokenCipher {

  override fun encrypt(plain: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    val iv = cipher.iv
    val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    val payload = iv + encrypted
    return Base64.encodeToString(payload, Base64.NO_WRAP)
  }

  override fun decrypt(cipherText: String): String {
    val payload = Base64.decode(cipherText, Base64.NO_WRAP)
    val iv = payload.copyOfRange(0, IV_LEN)
    val encrypted = payload.copyOfRange(IV_LEN, payload.size)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
    return String(cipher.doFinal(encrypted), Charsets.UTF_8)
  }

  /** 密钥常驻 Keystore（存在即复用，无钥即生成；不可导出，清应用数据即销毁）。 */
  private fun getOrCreateKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
      return it
    }
    val generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
          init(
              KeyGenParameterSpec.Builder(
                      KEY_ALIAS,
                      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                  )
                  .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                  .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                  .setKeySize(KEY_SIZE_BITS)
                  .build()
          )
        }
    return generator.generateKey()
  }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "session.tokens.key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BITS = 256
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
  }
}
