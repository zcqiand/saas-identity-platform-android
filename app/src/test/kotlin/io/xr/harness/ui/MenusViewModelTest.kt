@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.ui

import io.xr.harness.core.AuthError
import io.xr.harness.core.MenuNode
import io.xr.harness.core.ROOT_SENTINEL
import java.time.OffsetDateTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import saas.identity.shared.models.CreateSysMenuRequest
import saas.identity.shared.models.SysMenu
import saas.identity.shared.models.SysMenuType
import saas.identity.shared.models.UpdateSysMenuRequest

/** MenusViewModel fake-seam JVM 单测（swift MenusViewModel 同构）：装载/CRUD 原地变更/失败不动列表语义逐条钉死。 */
class MenusViewModelTest {

  private fun menu(
      id: String,
      title: String = "菜单",
      sortOrder: Int = 1,
  ): SysMenu =
      SysMenu(
          id = Uuid.parse(id),
          clientId = "lab-management",
          parentId = Uuid.parse(ROOT_SENTINEL),
          title = title,
          type = SysMenuType.menu,
          sortOrder = sortOrder,
          status = 1,
          createdAt = OffsetDateTime.parse("2026-10-19T00:00:00Z").toString(),
      )

  /** 按需覆盖的 fake 缝组：未覆盖的缝给静默缺省。 */
  private class FakeSeams {
    var list: (suspend (String) -> List<SysMenu>)? = null
    var create: (suspend (String, CreateSysMenuRequest) -> SysMenu)? = null
    var update: (suspend (String, String, UpdateSysMenuRequest) -> SysMenu)? = null
    var move: (suspend (String, String, String?) -> SysMenu)? = null
    var reorder: (suspend (String, String, List<String>) -> List<SysMenu>)? = null
    var delete: (suspend (String, String) -> Unit)? = null
  }

  private fun vm(f: FakeSeams.() -> Unit = {}): MenusViewModel {
    val fake = FakeSeams().apply(f)
    return MenusViewModel(
        MenusViewModel.Seams(
            listMenus = fake.list ?: { _ -> emptyList() },
            createMenu =
                fake.create
                    ?: { _, r ->
                      menu("00000000-0000-0000-0000-0000000000ff", title = r.title)
                    },
            updateMenu = fake.update ?: { _, id, req -> menu(id, title = req.title ?: "菜单") },
            moveMenu =
                fake.move
                    ?: { _, id, parentId ->
                      menu(id).copy(parentId = Uuid.parse(parentId ?: ROOT_SENTINEL))
                    },
            reorderMenus = fake.reorder ?: { _, _, _ -> emptyList() },
            deleteMenu = fake.delete ?: { _, _ -> },
        ),
    )
  }

  private fun failedMessage(phase: MenusViewModel.Phase?): String =
      (phase as MenusViewModel.Phase.Failed).message

  @Test
  fun `load stores clientId and menus`() = runBlocking {
    val m = vm { list = { _ -> listOf(menu("00000000-0000-0000-0000-000000000001")) } }

    val ok = m.load("lab-management")

    assertTrue(ok)
    assertEquals("lab-management", m.clientId)
    assertEquals(
        listOf("00000000-0000-0000-0000-000000000001"),
        m.menus.value.map { it.id.toString() },
    )
    assertEquals(MenusViewModel.Phase.Idle, m.phase.value)
  }

  @Test
  fun `load failure reports and keeps menus empty`() = runBlocking {
    val m = vm { list = { _ -> throw AuthError.Server("服务器错误（HTTP 500）") } }

    val ok = m.load("lab-management")

    assertFalse(ok)
    assertEquals("服务器错误（HTTP 500）", failedMessage(m.phase.value))
    assertTrue(m.menus.value.isEmpty())
  }

  @Test
  fun `ops before load fail with no-client message`() = runBlocking {
    val m = vm()
    val request = CreateSysMenuRequest(title = "新菜单", type = SysMenuType.menu)

    assertFalse(m.create(request))
    assertFalse(m.update("00000000-0000-0000-0000-000000000001", UpdateSysMenuRequest()))
    assertFalse(m.move("00000000-0000-0000-0000-000000000001", null))
    assertFalse(m.reorder(ROOT_SENTINEL, listOf("x")))
    assertFalse(m.deleteMenu("00000000-0000-0000-0000-000000000001"))
    repeat(5) { assertEquals(MenusViewModel.NO_CLIENT_MESSAGE, failedMessage(m.phase.value)) }
  }

