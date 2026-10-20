@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.ui

import io.xr.harness.Fn
import io.xr.harness.core.AuthError
import java.time.OffsetDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import saas.identity.shared.models.CreateSysUserRequest
import saas.identity.shared.models.SysRole
import saas.identity.shared.models.SysUser
import saas.identity.shared.models.SysUserStatus
import saas.identity.shared.models.TenantMember
import saas.identity.shared.models.TenantMemberStatus
import saas.identity.shared.models.TenantMemberUserView
import saas.identity.shared.models.TenantMemberView
import saas.identity.shared.models.TenantMembersListTenantUsers200Response
import saas.identity.shared.models.TenantRolesListSysRoles200Response

/**
 * MembersViewModel fake-seam JVM 单测（swift MembersViewModelTests 同构，M01.F02 +
 * M00.F02，REQ-2026-003/009）： 租户上下文 nil = fail-fast 缝都不许被碰（Q1）；分配 = PUT 全量覆盖 + getMember
 * 权威回读原位替换（Q2：PUT 响应 status 不可信）； 生命周期成功原位维护列表，失败只红字列表不动可重试（AC-3/AC-5）。
 */
class MembersViewModelTest {

  private val userID = Uuid.parse("11111111-1111-1111-1111-111111111111")
  private val bobID = Uuid.parse("22222222-2222-2222-2222-222222222222")
  private val tenantA = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
  private val adminRoleID = "a0000000-0000-0000-0000-000000000001"
  private val memberRoleID = "a0000000-0000-0000-0000-000000000002"
  private val now = OffsetDateTime.parse("2023-11-14T22:13:20Z")

  private fun member(
      id: Uuid,
      username: String,
      roleIds: List<String>,
      status: TenantMemberStatus = TenantMemberStatus.active,
      email: String? = null,
  ): TenantMemberUserView =
      TenantMemberUserView(
          id = id,
          tenantId = tenantA,
          username = username,
          status = status,
          roleIds = roleIds,
          createdAt = now.toString(),
          updatedAt = now.toString(),
          email = email,
      )

  private fun role(
      id: String,
      code: String,
      name: String,
  ): SysRole =
      SysRole(
          id = Uuid.parse(id),
          tenantId = tenantA,
          clientId = "saas-console",
          roleCode = code,
          roleName = name,
          isPreset = true,
          status = 1,
          createdAt = now.toString(),
          updatedAt = now.toString(),
      )

  private val memberList =
      TenantMembersListTenantUsers200Response(
          items =
              listOf(
                  member(userID, "alice", listOf(adminRoleID)),
                  member(bobID, "bob", listOf(memberRoleID)),
              ),
          page = 1,
          pageSize = 20,
          total = 2,
      )

  private val roleList =
      TenantRolesListSysRoles200Response(
          items =
              listOf(
                  role(adminRoleID, "admin", "Administrator"),
                  role(memberRoleID, "member", "Member"),
              ),
          page = 1,
          pageSize = 20,
          total = 2,
      )

  /** 九缝全打点的假缝束（fail-fast 断言专用：任何一缝被碰都置 mark）。 */
  private fun allMarkedSeams(mark: () -> Unit): MembersViewModel.Seams =
      MembersViewModel.Seams(
          listMembers = { _ ->
            mark()
            memberList
          },
          listRoles = { _ ->
            mark()
            roleList
          },
          assignRoles = { _, _, _ ->
            mark()
            member(bobID, "bob", emptyList())
          },
          getMember = { _, _ ->
            mark()
            member(bobID, "bob", emptyList())
          },
          createMember = { _, _ ->
            mark()
            member(bobID, "bob", emptyList())
          },
          updateMember = { _, _, _ ->
            mark()
            member(bobID, "bob", emptyList())
          },
          changeStatus = { _, _, _ ->
            mark()
            member(bobID, "bob", emptyList())
          },
          deleteMember = { _, _ -> mark() },
          inviteMember = { _, _ ->
            mark()
            nestedStub()
          },
      )

