@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.ui

import io.xr.harness.core.AuthError
import java.time.OffsetDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import saas.identity.shared.models.OAuthClient

/** ClientsViewModel fake-seam JVM 单测（swift AdminClientsViewModel 只读子集）：picker 供数面。 */
class ClientsViewModelTest {

  private fun client(clientId: String): OAuthClient =
      OAuthClient(
          id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
          clientId = clientId,
          clientName = "名-$clientId",
          grantTypes = "authorization_code",
          redirectUris = "https://a/cb",
          accessTokenValidity = 3600,
          refreshTokenValidity = 86400,
          autoApprove = false,
          status = 1,
          createdAt = OffsetDateTime.parse("2026-10-19T00:00:00Z"),
          updatedAt = OffsetDateTime.parse("2026-10-19T00:00:00Z"),
      )

  @Test
  fun `load populates clients from page items`() = runBlocking {
    val vm =
        ClientsViewModel(ClientsViewModel.Seams(listClients = { listOf(client("a"), client("b")) }))

    val ok = vm.load()

    assertTrue(ok)
    assertEquals(listOf("a", "b"), vm.clients.value.map { it.clientId })
    assertEquals(ClientsViewModel.Phase.Idle, vm.phase.value)
  }

  @Test
  fun `load failure reports phase`() = runBlocking {
    val vm =
        ClientsViewModel(
            ClientsViewModel.Seams(listClients = { throw AuthError.Server("服务器错误（HTTP 500）") }),
        )

    val ok = vm.load()

    assertFalse(ok)
    assertEquals("服务器错误（HTTP 500）", (vm.phase.value as ClientsViewModel.Phase.Failed).message)
  }
}