  @Test
  fun `create appends row in place`() = runBlocking {
    val created = menu("00000000-0000-0000-0000-000000000002", title = "新菜单")
    val m = vm {
      list = { _ -> listOf(menu("00000000-0000-0000-0000-000000000001")) }
      create = { _, _ -> created }
    }
    m.load("lab-management")

    val ok = m.create(CreateSysMenuRequest(title = "新菜单", type = SysMenuType.menu))

    assertTrue(ok)
    assertEquals(2, m.menus.value.size)
    assertEquals("新菜单", m.menus.value.last().title)
  }

  @Test
  fun `update replaces row in place by id`() = runBlocking {
    val original = menu("00000000-0000-0000-0000-000000000001", title = "旧名")
    val updated = menu("00000000-0000-0000-0000-000000000001", title = "新名")
    val m = vm {
      list = { _ -> listOf(original) }
      update = { _, _, _ -> updated }
    }
    m.load("lab-management")

    val ok = m.update("00000000-0000-0000-0000-000000000001", UpdateSysMenuRequest(title = "新名"))

    assertTrue(ok)
    assertEquals(1, m.menus.value.size)
    assertEquals("新名", m.menus.value[0].title)
  }

  @Test
  fun `move replaces row with server-returned parent`() = runBlocking {
    val moved =
        menu("00000000-0000-0000-0000-000000000001")
            .copy(parentId = Uuid.parse("00000000-0000-0000-0000-000000000009"))
    val m = vm {
      list = { _ -> listOf(menu("00000000-0000-0000-0000-000000000001")) }
      move = { _, _, _ -> moved }
    }
    m.load("lab-management")

    val ok = m.move("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000009")

    assertTrue(ok)
    assertEquals("00000000-0000-0000-0000-000000000009", m.menus.value[0].parentId.toString())
  }

  @Test
  fun `reorder re-sorts sortOrder locally ignoring server response`() = runBlocking {
    val a = menu("00000000-0000-0000-0000-000000000001", sortOrder = 1)
    val b = menu("00000000-0000-0000-0000-000000000002", sortOrder = 2)
    val m = vm {
      list = { _ -> listOf(a, b) }
      reorder = { _, _, _ -> emptyList() }
    }
    m.load("lab-management")

    val ok =
        m.reorder(
            ROOT_SENTINEL,
            listOf("00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000001"),
        )

    assertTrue(ok)
    assertEquals(
        1,
        m.menus.value
            .first { it.id.toString() == "00000000-0000-0000-0000-000000000002" }
            .sortOrder,
    )
    assertEquals(
        2,
        m.menus.value
            .first { it.id.toString() == "00000000-0000-0000-0000-000000000001" }
            .sortOrder,
    )
    assertEquals(
        listOf("00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000001"),
        m.tree.map { it.menu.id.toString() },
    )
  }

  @Test
  fun `delete removes row in place`() = runBlocking {
    val m = vm {
      list = { _ ->
        listOf(
            menu("00000000-0000-0000-0000-000000000001"),
            menu("00000000-0000-0000-0000-000000000002"),
        )
      }
    }
    m.load("lab-management")

    val ok = m.deleteMenu("00000000-0000-0000-0000-000000000001")

    assertTrue(ok)
    assertEquals(
        listOf("00000000-0000-0000-0000-000000000002"),
        m.menus.value.map { it.id.toString() },
    )
  }

  @Test
  fun `op failure leaves list untouched and reports`() = runBlocking {
    val m = vm { delete = { _, _ -> throw AuthError.Unreachable(Exception("connection reset")) } }
    m.load("lab-management")
    val before = m.menus.value

    val ok = m.deleteMenu("00000000-0000-0000-0000-000000000001")

    assertFalse(ok)
    assertEquals(before, m.menus.value)
    assertTrue(failedMessage(m.phase.value).contains("无法连接"))
  }

  @Test
  fun `tree exposes nested nodes`() = runBlocking {
    val parent = "00000000-0000-0000-0000-000000000001"
    val m = vm {
      list = { _ ->
        listOf(
            menu(parent, title = "系统"),
            menu("00000000-0000-0000-0000-000000000002", title = "用户")
                .copy(parentId = Uuid.parse(parent)),
        )
      }
    }
    m.load("lab-management")

    val tree: List<MenuNode> = m.tree

    assertEquals(listOf("用户"), tree[0].children.map { it.menu.title })
  }
}
