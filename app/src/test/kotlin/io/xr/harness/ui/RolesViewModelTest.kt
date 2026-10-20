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
import saas.identity.shared.models.CreateSysRoleRequest
import saas.identity.shared.models.RoleMenuGrant
import saas.identity.shared.models.SysRole
import saas.identity.shared.models.TenantRolesListSysRoles200Response

/**
 * RolesViewModel fake-seam JVM 单测（swift RolesViewModelTests 同构，M00.F03 CRUD + M00.F04
 * 菜单授权，REQ-2026-010/011）： 租户上下文 nil = fail-fast 缝都不许被碰； 生命周期成功原位替换/追加行；失败只红字列表不动可重试； 授权 PUT
 * 全量替换且授权态以响应 为准（readback 顺序不保证——消费端 Set 语义）。
 */
class RolesViewModelTest {

  private val tenantID = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
  private val adminRoleID = Uuid.parse("a0000000-0000-0000-0000-000000000001")
  private val probeRoleID = Uuid.parse("b0000000-0000-0000-0000-000000000002")
  private val now = OffsetDateTime.parse("2023-11-14T22:13:20Z")

  private val menuA = "00000000-0000-0000-0000-910000000001"
  private val menuB = "00000000-0000-0000-0000-910000000002"
  private val menuC = "00000000-0000-0000-0000-910000000003"

  private fun makeRole(
      id: Uuid,
      code: String,
      name: String,
      status: Int = 1,
  ): SysRole =
      SysRole(
          id = id,
          tenantId = tenantID,
          clientId = "saas-console",
          roleCode = code,
          roleName = name,
          isPreset = id == adminRoleID,
          status = status,
          createdAt = now.toString(),
          updatedAt = now.toString(),
      )

  private val roleList =
      TenantRolesListSysRoles200Response(
          items =
              listOf(
                  makeRole(adminRoleID, "admin", "Administrator"),
                  makeRole(probeRoleID, "editor", "Editor"),
              ),
          page = 1,
          pageSize = 20,
          total = 2,
      )

  private fun makeGrant(
      roleId: Uuid,
      menuIds: List<String>,
  ): RoleMenuGrant =
      RoleMenuGrant(
          roleId = roleId,
          tenantId = tenantID,
          menuIds = menuIds,
          updatedAt = now.toString(),
      )

  @Test
  fun `load populates roles`() = runBlocking {
    var listed: String? = null
    val vm =
        RolesViewModel(
            tenantId = {
              listed = tenantID.toString()
              tenantID.toString()
            },
            seams = RolesViewModel.Seams(listRoles = { _ -> roleList }),
        )

    val ok = vm.load()

    assertTrue(ok)
    assertEquals(tenantID.toString(), listed)
    assertEquals(2, vm.roles.value.size)
    assertEquals("admin", vm.roles.value.first().roleCode)
    assertEquals(RolesViewModel.Phase.Idle, vm.phase.value)
  }

  @Test
  fun `create appends row to list`() = runBlocking {
    var requested: CreateSysRoleRequest? = null
    val created = makeRole(Uuid.parse("c0000000-0000-0000-0000-000000000003"), "viewer", "Viewer")
    val vm =
        RolesViewModel(
            tenantId = { tenantID.toString() },
            seams =
                RolesViewModel.Seams(
                    listRoles = { _ -> roleList },
                    createRole = { _, request ->
                      requested = request
                      created
                    },
                ),
        )
    vm.load()

    val ok =
        vm.create(
            clientId = "saas-console",
            roleCode = "viewer",
            roleName = "Viewer",
            description = "只读",
        )

    assertTrue(ok)
    assertEquals("saas-console", requested?.clientId)
    assertEquals("viewer", requested?.roleCode)
    assertEquals("Viewer", requested?.roleName)
    assertEquals("只读", requested?.description)
    assertEquals(3, vm.roles.value.size, "成功追加行（AC-2）")
    assertEquals("viewer", vm.roles.value.last().roleCode)
  }

