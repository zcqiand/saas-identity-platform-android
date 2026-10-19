@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.core

import java.time.OffsetDateTime
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import saas.identity.shared.models.SysMenu
import saas.identity.shared.models.SysMenuType

/** buildMenuTree 纯函数单测（swift MenuTree.swift 同构）：零值 UUID 根、sortOrder/title 排序、孤儿挂根。 */
class MenuTreeTest {

  private fun menu(
      id: String,
      parentId: String,
      title: String,
      sortOrder: Int = 1,
  ): SysMenu =
      SysMenu(
          id = Uuid.parse(id),
          clientId = "lab-management",
          parentId = Uuid.parse(parentId),
          title = title,
          type = SysMenuType.menu,
          sortOrder = sortOrder,
          status = 1,
          createdAt = OffsetDateTime.parse("2026-10-19T00:00:00Z"),
      )

  @Test
  fun `zero-uuid parent and orphan both hang at root`() {
    val tree =
        buildMenuTree(
            listOf(
                menu("00000000-0000-0000-0000-000000000001", ROOT_SENTINEL, "系统"),
                menu(
                    "00000000-0000-0000-0000-000000000002",
                    "00000000-0000-0000-0000-000000009999",
                    "孤儿",
                    sortOrder = 2,
                ),
            ),
        )

    assertEquals(listOf("系统", "孤儿"), tree.map { it.menu.title }) // 孤儿挂根，不消失
    assertTrue(tree[0].children.isEmpty())
  }

  @Test
  fun `children nest under parent and sort by sortOrder then title`() {
    val parent = "00000000-0000-0000-0000-000000000001"
    val tree =
        buildMenuTree(
            listOf(
                menu(parent, ROOT_SENTINEL, "系统"),
                menu("00000000-0000-0000-0000-000000000002", parent, "b", sortOrder = 2),
                menu("00000000-0000-0000-0000-000000000003", parent, "甲", sortOrder = 1),
                menu("00000000-0000-0000-0000-000000000004", parent, "b-two", sortOrder = 2),
            ),
        )

    assertEquals(1, tree.size)
    assertEquals(
        listOf("甲", "b", "b-two"), // sortOrder 升序，同序 tie-break title（ASCII 避开中文码点序）
        tree[0].children.map { it.menu.title },
    )
  }

  @Test
  fun `grandchildren nest recursively`() {
    val parent = "00000000-0000-0000-0000-000000000001"
    val child = "00000000-0000-0000-0000-000000000002"
    val tree =
        buildMenuTree(
            listOf(
                menu(parent, ROOT_SENTINEL, "系统"),
                menu(child, parent, "用户管理"),
                menu("00000000-0000-0000-0000-000000000003", child, "新增按钮"),
            ),
        )

    assertEquals(listOf("新增按钮"), tree[0].children[0].children.map { it.menu.title })
  }

  @Test
  fun `empty list yields empty tree`() {
    assertEquals(emptyList<MenuNode>(), buildMenuTree(emptyList()))
  }

  companion object {
    /** 根哨兵：零值 UUID（swift MenuTree 同款口径，CRUD 平铺列表根 parentId）。 */
    const val ROOT_SENTINEL = "00000000-0000-0000-0000-000000000000"
  }
}