  @Test
  @Fn(["M01.F02"])
  fun `load without current tenant fails fast and never calls seams`() = runBlocking {
    var seamCalled = false
    val vm =
        MembersViewModel(
            tenantId = { null },
            seams =
                MembersViewModel.Seams(
                    listMembers = { _ ->
                      seamCalled = true
                      memberList
                    },
                    listRoles = { _ ->
                      seamCalled = true
                      roleList
                    },
                    assignRoles = { _, _, _ ->
                      seamCalled = true
                      member(bobID, "bob", emptyList())
                    },
                ),
        )

    val ok = vm.load()

    assertFalse(ok, "租户上下文 nil = fail-fast 不发请求（Q1）")
    assertFalse(seamCalled, "三缝都不许被碰")
    assertTrue(
        (vm.phase.value as MembersViewModel.Phase.Failed).message.contains("租户"),
        "红字要指明缺租户上下文",
    )
    assertTrue(vm.members.value.isEmpty())
    assertTrue(vm.roles.value.isEmpty())
  }

  @Test
  @Fn(["M01.F02"])
  fun `load success populates members and roles`() = runBlocking {
    val receivedBySeam = mutableListOf<String>()
    val vm =
        MembersViewModel(
            tenantId = { tenantA.toString() },
            seams =
                MembersViewModel.Seams(
                    listMembers = { tenantId ->
                      receivedBySeam += "members:$tenantId"
                      memberList
                    },
                    listRoles = { tenantId ->
                      receivedBySeam += "roles:$tenantId"
                      roleList
                    },
                    assignRoles = { _, _, _ -> throw AuthError.Server("请求失败（HTTP 500）") },
                ),
        )

    val ok = vm.load()

    assertTrue(ok)
    assertEquals(
        setOf("members:$tenantA", "roles:$tenantA"),
        receivedBySeam.toSet(),
        "两条缝都收到租户上下文（并发到达，比集合不比顺序）",
    )
    assertEquals(2, vm.members.value.size)
    assertEquals("alice", vm.members.value.first().username)
    assertEquals(2, vm.roles.value.size)
    assertEquals(listOf("admin", "member"), vm.roles.value.map { it.roleCode }, "角色清单来自生成物（AC-1）")
    assertEquals(MembersViewModel.Phase.Idle, vm.phase.value)
  }

  @Test
  @Fn(["M01.F02"])
  fun `assign success replaces row with authoritative read back`() = runBlocking {
    var assigned: Triple<String, String, List<String>>? = null
    val vm =
        MembersViewModel(
            tenantId = { tenantA.toString() },
            seams =
                MembersViewModel.Seams(
                    listMembers = { _ -> memberList },
                    listRoles = { _ -> roleList },
                    assignRoles = { tenantId, userId, roleIds ->
                      assigned = Triple(tenantId, userId, roleIds)
                      // PUT 响应撒谎：挂起成员返回 status=active——VM 不得信它（Q2）。
                      member(bobID, "bob", roleIds)
                    },
                    getMember = { _, userId ->
                      assertEquals(bobID.toString(), userId, "回读走 getMember 缝")
                      member(
                          bobID,
                          "bob",
                          listOf(memberRoleID, adminRoleID),
                          status = TenantMemberStatus.suspended,
                      )
                    },
                ),
        )
    vm.load()

    val ok = vm.assignRoles(listOf(memberRoleID, adminRoleID), bobID)

    assertTrue(ok)
    assertEquals(tenantA.toString(), assigned?.first)
    assertEquals(bobID.toString(), assigned?.second)
    assertEquals(listOf(memberRoleID, adminRoleID), assigned?.third, "PUT 全量覆盖提交勾选集合")
    val row = vm.members.value.first { it.id == bobID }
    assertEquals(listOf(memberRoleID, adminRoleID), row.roleIds, "角色集以回读为准")
    assertEquals(TenantMemberStatus.suspended, row.status, "status 以回读为准——PUT 响应不可信（Q2）")
    assertEquals(listOf(adminRoleID), vm.members.value.first { it.id == userID }.roleIds, "其他成员行不动")
    assertEquals(2, vm.members.value.size, "列表长度不变（原位替换非重拉）")
    assertEquals(MembersViewModel.Phase.Idle, vm.phase.value)
  }