  @Test
  fun `update replaces row in place`() = runBlocking {
    var requested: Pair<String, saas.identity.shared.models.UpdateSysRoleRequest>? = null
    val vm =
        RolesViewModel(
            tenantId = { tenantID.toString() },
            seams =
                RolesViewModel.Seams(
                    listRoles = { _ -> roleList },
                    updateRole = { _, roleId, request ->
                      requested = roleId to request
                      makeRole(
                          probeRoleID,
                          "editor",
                          request.roleName ?: "?",
                          status = request.status ?: 1,
                      )
                    },
                ),
        )
    vm.load()

    val ok = vm.update(roleID = probeRoleID, roleName = "Editor改", description = null)

    assertTrue(ok)
    assertEquals(probeRoleID.toString(), requested?.first)
    assertEquals("Editor改", requested?.second?.roleName)
    assertEquals(
        "Editor改",
        vm.roles.value.first { it.id == probeRoleID }.roleName,
        "原位更新返回即见新值（AC-3）",
    )
    assertEquals(2, vm.roles.value.size)
  }

  @Test
  fun `set status replaces row both ways`() = runBlocking {
    var current: Int = 1
    val vm =
        RolesViewModel(
            tenantId = { tenantID.toString() },
            seams =
                RolesViewModel.Seams(
                    listRoles = { _ -> roleList },
                    updateRole = { _, _, request ->
                      current = request.status ?: 1
                      makeRole(probeRoleID, "editor", "Editor", status = current)
                    },
                ),
        )
    vm.load()

    val disabled = vm.setStatus(roleID = probeRoleID, to = 0)

    assertTrue(disabled)
    assertEquals(0, current, "setStatus 缝收到停用值")
    assertEquals(0, vm.roles.value.first { it.id == probeRoleID }.status, "停用原位生效（AC-4）")

    val enabled = vm.setStatus(roleID = probeRoleID, to = 1)

    assertTrue(enabled)
    assertEquals(1, current, "setStatus 缝收到启用值")
    assertEquals(1, vm.roles.value.first { it.id == probeRoleID }.status, "启用原位生效")
  }

  @Test
  fun `remove deletes row without response body`() = runBlocking {
    var removed: Pair<String, String>? = null
    val vm =
        RolesViewModel(
            tenantId = { tenantID.toString() },
            seams =
                RolesViewModel.Seams(
                    listRoles = { _ -> roleList },
                    deleteRole = { tenantId, roleId -> removed = tenantId to roleId },
                ),
        )
    vm.load()

    val ok = vm.remove(roleID = probeRoleID)

    assertTrue(ok)
    assertEquals(tenantID.toString(), removed?.first)
    assertEquals(probeRoleID.toString(), removed?.second)
    assertEquals(listOf(adminRoleID), vm.roles.value.map { it.id }, "204 后行消失（AC-5）")
  }

  @Test
  fun `refresh replaces row with authoritative state`() = runBlocking {
    val vm =
        RolesViewModel(
            tenantId = { tenantID.toString() },
            seams =
                RolesViewModel.Seams(
                    listRoles = { _ -> roleList },
                    getRole = { _, _ -> makeRole(probeRoleID, "editor", "Editor权威", status = 0) },
                ),
        )
    vm.load()

    val ok = vm.refresh(roleID = probeRoleID)

    assertTrue(ok)
    val row = vm.roles.value.first { it.id == probeRoleID }
    assertEquals("Editor权威", row.roleName)
    assertEquals(0, row.status)
  }

