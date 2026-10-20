@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.ui

import io.xr.harness.core.AuthError
import java.time.OffsetDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import saas.identity.shared.models.SubscribeTenantApplicationRequest
import saas.identity.shared.models.TenantApplication
import saas.identity.shared.models.TenantApplicationsListTenantApplications200Response
import saas.identity.shared.models.UpdateTenantApplicationRequest

/**
 * TenantApplicationsViewModel fake-seam JVM 单测（swift TenantApplicationsViewModelTests 同构，M00.F05，
 * REQ-2026-012）： 租户上下文 nil = fail-fast 缝都不许被碰； 寻址契约 PATCH/DELETE 用 clientId 字符串列非 UUID id； 订阅
 * expireTime 可空原样上送不兜底； 失败只红字列表不动可重试。
 */
class TenantApplicationsViewModelTest {

  private val tenantID = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
  private val base = OffsetDateTime.parse("2023-11-14T22:13:20Z")
  private val expire = OffsetDateTime.parse("2027-02-26T00:00:00Z")

  private fun makeApp(
      clientId: String,
      status: Int = 1,
      expireTime: OffsetDateTime? = null,
  ): TenantApplication =
      TenantApplication(
          id = Uuid.parse("c0000000-0000-0000-0000-000000000003"),
          tenantId = tenantID,
          clientId = clientId,
          status = status,
          createdAt = base,
          expireTime = expireTime,
      )

  private val appList =
      TenantApplicationsListTenantApplications200Response(
          items =
              listOf(
                  makeApp("lab-management", expireTime = expire),
                  makeApp("lab-vue", status = 0),
              ),
          page = 1,
          pageSize = 20,
          total = 2,
      )

  @Test
  fun `load populates applications`() = runBlocking {
    var listed: String? = null
    val vm =
        TenantApplicationsViewModel(
            tenantId = {
              listed = tenantID.toString()
              tenantID.toString()
            },
            seams = TenantApplicationsViewModel.Seams(list = { _ -> appList }),
        )

    val ok = vm.load()

    assertTrue(ok)
    assertEquals(tenantID.toString(), listed)
    assertEquals(2, vm.applications.value.size)
    assertEquals("lab-management", vm.applications.value.first().clientId)
    assertEquals(TenantApplicationsViewModel.Phase.Idle, vm.phase.value)
  }

  @Test
  fun `subscribe appends row and carries expire time`() = runBlocking {
    var requested: SubscribeTenantApplicationRequest? = null
    val vm =
        TenantApplicationsViewModel(
            tenantId = { tenantID.toString() },
            seams =
                TenantApplicationsViewModel.Seams(
                    list = { _ -> appList },
                    subscribe = { _, request ->
                      requested = request
                      makeApp("lab-react", expireTime = expire)
                    },
                ),
        )
    vm.load()

    val ok = vm.subscribe(clientId = "lab-react", expireTime = expire)

    assertTrue(ok)
    assertEquals("lab-react", requested?.clientId)
    assertEquals(expire, requested?.expireTime, "订阅带的到期日必须原样上送（后端曾丢弃）")
    assertEquals(3, vm.applications.value.size)
    assertEquals("lab-react", vm.applications.value.last().clientId)
  }

  @Test
  fun `subscribe without expire time sends null`() = runBlocking {
    var requested: SubscribeTenantApplicationRequest? = null
    val vm =
        TenantApplicationsViewModel(
            tenantId = { tenantID.toString() },
            seams =
                TenantApplicationsViewModel.Seams(
                    subscribe = { _, request ->
                      requested = request
                      makeApp("lab-react")
                    },
                ),
        )

    val ok = vm.subscribe(clientId = "lab-react", expireTime = null)

    assertTrue(ok)
    assertNull(requested?.expireTime, "expireTime 契约可空，null 必须原样上送不兜底")
  }

