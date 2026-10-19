@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness.ui

import io.xr.harness.core.AuthError
import io.xr.harness.core.MenuNode
import io.xr.harness.core.buildMenuTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import saas.identity.shared.models.CreateSysMenuRequest
import saas.identity.shared.models.SysMenu
import saas.identity.shared.models.UpdateSysMenuRequest

/**
 * 菜单管理 VM（swift MenusViewModel 同构，M04.F04 lean slice）：6 缝注入 + 原地变更语义。 语义钉死：成功就地对列表
 * append/replace/remove， reorder 只按本地重排 sortOrder（服务端回空数组）；失败仅红字，列表不动可重试。move/reorder 的 UI 归后刀。
 */
class MenusViewModel(internal val seams: Seams = Seams()) {
  data class Seams(
      val listMenus: suspend (String) -> List<SysMenu> = { _ ->
        throw IllegalStateException("seam 未注入: listMenus")
      },
      val createMenu: suspend (String, CreateSysMenuRequest) -> SysMenu = { _, _ ->
        throw IllegalStateException("seam 未注入: createMenu")
      },
      val updateMenu: suspend (String, String, UpdateSysMenuRequest) -> SysMenu = { _, _, _ ->
        throw IllegalStateException("seam 未注入: updateMenu")
      },
      val moveMenu: suspend (String, String, String?) -> SysMenu = { _, _, _ ->
        throw IllegalStateException("seam 未注入: moveMenu")
      },
      val reorderMenus: suspend (String, String, List<String>) -> List<SysMenu> = { _, _, _ ->
        throw IllegalStateException("seam 未注入: reorderMenus")
      },
      val deleteMenu: suspend (String, String) -> Unit = { _, _ ->
        throw IllegalStateException("seam 未注入: deleteMenu")
      },
  )

  sealed interface Phase {
    data object Idle : Phase

    data object Busy : Phase

    data class Failed(val message: String) : Phase
  }

  /** 未 load 就操作的统一失败文案（swift "no client selected" 同位）。 */
  companion object {
    const val NO_CLIENT_MESSAGE: String = "未选择客户端"
  }

  private val _phase = MutableStateFlow<Phase>(Phase.Idle)
  val phase: StateFlow<Phase> = _phase.asStateFlow()

  private val _menus = MutableStateFlow<List<SysMenu>>(emptyList())
  val menus: StateFlow<List<SysMenu>> = _menus.asStateFlow()

  /** 当前 client（load 时设定；全部操作缝经它寻址）。 */
  var clientId: String? = null
    private set

  /** 树视图（零值 UUID = 根，sortOrder 升序）。 */
  val tree: List<MenuNode>
    get() = buildMenuTree(_menus.value)

  /** 装载某 client 的平铺菜单清单。 */
  suspend fun load(targetClientId: String): Boolean {
    _phase.value = Phase.Busy
    return runOp {
      val list = seams.listMenus(targetClientId)
      clientId = targetClientId
      _menus.value = list
    }
  }

  /** 新建菜单（parentId 缺省 = 根）。成功就地追加，不重拉。 */
  suspend fun create(request: CreateSysMenuRequest): Boolean = withClient { clientId ->
    val created = seams.createMenu(clientId, request)
    _menus.value = _menus.value + created
  }

  /** 更新菜单（部分请求）。成功就地把该行原位替换。 */
  suspend fun update(menuId: String, request: UpdateSysMenuRequest): Boolean =
      withClient { clientId ->
        val updated = seams.updateMenu(clientId, menuId, request)
        replaceRow(menuId, updated)
      }

  /** 移动到新父（null = 回根）。成功以服务端返回的新 parentId 原位替换。 */
  suspend fun move(menuId: String, parentId: String?): Boolean = withClient { clientId ->
    val moved = seams.moveMenu(clientId, menuId, parentId)
    replaceRow(menuId, moved)
  }

  /** 重排一个兄弟段（提交全量 orderedMenuIds）。成功后只按本地 index 重写 sortOrder，不重拉。 */
  suspend fun reorder(parentMenuId: String, orderedMenuIds: List<String>): Boolean =
      withClient { clientId ->
        seams.reorderMenus(clientId, parentMenuId, orderedMenuIds)
        val current = _menus.value.toMutableList()
        orderedMenuIds.forEachIndexed { index, id ->
          val row = current.indexOfFirst { it.id.toString() == id }
          if (row >= 0) current[row] = current[row].copy(sortOrder = index + 1)
        }
        _menus.value = current
      }

  /** 删除菜单。成功就地把该行移除。 */
  suspend fun deleteMenu(menuId: String): Boolean = withClient { clientId ->
    seams.deleteMenu(clientId, menuId)
    _menus.value = _menus.value.filterNot { it.id.toString() == menuId }
  }

  private suspend fun withClient(block: suspend (String) -> Unit): Boolean {
    val current = clientId
    return if (current == null) {
      _phase.value = Phase.Failed(NO_CLIENT_MESSAGE)
      false
    } else {
      runOp { block(current) }
    }
  }

  /** 统一操作护栏：busy → 成功 Idle / 失败 Failed(message)，列表不动可重试（try 表达式零 return）。 */
  private suspend fun runOp(block: suspend () -> Unit): Boolean =
      try {
        block()
        _phase.value = Phase.Idle
        true
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _phase.value = Phase.Failed(messageOf(e))
        false
      }

  private fun replaceRow(menuId: String, updated: SysMenu) {
    _menus.value = _menus.value.map { if (it.id.toString() == menuId) updated else it }
  }

  private fun messageOf(e: Exception): String =
      if (e is AuthError) e.message ?: "请求失败" else "请求失败（${e.message}）"
}
