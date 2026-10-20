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
import saas.identity.shared.models.AdminClientsListClients200Response
import saas.identity.shared.models.CreateOAuthClientRequest
import saas.identity.shared.models.OAuthClient
import saas.identity.shared.models.UpdateOAuthClientRequest

/**
 * AdminClientsViewModel fake-seam JVM 单测（swift AdminClientsViewModelTests
 * 同构，M04.F01/F02，REQ-2026-005/006）： list 不传分页（live 实证 0-indexed，nil 全量）；create/update/setStatus
 * 成功原位维护列表不重拉； 失败只红字，列表不动可重试（AC-5）。delete 是危险操作，确认流在 UI 层，本层只执行。
 */
class AdminClientsViewModelTest {

  private val erpId = Uuid.parse("11111111-1111-1111-1111-111111111112")
  private val crmId = Uuid.parse("11111111-1111-1111-1111-111111111113")

  private fun client(
      id: Uuid,
      clientId: String,
      name: String,
      status: Int = 1,
  ): OAuthClient =
      OAuthClient(
          id = id,
          clientId = clientId,
          clientName = name,
          grantTypes = "authorization_code,refresh_token",
          redirectUris = "https://app.example.invalid/login",
          accessTokenValidity = 3600,
          refreshTokenValidity = 604800,
          autoApprove = false,
          status = status,
          createdAt = OffsetDateTime.parse("2026-10-19T00:00:00Z").toString(),
          updatedAt = OffsetDateTime.parse("2026-10-19T00:00:00Z").toString(),
      )

  private val seedList =
      AdminClientsListClients200Response(
          items =
              listOf(
                  client(erpId, "erp", "企业资源计划系统"),
                  client(crmId, "crm", "客户关系管理系统"),
              ),
          page = 1,
          pageSize = 20,
          total = 2,
      )

  private fun unusedClientSeam(): suspend (String) -> OAuthClient = { _ ->
    throw IllegalStateException("seam 未使用")
  }

  @Test
  fun `load populates clients without paging`() = runBlocking {
    var listCalled = false
    val vm =
        AdminClientsViewModel(
            AdminClientsViewModel.Seams(
                listClients = {
                  listCalled = true
                  seedList
                },
                getClient = unusedClientSeam(),
            )
        )

    val ok = vm.load()

    assertTrue(ok, "nil 全量拉取（Q1：分页 0-indexed，传 nil）")
    assertTrue(listCalled)
    assertEquals(listOf("erp", "crm"), vm.clients.value.map { it.clientId }, "列表全量渲染（AC-1）")
    assertEquals(AdminClientsViewModel.Phase.Idle, vm.phase.value)
  }

  @Test
  fun `load failure keeps empty and retryable`() = runBlocking {
    var reject = true
    val vm =
        AdminClientsViewModel(
            AdminClientsViewModel.Seams(
                listClients = {
                  if (reject) throw AuthError.Server("请求失败（HTTP 403）")
                  seedList
                },
                getClient = unusedClientSeam(),
            )
        )

    val ok = vm.load()

    assertFalse(ok, "403 = 失败（AC-5）")
    assertEquals(
        "请求失败（HTTP 403）",
        (vm.phase.value as AdminClientsViewModel.Phase.Failed).message,
        "红字带 HTTP 码",
    )
    assertTrue(vm.clients.value.isEmpty(), "失败不发假数据")

    reject = false
    val retry = vm.load()
    assertTrue(retry, "同一 VM 可直接重试（AC-5 可重试）")
    assertEquals(2, vm.clients.value.size)
  }

  @Test
  fun `create appends row in place without reload`() = runBlocking {
    var listCalls = 0
    var received: CreateOAuthClientRequest? = null
    val created = client(Uuid.random(), "new-app", "新应用")
    val vm =
        AdminClientsViewModel(
            AdminClientsViewModel.Seams(
                listClients = {
                  listCalls += 1
                  seedList
                },
                getClient = unusedClientSeam(),
                createClient = { request ->
                  received = request
                  created
                },
            )
        )
    vm.load()
    val request =
        CreateOAuthClientRequest(
            clientId = "new-app",
            clientName = "新应用",
            clientSecret = "s3cret",
            grantTypes = "authorization_code",
            redirectUris = "https://new.example.invalid/login",
            scopes = "openid",
        )

    val ok = vm.create(request)

    assertTrue(ok)
    assertEquals("new-app", received?.clientId, "create 缝收到完整请求（AC-4）")
    assertEquals("s3cret", received?.clientSecret, "secret 必填录入（Q2）")
    assertEquals(3, vm.clients.value.size, "create 成功后 count +1（AC-4）")
    assertEquals("new-app", vm.clients.value.last().clientId, "create 成功追加行（AC-4）")
    assertEquals(1, listCalls, "不重拉列表（原位追加）")
  }