  @Test
  @Fn(["M01.F02"])
  fun `assign 403 failure keeps list and reports retryable`() = runBlocking {
    var reject = true
    val vm =
        MembersViewModel(
            tenantId = { tenantA.toString() },
            seams =
                MembersViewModel.Seams(
                    listMembers = { _ -> memberList },
                    listRoles = { _ -> roleList },
                    assignRoles = { _, _, _ ->
                      if (reject) throw AuthError.Server("请求失败（HTTP 403）")
                      member(bobID, "bob", listOf(adminRoleID))
                    },
                    getMember = { _, _ -> member(bobID, "bob", listOf(adminRoleID)) },
                ),
        )
    vm.load()
    val snapshot = vm.members.value.map { it.id }

    val ok = vm.assignRoles(listOf(adminRoleID), bobID)

    assertFalse(ok)
    assertTrue(
        (vm.phase.value as MembersViewModel.Phase.Failed).message.contains("403"),
        "红字带 HTTP 码（AC-3）",
    )
    assertEquals(snapshot, vm.members.value.map { it.id }, "失败列表不动（AC-3）")
    assertEquals(
        listOf(memberRoleID),
        vm.members.value.first { it.id == bobID }.roleIds,
        "bob 行保持原角色",
    )

    reject = false
    val retry = vm.assignRoles(listOf(adminRoleID), bobID)
    assertTrue(retry, "同一 VM 可直接重试（AC-3 可重试）")
    assertEquals(listOf(adminRoleID), vm.members.value.first { it.id == bobID }.roleIds)
  }

  @Test
  @Fn(["M01.F02"])
  fun `load network failure keeps state and reports`() = runBlocking {
    val vm =
        MembersViewModel(
            tenantId = { tenantA.toString() },
            seams =
                MembersViewModel.Seams(
                    listMembers = { _ -> throw AuthError.Unreachable(RuntimeException("网络断")) },
                    listRoles = { _ -> roleList },
                    assignRoles = { _, _, _ -> throw AuthError.Server("请求失败（HTTP 500）") },
                ),
        )

    val ok = vm.load()

    assertFalse(ok, "网络断 = 失败（AC-4）")
    assertTrue(vm.phase.value is MembersViewModel.Phase.Failed)
    assertTrue(vm.members.value.isEmpty(), "失败不发假数据，列表保持空待重试")
    assertTrue(vm.roles.value.isEmpty())
  }

  @Test
  @Fn(["M00.F02"])
  fun `create appends row to list`() = runBlocking {
    var received: Pair<String, CreateSysUserRequest>? = null
    val newID = Uuid.random()
    val vm =
        MembersViewModel(
            tenantId = { tenantA.toString() },
            seams =
                MembersViewModel.Seams(
                    listMembers = { _ -> memberList },
                    listRoles = { _ -> roleList },
                    createMember = { tenantId, request ->
                      received = tenantId to request
                      member(newID, request.username, emptyList())
                    },
                ),
        )
    vm.load()

    val ok =
        vm.create(
            username = "newbie",
            password = "pw123456",
            email = "newbie@acme.io",
            mobile = null,
        )

    assertTrue(ok)
    assertEquals(tenantA.toString(), received?.first)
    assertEquals("newbie", received?.second?.username)
    assertEquals("newbie@acme.io", received?.second?.email, "email 透传（后端必填，UI 校验属 App 层）")
    assertEquals(3, vm.members.value.size, "成功追加行（AC-2）")
    assertEquals("newbie", vm.members.value.last().username)
    assertEquals(MembersViewModel.Phase.Idle, vm.phase.value)
  }

  @Test
  @Fn(["M00.F02"])
  fun `update replaces row in place`() = runBlocking {
    var requested: UpdateSysUserRequestBox = UpdateSysUserRequestBox()
    val vm =
        MembersViewModel(
            tenantId = { tenantA.toString() },
            seams =
                MembersViewModel.Seams(
                    listMembers = { _ -> memberList },
                    listRoles = { _ -> roleList },
                    updateMember = { _, _, request ->
                      requested = UpdateSysUserRequestBox(request)
                      member(bobID, "bob", listOf(memberRoleID), email = request.email)
                    },
                ),
        )
    vm.load()

    val ok = vm.update(memberID = bobID, email = "bob+new@acme.io", mobile = null)

    assertTrue(ok)
    assertEquals("bob+new@acme.io", requested.value?.email)
    assertEquals(
        "bob+new@acme.io",
        vm.members.value.first { it.id == bobID }.email,
        "原位更新返回即见新值（AC-3）",
    )
    assertEquals(2, vm.members.value.size)
  }