  @Test
  fun `lifecycle ops without tenant fail fast`() = runBlocking {
    var seamCalled = false
    val marked: () -> Unit = { seamCalled = true }
    val vm =
        RolesViewModel(
            tenantId = { null },
            seams =
                RolesViewModel.Seams(
                    listRoles = { _ ->
                      marked()
                      roleList
                    },
                    createRole = { _, _ ->
                      marked()
                      makeRole(probeRoleID, "x", "x")
                    },
                    getRole = { _, _ ->
                      marked()
                      makeRole(probeRoleID, "x", "x")
                    },
                    updateRole = { _, _, _ ->
                      marked()
                      makeRole(probeRoleID, "x", "x")
                    },
                    deleteRole = { _, _ -> marked() },
                ),
        )

    var ok = vm.load()
    assertFalse(ok)
    ok = vm.create(clientId = "saas-console", roleCode = "x", roleName = "x", description = null)
    assertFalse(ok)
    ok = vm.update(roleID = probeRoleID, roleName = "x", description = null)
    assertFalse(ok)
    ok = vm.remove(roleID = probeRoleID)
    assertFalse(ok)
    ok = vm.refresh(roleID = probeRoleID)
    assertFalse(ok)
    assertFalse(seamCalled, "无租户上下文必须 fail-fast，一个缝都不许发")
    assertTrue(vm.phase.value is RolesViewModel.Phase.Failed, "无租户上下文应报红字")
  }

  @Test
  fun `load grants populates grant aggregates`() = runBlocking {
    var requested: Pair<String, String>? = null
    val vm =
        RolesViewModel(
            tenantId = { tenantID.toString() },
            seams =
                RolesViewModel.Seams(
                    listRoles = { _ -> roleList },
                    listGrants = { tenantId, roleId ->
                      requested = tenantId to roleId
                      makeGrant(probeRoleID, listOf(menuA, menuB))
                    },
                ),
        )

    val ok = vm.loadGrants(roleID = probeRoleID)

    assertTrue(ok)
    assertEquals(tenantID.toString(), requested?.first)
    assertEquals(probeRoleID.toString(), requested?.second)
    assertEquals(probeRoleID, vm.grants.value?.roleId)
    assertEquals(tenantID, vm.grants.value?.tenantId)
    assertEquals(setOf(menuA, menuB), vm.grants.value?.menuIds?.toSet())
  }

  @Test
  fun `save grants sends full replacement and keeps response`() = runBlocking {
    var requested: Pair<String, List<String>>? = null
    val vm =
        RolesViewModel(
            tenantId = { tenantID.toString() },
            seams =
                RolesViewModel.Seams(
                    listRoles = { _ -> roleList },
                    listGrants = { _, _ -> makeGrant(probeRoleID, listOf(menuA)) },
                    setGrants = { _, roleId, request ->
                      requested = roleId to request.menuIds
                      // 后端 readback 集合相等但顺序不保证（REQ-011 探针实证）——
                      // fake 故意返回乱序，VM 端必须照存响应、不做重排假设。
                      makeGrant(probeRoleID, listOf(menuC, menuB))
                    },
                ),
        )
    vm.loadGrants(roleID = probeRoleID)

    val ok = vm.saveGrants(roleID = probeRoleID, menuIds = listOf(menuB, menuC))

    assertTrue(ok)
    assertEquals(probeRoleID.toString(), requested?.first)
    assertEquals(listOf(menuB, menuC), requested?.second, "PUT 全量替换：提交集合原样上送")
    assertEquals(setOf(menuB, menuC), vm.grants.value?.menuIds?.toSet(), "授权态以响应为准")
  }

  @Test
  fun `clear grants empties grant in place`() = runBlocking {
    var cleared: Pair<String, String>? = null
    val vm =
        RolesViewModel(
            tenantId = { tenantID.toString() },
            seams =
                RolesViewModel.Seams(
                    listRoles = { _ -> roleList },
                    listGrants = { _, _ -> makeGrant(probeRoleID, listOf(menuA, menuB, menuC)) },
                    clearGrants = { tenantId, roleId -> cleared = tenantId to roleId },
                ),
        )
    vm.loadGrants(roleID = probeRoleID)
    assertEquals(3, vm.grants.value?.menuIds?.size)

    val ok = vm.clearGrants(roleID = probeRoleID)

    assertTrue(ok)
    assertEquals(tenantID.toString(), cleared?.first)
    assertEquals(probeRoleID.toString(), cleared?.second)
    assertEquals(emptyList<String>(), vm.grants.value?.menuIds, "204 后本地授权态清空（AC-3）")
    assertEquals(probeRoleID, vm.grants.value?.roleId)
  }