  @Test
  fun `create failure keeps list and retryable`() = runBlocking {
    var reject = true
    val vm =
        AdminClientsViewModel(
            AdminClientsViewModel.Seams(
                listClients = { seedList },
                getClient = unusedClientSeam(),
                createClient = {
                  if (reject) throw AuthError.Server("请求失败（HTTP 400）")
                  client(Uuid.random(), "new-app", "新应用")
                },
            )
        )
    vm.load()
    val request =
        CreateOAuthClientRequest(
            clientId = "new-app",
            clientName = "新应用",
            clientSecret = "s3cret",
            grantTypes = "authorization_code",
            redirectUris = "https://new.example.invalid/login",
        )

    val ok = vm.create(request)

    assertFalse(ok)
    assertEquals(
        "请求失败（HTTP 400）",
        (vm.phase.value as AdminClientsViewModel.Phase.Failed).message,
        "红字带 HTTP 码（AC-5）",
    )
    assertEquals(2, vm.clients.value.size, "失败列表不动（AC-5）")

    reject = false
    val retry = vm.create(request)
    assertTrue(retry, "同一 VM 可直接重试（AC-5）")
    assertEquals("new-app", vm.clients.value.last().clientId)
  }

  @Test
  fun `update replaces row in place`() = runBlocking {
    var received: Pair<String, UpdateOAuthClientRequest>? = null
    val renamed = client(erpId, "erp", "ERP（改名后）")
    val vm =
        AdminClientsViewModel(
            AdminClientsViewModel.Seams(
                listClients = { seedList },
                getClient = unusedClientSeam(),
                updateClient = { clientId, request ->
                  received = clientId to request
                  renamed
                },
            )
        )
    vm.load()

    val ok = vm.update("erp", UpdateOAuthClientRequest(clientName = "ERP（改名后）"))

    assertTrue(ok)
    assertEquals("erp", received?.first)
    assertEquals("ERP（改名后）", received?.second?.clientName)
    assertEquals(
        "ERP（改名后）",
        vm.clients.value.first { it.clientId == "erp" }.clientName,
        "update 成功原位替换行（AC-3）",
    )
    assertEquals(
        "客户关系管理系统",
        vm.clients.value.first { it.clientId == "crm" }.clientName,
        "其他行不动",
    )
    assertEquals(2, vm.clients.value.size, "列表长度不变（原位替换非重拉）")
  }

  @Test
  fun `update failure keeps row and retryable`() = runBlocking {
    var reject = true
    val vm =
        AdminClientsViewModel(
            AdminClientsViewModel.Seams(
                listClients = { seedList },
                getClient = unusedClientSeam(),
                updateClient = { _, _ ->
                  if (reject) throw AuthError.Server("请求失败（HTTP 500）")
                  client(erpId, "erp", "ERP（改名后）")
                },
            )
        )
    vm.load()

    val ok = vm.update("erp", UpdateOAuthClientRequest(clientName = "ERP（改名后）"))

    assertFalse(ok)
    assertEquals(
        "请求失败（HTTP 500）",
        (vm.phase.value as AdminClientsViewModel.Phase.Failed).message,
        "红字带 HTTP 码（AC-5）",
    )
    assertEquals(
        "企业资源计划系统",
        vm.clients.value.first { it.clientId == "erp" }.clientName,
        "失败行不动（AC-5）",
    )

    reject = false
    val retry = vm.update("erp", UpdateOAuthClientRequest(clientName = "ERP（改名后）"))
    assertTrue(retry)
    assertEquals("ERP（改名后）", vm.clients.value.first { it.clientId == "erp" }.clientName)
  }

  @Test
  fun `delete removes row in place`() = runBlocking {
    var deleted: String? = null
    val vm =
        AdminClientsViewModel(
            AdminClientsViewModel.Seams(
                listClients = { seedList },
                getClient = unusedClientSeam(),
                deleteClient = { deleted = it },
            )
        )
    vm.load()

    val ok = vm.delete("erp")

    assertTrue(ok)
    assertEquals("erp", deleted, "delete 缝收到 clientId（AC-4）")
    assertEquals(listOf("crm"), vm.clients.value.map { it.clientId }, "删除后原位移除（AC-4）")
    assertEquals(1, vm.clients.value.size, "其他行完整保留")
  }