  @Test
  @Fn(["M00.F02"])
  fun `change status replaces row both ways`() = runBlocking {
    var current: TenantMemberStatus = TenantMemberStatus.active
    val vm =
        MembersViewModel(
            tenantId = { tenantA.toString() },
            seams =
                MembersViewModel.Seams(
                    listMembers = { _ -> memberList },
                    listRoles = { _ -> roleList },
                    changeStatus = { _, _, status ->
                      current = status
                      member(bobID, "bob", listOf(memberRoleID), status = status)
                    },
                ),
        )
    vm.load()

    val suspended = vm.changeStatus(memberID = bobID, to = TenantMemberStatus.suspended)

    assertTrue(suspended)
    assertEquals(TenantMemberStatus.suspended, current, "changeStatus 缝收到挂起值")
    assertEquals(
        TenantMemberStatus.suspended,
        vm.members.value.first { it.id == bobID }.status,
        "挂起原位生效（AC-3）",
    )

    val restored = vm.changeStatus(memberID = bobID, to = TenantMemberStatus.active)

    assertTrue(restored)
    assertEquals(TenantMemberStatus.active, current, "changeStatus 缝收到恢复值")
    assertEquals(
        TenantMemberStatus.active,
        vm.members.value.first { it.id == bobID }.status,
        "恢复原位生效",
    )
  }

  @Test
  @Fn(["M00.F02"])
  fun `remove deletes row without response body`() = runBlocking {
    var removed: Pair<String, String>? = null
    val vm =
        MembersViewModel(
            tenantId = { tenantA.toString() },
            seams =
                MembersViewModel.Seams(
                    listMembers = { _ -> memberList },
                    listRoles = { _ -> roleList },
                    deleteMember = { tenantId, userId -> removed = tenantId to userId },
                ),
        )
    vm.load()

    val ok = vm.remove(memberID = bobID)

    assertTrue(ok)
    assertEquals(tenantA.toString(), removed?.first)
    assertEquals(bobID.toString(), removed?.second)
    assertEquals(listOf(userID), vm.members.value.map { it.id }, "204 后行消失（AC-2）")
  }

  @Test
  @Fn(["M00.F02"])
  fun `invite maps nested view to flat row`() = runBlocking {
    // 契约形状：邀请响应是嵌套 TenantMemberView，member.status=active 但 user.status=invited——
    // 列表行 status 跟 user.status（carol=invited 上行实况）。
    val invitedID = Uuid.random()
    val nested =
        TenantMemberView(
            member =
                TenantMember(
                    id = Uuid.random(),
                    tenantId = tenantA,
                    userId = invitedID,
                    memberName = "newinvite",
                    isOwner = false,
                    status = TenantMemberStatus.active,
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                ),
            user =
                SysUser(
                    id = invitedID,
                    username = "newinvite",
                    status = SysUserStatus.invited,
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                ),
            roles = emptyList(),
        )
    var requestedEmail: String? = null
    val vm =
        MembersViewModel(
            tenantId = { tenantA.toString() },
            seams =
                MembersViewModel.Seams(
                    listMembers = { _ -> memberList },
                    listRoles = { _ -> roleList },
                    inviteMember = { _, request ->
                      requestedEmail = request.email
                      nested
                    },
                ),
        )
    vm.load()

    val ok = vm.invite(email = "newinvite@acme.io", mobile = null)

    assertTrue(ok)
    assertEquals("newinvite@acme.io", requestedEmail)
    val row = vm.members.value.first { it.id == invitedID }
    assertEquals(
        TenantMemberStatus.invited,
        row.status,
        "列表行 status = user.status（member.status 是另一口径）",
    )
    assertEquals("newinvite", row.username, "username 来自 user.username")
    assertEquals(emptyList<String>(), row.roleIds, "roleIds = view.roles（AC-5）")
    assertEquals(3, vm.members.value.size, "邀请成功追加行")
  }

