package io.xr.harness.core

/** token 加解密缝（swift Keychain 硬件安全位对位）：core 不摸 AndroidKeyStore，App 层供 Keystore 实现，测试用 fake。 */
interface TokenCipher {
  fun encrypt(plain: String): String

  fun decrypt(cipherText: String): String
}