  @Test
  fun `delete failure keeps row and retryable`() = runBlocking {
    var reject = true
    val vm =
        AdminClientsViewModel(
            AdminClientsViewModel.Seams(
                listClients = { seedList },
                getClient = unusedClientSeam(),
                deleteClient = { if (reject) throw AuthError.Server("请求失败（HTTP 409）") },
            )
        )
    vm.load()

    val ok = vm.delete("erp")

    assertFalse(ok)
    assertEquals(
        "请求失败（HTTP 409）",
        (vm.phase.value as AdminClientsViewModel.Phase.Failed).message,
        "红字带 HTTP 码（AC-5）",
    )
    assertEquals(listOf("erp", "crm"), vm.clients.value.map { it.clientId }, "失败行不动（AC-5）")

    reject = false
    val retry = vm.delete("erp")
    assertTrue(retry, "同一 VM 可直接重试（AC-5）")
    assertEquals(listOf("crm"), vm.clients.value.map { it.clientId })
  }

  @Test
  fun `setStatus disable replaces row in place without reload`() = runBlocking {
    var listCalls = 0
    var received: Pair<String, Int>? = null
    val disabled = client(erpId, "erp", "企业资源计划系统", status = 0)
    val vm =
        AdminClientsViewModel(
            AdminClientsViewModel.Seams(
                listClients = {
                  listCalls += 1
                  seedList
                },
                getClient = unusedClientSeam(),
                setClientStatus = { clientId, status ->
                  received = clientId to status
                  disabled
                },
            )
        )
    vm.load()

    val ok = vm.setStatus("erp", 0)

    assertTrue(ok, "停用成功（AC-1）")
    assertEquals("erp", received?.first, "status 缝寻址 clientId 字符串（Q1：非 UUID）")
    assertEquals(0, received?.second, "停用传 status=0")
    assertEquals(
        0,
        vm.clients.value.first { it.clientId == "erp" }.status,
        "成功原位替换行（AC-3）",
    )
    assertEquals(2, vm.clients.value.size, "列表长度不变（原位替换非重拉）")
    assertEquals(1, listCalls, "不重拉列表（AC-3）")
  }

  @Test
  fun `setStatus enable passes one and replaces row`() = runBlocking {
    var receivedStatus: Int? = null
    val enabled = client(erpId, "erp", "企业资源计划系统", status = 1)
    val vm =
        AdminClientsViewModel(
            AdminClientsViewModel.Seams(
                listClients = { seedList },
                getClient = unusedClientSeam(),
                setClientStatus = { _, status ->
                  receivedStatus = status
                  enabled
                },
            )
        )
    vm.load()

    val ok = vm.setStatus("erp", 1)

    assertTrue(ok, "启用成功（AC-1：启用直通）")
    assertEquals(1, receivedStatus, "启用传 status=1")
    assertEquals(1, vm.clients.value.first { it.clientId == "erp" }.status, "行随返回体刷新")
  }

  @Test
  fun `setStatus failure keeps row and retryable`() = runBlocking {
    var reject = true
    val vm =
        AdminClientsViewModel(
            AdminClientsViewModel.Seams(
                listClients = { seedList },
                getClient = unusedClientSeam(),
                setClientStatus = { _, _ ->
                  if (reject) throw AuthError.Server("请求失败（HTTP 404）")
                  client(erpId, "erp", "企业资源计划系统", status = 0)
                },
            )
        )
    vm.load()

    val ok = vm.setStatus("erp", 0)

    assertFalse(ok, "404 = 失败（AC-4）")
    assertEquals(
        "请求失败（HTTP 404）",
        (vm.phase.value as AdminClientsViewModel.Phase.Failed).message,
        "红字带 HTTP 码（AC-4）",
    )
    assertEquals(1, vm.clients.value.first { it.clientId == "erp" }.status, "失败行不动（AC-4）")

    reject = false
    val retry = vm.setStatus("erp", 0)
    assertTrue(retry, "同一 VM 可直接重试（AC-4）")
    assertEquals(0, vm.clients.value.first { it.clientId == "erp" }.status)
  }
}