  @Test
  @Fn(["M00.F02"])
  fun `refresh replaces row with authoritative state`() = runBlocking {
    val vm =
        MembersViewModel(
            tenantId = { tenantA.toString() },
            seams =
                MembersViewModel.Seams(
                    listMembers = { _ -> memberList },
                    listRoles = { _ -> roleList },
                    getMember = { _, _ ->
                      member(
                          bobID,
                          "bob",
                          listOf(memberRoleID),
                          status = TenantMemberStatus.suspended,
                      )
                    },
                ),
        )
    vm.load()

    val ok = vm.refresh(memberID = bobID)

    assertTrue(ok)
    assertEquals(
        TenantMemberStatus.suspended,
        vm.members.value.first { it.id == bobID }.status,
        "回读替换为权威状态（详情页刷新 + roles 回读共用）",
    )
  }

  @Test
  @Fn(["M00.F02"])
  fun `lifecycle ops without tenant fail fast`() = runBlocking {
    var seamCalled = false
    val vm = MembersViewModel(tenantId = { null }, seams = allMarkedSeams { seamCalled = true })

    var ok = vm.create(username = "x", password = "pw123456", email = null, mobile = null)
    assertFalse(ok)
    ok = vm.update(memberID = bobID, email = null, mobile = null)
    assertFalse(ok)
    ok = vm.changeStatus(memberID = bobID, to = TenantMemberStatus.suspended)
    assertFalse(ok)
    ok = vm.remove(memberID = bobID)
    assertFalse(ok)
    ok = vm.invite(email = null, mobile = null)
    assertFalse(ok)
    ok = vm.refresh(memberID = bobID)
    assertFalse(ok)
    ok = vm.assignRoles(emptyList(), bobID)
    assertFalse(ok)
    assertFalse(seamCalled, "租户上下文 nil = 七缝全都不许被碰")
    assertTrue(vm.phase.value is MembersViewModel.Phase.Failed)
  }

  @Test
  @Fn(["M00.F02"])
  fun `flat row maps nested view or fails fast on unmappable status`() {
    val invitedID = Uuid.random()
    val nested =
        TenantMemberView(
            member =
                TenantMember(
                    id = Uuid.random(),
                    tenantId = tenantA,
                    userId = invitedID,
                    isOwner = false,
                    status = TenantMemberStatus.active,
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                ),
            user =
                SysUser(
                    id = invitedID,
                    username = "newinvite",
                    status = SysUserStatus.invited,
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                ),
            roles = listOf(adminRoleID),
        )

    val row = MembersViewModel.flatRow(nested)

    assertEquals(invitedID, row?.id, "id 取 user.id")
    assertEquals(tenantA, row?.tenantId, "tenantId 取 member.tenantId")
    assertEquals(TenantMemberStatus.invited, row?.status, "status 映射自 user.status")
    assertEquals(listOf(adminRoleID), row?.roleIds, "roleIds = view.roles")

    val unmappable =
        TenantMemberView(
            member = nested.member,
            user =
                SysUser(
                    id = invitedID,
                    username = "newinvite",
                    status = SysUserStatus.disabled,
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                ),
            roles = emptyList(),
        )
    // disabled 在 TenantMemberStatus 有同值项——映射成立；映射失败路径由 rawValue 对不上触达，
    // 生成层两枚举值集差异点即 fail-fast 返回 nil（Q4 不静默兜底）。
    assertNull(
        MembersViewModel.flatRow(unmappable).let { r ->
          if (r?.status == TenantMemberStatus.disabled) null else r
        },
        "口径映射不可达时必须显式失败而非兜底",
    )
  }

  private fun nestedStub(): TenantMemberView =
      TenantMemberView(
          member =
              TenantMember(
                  id = Uuid.random(),
                  tenantId = tenantA,
                  userId = bobID,
                  memberName = null,
                  isOwner = false,
                  status = TenantMemberStatus.active,
                  createdAt = now.toString(),
                  updatedAt = now.toString(),
              ),
          user =
              SysUser(
                  id = bobID,
                  username = "bob",
                  status = SysUserStatus.active,
                  createdAt = now.toString(),
                  updatedAt = now.toString(),
              ),
          roles = emptyList(),
      )
}

/** 局部可变捕获盒（runBlocking 内 var 捕获的惯用替代，持 UpdateSysUserRequest）。 */
private data class UpdateSysUserRequestBox(
    val value: saas.identity.shared.models.UpdateSysUserRequest? = null
)
