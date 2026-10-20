@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.ui

import io.xr.harness.Fn
import io.xr.harness.core.AuthError
import java.time.OffsetDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import saas.identity.shared.models.AdminTenantsListTenants200Response
import saas.identity.shared.models.CreateTenantRequest
import saas.identity.shared.models.Tenant
import saas.identity.shared.models.TenantStatus
import saas.identity.shared.models.UpdateTenantRequest

/**
 * AdminTenantsViewModel fake-seam JVM 单测（swift AdminTenantsViewModelTests 同构，M00.F01，REQ-2026-007）：
 * 平台 admin 租户 CRUD； 寻址 UUID id 非 key（Q1，与 admin/clients 的 clientId 字符串口径相反）； status 字符串枚举
 * active/suspended（Q2， 与 OAuthClient 的 Int 两套口径）； tenantKey 重复 409（Q3）。 成功原位维护列表不重拉；失败红字列表不动可重试。
 */
class AdminTenantsViewModelTest {

  private val acmeId = Uuid.parse("00000000-0000-0000-0000-000000000001")
  private val globexId = Uuid.parse("00000000-0000-0000-0000-000000000002")
  private val stamp = OffsetDateTime.parse("2023-11-14T22:13:20Z")

  private fun makeTenant(
      id: Uuid,
      key: String,
      name: String,
      status: TenantStatus = TenantStatus.active,
  ): Tenant =
      Tenant(
          id = id,
          tenantKey = key,
          name = name,
          status = status,
          createdAt = stamp.toString(),
          updatedAt = stamp.toString(),
      )

  private val seedList =
      AdminTenantsListTenants200Response(
          items =
              listOf(
                  makeTenant(acmeId, "acme", "ACME Corp"),
                  makeTenant(globexId, "globex", "Globex Industries"),
              ),
          page = 0,
          pageSize = 20,
          total = 2,
      )

  @Test
  @Fn(["M00.F01"])
  fun `load populates tenants without paging`() = runBlocking {
    var listCalled = false
    val vm =
        AdminTenantsViewModel(
            seams =
                AdminTenantsViewModel.Seams(
                    list = {
                      listCalled = true
                      seedList
                    },
                ),
        )

    val ok = vm.load()

    assertTrue(ok, "list 不传分页（Q1：分页 0-indexed，nil 全量）")
    assertTrue(listCalled)
    assertEquals(listOf("acme", "globex"), vm.tenants.value.map { it.tenantKey }, "列表全量渲染（AC-1）")
    assertEquals(AdminTenantsViewModel.Phase.Idle, vm.phase.value)
  }

  @Test
  @Fn(["M00.F01"])
  fun `load failure keeps empty and retryable`() = runBlocking {
    var reject = true
    val vm =
        AdminTenantsViewModel(
            seams =
                AdminTenantsViewModel.Seams(
                    list = {
                      if (reject) throw AuthError.Server("请求失败（HTTP 403）")
                      seedList
                    },
                ),
        )

    val ok = vm.load()

    assertFalse(ok, "403 = 失败（AC-4）")
    assertTrue(
        (vm.phase.value as AdminTenantsViewModel.Phase.Failed).message.contains("403"),
        "红字带 HTTP 码",
    )
    assertTrue(vm.tenants.value.isEmpty(), "失败不发假数据")

    reject = false
    val retry = vm.load()
    assertTrue(retry, "同一 VM 可直接重试（AC-4）")
    assertEquals(2, vm.tenants.value.size)
  }

  @Test
  @Fn(["M00.F01"])
  fun `create appends row in place without reload`() = runBlocking {
    var listCalls = 0
    var received: CreateTenantRequest? = null
    val vm =
        AdminTenantsViewModel(
            seams =
                AdminTenantsViewModel.Seams(
                    list = {
                      listCalls++
                      seedList
                    },
                    create = { request ->
                      received = request
                      makeTenant(Uuid.random(), "initech", "Initech")
                    },
                ),
        )
    vm.load()

    val ok = vm.create(CreateTenantRequest(tenantKey = "initech", name = "Initech"))

    assertTrue(ok)
    assertEquals("initech", received?.tenantKey, "create 缝收到 tenantKey+name（AC-2）")
    assertEquals("Initech", received?.name)
    assertEquals(3, vm.tenants.value.size, "create 成功后 count +1（AC-2）")
    assertEquals("initech", vm.tenants.value.last().tenantKey, "create 成功追加行（AC-2）")
    assertEquals(1, listCalls, "不重拉列表（原位追加）")
  }

