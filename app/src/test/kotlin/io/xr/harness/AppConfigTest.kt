package io.xr.harness

import io.xr.harness.config.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppConfigTest {

  @Test
  fun `returns the configured base url`() {
    val config = AppConfig(mapOf("API_BASE_URL" to "http://localhost:5104"))
    assertEquals("http://localhost:5104", config.apiBaseUrl())
  }

  @Test
  fun `throws when API_BASE_URL is missing`() {
    assertThrows(IllegalStateException::class.java) { AppConfig(emptyMap()).apiBaseUrl() }
  }
}
