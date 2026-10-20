@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.core

import kotlin.uuid.Uuid
import saas.identity.shared.models.SysMenu

/** 根哨兵：零值 UUID（swift MenuTree 同款口径）；wire 上根节点缺省 parentId（null）同为根——2026-10-20 live 冒烟实证。 */
const val ROOT_SENTINEL = "00000000-0000-0000-0000-000000000000"

/** 树节点：menu + 有序 children（Compose 递归渲染用）。 */
data class MenuNode(val menu: SysMenu, val children: List<MenuNode>)

/**
 * 组树（swift buildMenuTree 同构）：零值 UUID 或缺省 null = 根；兄弟 sortOrder 升序、tie-break
 * title（确定性渲染）；孤儿挂根，不让数据无声消失。
 */
fun buildMenuTree(menus: List<SysMenu>): List<MenuNode> {
  val rootSentinel = Uuid.parse(ROOT_SENTINEL)
  val byId = menus.associateBy { it.id }
  val childrenByParent = mutableMapOf<Uuid, MutableList<SysMenu>>()
  val roots = mutableListOf<SysMenu>()
  for (menu in menus) {
    val pid = menu.parentId
    if (pid == null || pid == rootSentinel || !byId.containsKey(pid)) {
      roots.add(menu)
    } else {
      childrenByParent.getOrPut(pid) { mutableListOf() }.add(menu)
    }
  }

  fun sort(list: List<SysMenu>): List<SysMenu> =
      list.sortedWith(compareBy({ it.sortOrder }, { it.title }))

  fun node(menu: SysMenu): MenuNode =
      MenuNode(menu, sort(childrenByParent[menu.id] ?: emptyList()).map { node(it) })

  return sort(roots).map { node(it) }
}