  @Test
  fun `update replaces row in place`() = runBlocking {
    var requested: Pair<String, UpdateTenantApplicationRequest>? = null
    val vm =
        TenantApplicationsViewModel(
            tenantId = { tenantID.toString() },
            seams =
                TenantApplicationsViewModel.Seams(
                    list = { _ -> appList },
                    update = { _, clientId, request ->
                      requested = clientId to request
                      makeApp("lab-vue", status = 1, expireTime = expire)
                    },
                ),
        )
    vm.load()

    val ok = vm.update(clientId = "lab-vue", status = 1, expireTime = expire)

    assertTrue(ok)
    assertEquals("lab-vue", requested?.first, "寻址 clientId 字符串列非 UUID id")
    assertEquals(1, requested?.second?.status)
    assertEquals(expire, requested?.second?.expireTime)
    val row = vm.applications.value.first { it.clientId == "lab-vue" }
    assertEquals(1, row.status, "成功以响应原位替换行")
    assertEquals(expire, row.expireTime)
  }

  @Test
  fun `remove deletes row by client id`() = runBlocking {
    var removed: Pair<String, String>? = null
    val vm =
        TenantApplicationsViewModel(
            tenantId = { tenantID.toString() },
            seams =
                TenantApplicationsViewModel.Seams(
                    list = { _ -> appList },
                    remove = { tenantId, clientId -> removed = tenantId to clientId },
                ),
        )
    vm.load()

    val ok = vm.remove(clientId = "lab-vue")

    assertTrue(ok)
    assertEquals(tenantID.toString(), removed?.first)
    assertEquals("lab-vue", removed?.second)
    assertNull(
        vm.applications.value.firstOrNull { it.clientId == "lab-vue" },
        "DELETE 后本地行按 clientId 移除",
    )
    assertEquals(1, vm.applications.value.size)
  }

  @Test
  fun `lifecycle ops without tenant fail fast`() = runBlocking {
    var seamCalled = false
    val marked: () -> Unit = { seamCalled = true }
    val vm =
        TenantApplicationsViewModel(
            tenantId = { null },
            seams =
                TenantApplicationsViewModel.Seams(
                    list = { _ ->
                      marked()
                      appList
                    },
                    subscribe = { _, _ ->
                      marked()
                      makeApp("x")
                    },
                    update = { _, _, _ ->
                      marked()
                      makeApp("x")
                    },
                    remove = { _, _ -> marked() },
                ),
        )

    var ok = vm.load()
    assertFalse(ok)
    ok = vm.subscribe(clientId = "x", expireTime = null)
    assertFalse(ok)
    ok = vm.update(clientId = "x", status = 1, expireTime = null)
    assertFalse(ok)
    ok = vm.remove(clientId = "x")
    assertFalse(ok)
    assertFalse(seamCalled, "无租户上下文必须 fail-fast，一个缝都不许发")
    assertTrue(vm.phase.value is TenantApplicationsViewModel.Phase.Failed, "无租户上下文应报红字")
  }

  @Test
  fun `failure seam keeps list and reports retryable`() = runBlocking {
    var reject = true
    val vm =
        TenantApplicationsViewModel(
            tenantId = { tenantID.toString() },
            seams =
                TenantApplicationsViewModel.Seams(
                    list = { _ -> appList },
                    subscribe = { _, request ->
                      if (reject) throw AuthError.Server("请求失败（HTTP 400）")
                      makeApp(request.clientId)
                    },
                ),
        )
    vm.load()
    val snapshot = vm.applications.value.map { it.clientId }

    val failed = vm.subscribe(clientId = "lab-react", expireTime = null)

    assertFalse(failed)
    assertTrue(
        (vm.phase.value as TenantApplicationsViewModel.Phase.Failed).message.contains("400"),
        "红字带 HTTP 码（重复订阅后端 400 裸 SQL 泄漏如实呈现，REQ-012 探针口径）",
    )
    assertEquals(snapshot, vm.applications.value.map { it.clientId }, "失败列表不动")

    reject = false
    val retry = vm.subscribe(clientId = "lab-react", expireTime = null)
    assertTrue(retry, "同一 VM 可直接重试")
    assertEquals(3, vm.applications.value.size)
  }
}