  @Test
  fun `grant ops without tenant fail fast`() = runBlocking {
    var seamCalled = false
    val marked: () -> Unit = { seamCalled = true }
    val vm =
        RolesViewModel(
            tenantId = { null },
            seams =
                RolesViewModel.Seams(
                    listRoles = { _ ->
                      marked()
                      roleList
                    },
                    listGrants = { _, _ ->
                      marked()
                      makeGrant(probeRoleID, emptyList())
                    },
                    setGrants = { _, _, _ ->
                      marked()
                      makeGrant(probeRoleID, emptyList())
                    },
                    clearGrants = { _, _ -> marked() },
                ),
        )

    var ok = vm.loadGrants(roleID = probeRoleID)
    assertFalse(ok)
    ok = vm.saveGrants(roleID = probeRoleID, menuIds = listOf(menuA))
    assertFalse(ok)
    ok = vm.clearGrants(roleID = probeRoleID)
    assertFalse(ok)
    assertFalse(seamCalled, "无租户上下文必须 fail-fast，一个缝都不许发")
    assertTrue(vm.phase.value is RolesViewModel.Phase.Failed, "无租户上下文应报红字")
  }

  @Test
  fun `failure seam keeps list and reports retryable`() = runBlocking {
    var reject = true
    val vm =
        RolesViewModel(
            tenantId = { tenantID.toString() },
            seams =
                RolesViewModel.Seams(
                    listRoles = { _ -> roleList },
                    createRole = { _, _ ->
                      if (reject) throw AuthError.Server("请求失败（HTTP 500）")
                      makeRole(
                          Uuid.parse("c0000000-0000-0000-0000-000000000003"),
                          "viewer",
                          "Viewer",
                      )
                    },
                ),
        )
    vm.load()
    val snapshot = vm.roles.value.map { it.id }

    val failed =
        vm.create(
            clientId = "saas-console",
            roleCode = "viewer",
            roleName = "Viewer",
            description = null,
        )

    assertFalse(failed)
    assertTrue(
        (vm.phase.value as RolesViewModel.Phase.Failed).message.contains("500"),
        "红字带 HTTP 码（重名 roleCode 后端 500 空 body 如实呈现，REQ-010 Q1）",
    )
    assertEquals(snapshot, vm.roles.value.map { it.id }, "失败列表不动（AC-3）")

    reject = false
    val retry =
        vm.create(
            clientId = "saas-console",
            roleCode = "viewer",
            roleName = "Viewer",
            description = null,
        )
    assertTrue(retry, "同一 VM 可直接重试（AC-3 可重试）")
    assertEquals(3, vm.roles.value.size)
  }

  @Test
  fun `clear grants without tenant keeps stale grants untouched`() = runBlocking {
    val vm =
        RolesViewModel(
            tenantId = { tenantID.toString() },
            seams =
                RolesViewModel.Seams(
                    listRoles = { _ -> roleList },
                    listGrants = { _, _ -> makeGrant(probeRoleID, listOf(menuA)) },
                ),
        )
    vm.loadGrants(roleID = probeRoleID)
    val tenantless =
        RolesViewModel(
            tenantId = { null },
            seams = RolesViewModel.Seams(listRoles = { _ -> roleList }),
        )
    // 无租户 VM 的 grants 独立为 null：清空聚合只在成功后落地，不预清（fail-fast 路径不碰状态）。
    assertNull(tenantless.grants.value)
  }
}