  @Test
  @Fn(["M00.F01"])
  fun `create conflict keeps list and retryable`() = runBlocking {
    var reject = true
    val vm =
        AdminTenantsViewModel(
            seams =
                AdminTenantsViewModel.Seams(
                    list = { seedList },
                    create = { _ ->
                      if (reject) throw AuthError.Server("请求失败（HTTP 409）")
                      makeTenant(Uuid.random(), "initech", "Initech")
                    },
                ),
        )
    vm.load()

    val failed = vm.create(CreateTenantRequest(tenantKey = "initech", name = "Initech"))

    assertFalse(failed, "tenantKey 重复 409 = 失败（AC-3/Q3）")
    assertTrue(
        (vm.phase.value as AdminTenantsViewModel.Phase.Failed).message.contains("409"),
        "红字带 HTTP 码（AC-4）",
    )
    assertEquals(2, vm.tenants.value.size, "失败列表不动（AC-4）")

    reject = false
    val retry = vm.create(CreateTenantRequest(tenantKey = "initech", name = "Initech"))
    assertTrue(retry, "同一 VM 可直接重试（AC-4）")
    assertEquals("initech", vm.tenants.value.last().tenantKey)
  }

  @Test
  @Fn(["M00.F01"])
  fun `update replaces row in place`() = runBlocking {
    var received: Pair<Uuid, UpdateTenantRequest>? = null
    val vm =
        AdminTenantsViewModel(
            seams =
                AdminTenantsViewModel.Seams(
                    list = { seedList },
                    update = { id, request ->
                      received = id to request
                      makeTenant(acmeId, "acme", "ACME（改名后）")
                    },
                ),
        )
    vm.load()

    val ok = vm.update(acmeId, UpdateTenantRequest(name = "ACME（改名后）"))

    assertTrue(ok)
    assertEquals(acmeId, received?.first, "寻址 UUID id（Q1，非 key 非字符串）")
    assertEquals("ACME（改名后）", received?.second?.name)
    assertEquals(
        "ACME（改名后）",
        vm.tenants.value.first { it.id == acmeId }.name,
        "update 成功原位替换行（AC-3）",
    )
    assertEquals(
        "Globex Industries",
        vm.tenants.value.first { it.id == globexId }.name,
        "其他行不动",
    )
    assertEquals(2, vm.tenants.value.size, "列表长度不变（原位替换非重拉）")
  }

  @Test
  @Fn(["M00.F01"])
  fun `update status uses string enum`() = runBlocking {
    var receivedStatus: TenantStatus? = null
    val vm =
        AdminTenantsViewModel(
            seams =
                AdminTenantsViewModel.Seams(
                    list = { seedList },
                    update = { _, request ->
                      receivedStatus = request.status
                      makeTenant(acmeId, "acme", "ACME Corp", status = TenantStatus.suspended)
                    },
                ),
        )
    vm.load()

    val ok = vm.update(acmeId, UpdateTenantRequest(status = TenantStatus.suspended))

    assertTrue(ok, "停用租户成功（AC-3）")
    assertEquals(TenantStatus.suspended, receivedStatus, "status 走字符串枚举（Q2，非 Int）")
    assertEquals(
        TenantStatus.suspended,
        vm.tenants.value.first { it.id == acmeId }.status,
        "行随返回体刷新",
    )
  }

  @Test
  @Fn(["M00.F01"])
  fun `delete removes row in place`() = runBlocking {
    var deleted: Uuid? = null
    val vm =
        AdminTenantsViewModel(
            seams =
                AdminTenantsViewModel.Seams(
                    list = { seedList },
                    delete = { id -> deleted = id },
                ),
        )
    vm.load()

    val ok = vm.deleteTenant(acmeId)

    assertTrue(ok)
    assertEquals(acmeId, deleted, "delete 缝收到 UUID id（Q1）")
    assertEquals(listOf("globex"), vm.tenants.value.map { it.tenantKey }, "删除后原位移除（AC-2）")
    assertEquals(1, vm.tenants.value.size, "其他行完整保留")
  }
}
