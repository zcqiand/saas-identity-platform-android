@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.xr.harness.app.adminClientsSeams
import io.xr.harness.app.menusCreateSeam
import io.xr.harness.app.menusDeleteSeam
import io.xr.harness.app.menusListSeam
import io.xr.harness.app.menusMoveSeam
import io.xr.harness.app.menusReorderSeam
import io.xr.harness.app.menusUpdateSeam
import io.xr.harness.core.MenuNode
import io.xr.harness.core.ROOT_SENTINEL
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.ui.AdminClientsViewModel
import io.xr.harness.ui.MenusViewModel
import kotlinx.coroutines.launch
import saas.identity.shared.models.CreateSysMenuRequest
import saas.identity.shared.models.OAuthClient
import saas.identity.shared.models.SysMenu
import saas.identity.shared.models.SysMenuType

/**
 * 菜单管理屏（swift MenusAdminView 同构，M04.F04）：client 选择器（AdminClientsViewModel 全量清单）+ 树（编辑/上移/下移/删除）+
 * 新建到根 + 编辑/删除对话框（对话框族在 MenuDialogs.kt）。 上下移仅非根节点提供（reorder 以父菜单 id 寻址）；兄弟段计算在 UI 层（filter 同父 +
 * sortOrder/title 排序 + 交换提交）。
 */
@Composable
fun MenusScreen(onBack: () -> Unit, store: SessionStore, tokens: TokenHolder) {
  val scope = rememberCoroutineScope()
  val st = rememberMenusState(store, tokens)
  val clients by st.clientsVm.clients.collectAsState()
  val menus by st.menusVm.menus.collectAsState()
  val phase by st.menusVm.phase.collectAsState()
  val busy = phase is MenusViewModel.Phase.Busy
  var editing by remember { mutableStateOf<SysMenu?>(null) }
  var deleting by remember { mutableStateOf<SysMenu?>(null) }
  val actions =
      menuRowActions(
          menus = menus,
          onEdit = { editing = it },
          onDelete = { deleting = it },
          onMove = { m, ids ->
            scope.launch { st.menusVm.reorder(m.parentId?.toString() ?: ROOT_SENTINEL, ids) }
          },
      )

  Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onBack, enabled = !busy) { Text(text = "返回") }
      Text(text = "菜单管理", style = MaterialTheme.typography.headlineMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))
    MenusControlsSection(
        picker =
            MenusPickerState(
                clients = clients,
                selected = st.selectedClient,
                onSelect = { c ->
                  st.selectClient(c)
                  scope.launch { st.menusVm.load(c) }
                },
            ),
        busy = busy,
        failedMessage = (phase as? MenusViewModel.Phase.Failed)?.message,
        onCreate = { t, onDone ->
          scope.launch {
            if (st.menusVm.create(CreateSysMenuRequest(title = t, type = SysMenuType.menu))) {
              onDone()
            }
          }
        },
    )
    Spacer(modifier = Modifier.height(8.dp))
    MenusTree(tree = st.menusVm.tree, menus = menus, busy = busy, actions = actions)
  }

  editing?.let { target ->
    MenuEditDialogHost(
        target = target,
        menus = menus,
        vm = st.menusVm,
        busy = busy,
        onDismiss = { editing = null },
    )
  }
  deleting?.let { target ->
    MenuDeleteDialogHost(
        target = target,
        vm = st.menusVm,
        busy = busy,
        onDismiss = { deleting = null },
    )
  }
}

/** 选择器束（数据 + 选中回调，束形降参）。 */
private data class MenusPickerState(
    val clients: List<OAuthClient>,
    val selected: String?,
    val onSelect: (String) -> Unit,
)

/** 树行动作束（回调上抛 MenusScreen 持有对话框状态）。 */
private data class MenuRowActions(
    val onEdit: (SysMenu) -> Unit,
    val onMoveUp: (SysMenu) -> Unit,
    val onMoveDown: (SysMenu) -> Unit,
    val onDelete: (SysMenu) -> Unit,
)

/** 动作束工厂：兄弟段交换计算在此，提交经 onMove（父菜单 id 寻址 reorder）。 */
private fun menuRowActions(
    menus: List<SysMenu>,
    onEdit: (SysMenu) -> Unit,
    onDelete: (SysMenu) -> Unit,
    onMove: (SysMenu, List<String>) -> Unit,
): MenuRowActions =
    MenuRowActions(
        onEdit = onEdit,
        onMoveUp = { m -> swappedSiblingIds(menus, m.id.toString(), -1)?.let { onMove(m, it) } },
        onMoveDown = { m -> swappedSiblingIds(menus, m.id.toString(), 1)?.let { onMove(m, it) } },
        onDelete = onDelete,
    )

/** client 选择器（AdminClients 全量清单，不传分页；父级 Picker 同款交互）。 */
@Composable
private fun MenusClientPicker(
    clients: List<OAuthClient>,
    selected: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    TextButton(onClick = { open = true }, enabled = !busy) {
      Text(text = "客户端：${selected ?: "加载中…"}")
    }
    DropdownMenu(expanded = open && clients.isNotEmpty(), onDismissRequest = { open = false }) {
      clients.forEach { c ->
        DropdownMenuItem(
            text = { Text(text = "${c.clientName}（${c.clientId}）") },
            onClick = {
              open = false
              onSelect(c.clientId)
            },
        )
      }
    }
  }
}

/** 树递归行：标题 + 类型/排序副行 + 编辑/上移/下移/删除（根节点不提供上下移）。 */
@Composable
private fun menuNodeRows(
    node: MenuNode,
    menus: List<SysMenu>,
    depth: Int,
    busy: Boolean,
    actions: MenuRowActions,
) {
  val m = node.menu
  Column(modifier = Modifier.fillMaxWidth().padding(start = (depth * 16).dp, top = 4.dp)) {
    Text(text = m.title, style = MaterialTheme.typography.bodyLarge)
    Text(
        text = "${m.type} · 排序 ${m.sortOrder}" + if (m.status == 1) "" else " · 停用",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      TextButton(onClick = { actions.onEdit(m) }, enabled = !busy) { Text(text = "编辑") }
      if (parentKeyOf(m).isNotEmpty()) {
        TextButton(onClick = { actions.onMoveUp(m) }, enabled = !busy) { Text(text = "上移") }
        TextButton(onClick = { actions.onMoveDown(m) }, enabled = !busy) { Text(text = "下移") }
      }
      TextButton(onClick = { actions.onDelete(m) }, enabled = !busy) { Text(text = "删除") }
    }
  }
  node.children.forEach { child -> menuNodeRows(child, menus, depth + 1, busy, actions) }
}

/** 控制段：client 选择器 + 新建到根 + 操作失败红字（新建标题局部持有，成功经 onDone 清空）。 */
@Composable
private fun MenusControlsSection(
    picker: MenusPickerState,
    busy: Boolean,
    failedMessage: String?,
    onCreate: (title: String, onDone: () -> Unit) -> Unit,
) {
  var title by remember { mutableStateOf("") }
  MenusClientPicker(
      clients = picker.clients,
      selected = picker.selected,
      busy = busy,
      onSelect = picker.onSelect,
  )
  Spacer(modifier = Modifier.height(8.dp))
  MenusCreateRow(
      title = title,
      busy = busy,
      onTitleChange = { title = it },
      onCreate = { onCreate(title) { title = "" } },
  )
  failedMessage?.let {
    Spacer(modifier = Modifier.height(8.dp))
    Text(text = it, color = MaterialTheme.colorScheme.error)
  }
}

/** 新建行：标题输入 + 建到根（parentId 缺省 = 根）。 */
@Composable
private fun MenusCreateRow(
    title: String,
    busy: Boolean,
    onTitleChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
        value = title,
        onValueChange = onTitleChange,
        label = { Text(text = "新菜单标题（建到根）") },
        modifier = Modifier.weight(1f),
        singleLine = true,
    )
    Button(onClick = onCreate, enabled = !busy && title.isNotBlank()) { Text(text = "新建") }
  }
}

/** 树只读渲染（递归缩进；编辑/移动/删除回调上抛）。 */
@Composable
private fun MenusTree(
    tree: List<MenuNode>,
    menus: List<SysMenu>,
    busy: Boolean,
    actions: MenuRowActions,
) {
  LazyColumn(modifier = Modifier.fillMaxWidth()) {
    tree.forEach { root ->
      item(key = root.menu.id.toString()) { menuNodeRows(root, menus, 0, busy, actions) }
    }
  }
}

/** 菜单页控制器束：双 VM 接线 + 首选 client 自动装载。 */
private class MenusState(
    val clientsVm: AdminClientsViewModel,
    val menusVm: MenusViewModel,
    val selectedClient: String?,
    val selectClient: (String) -> Unit,
)

@Composable
private fun rememberMenusState(store: SessionStore, tokens: TokenHolder): MenusState {
  val clientsVm = remember { AdminClientsViewModel(adminClientsSeams(store, tokens)) }
  val menusVm = remember {
    MenusViewModel(
        MenusViewModel.Seams(
            listMenus = menusListSeam(store, tokens),
            createMenu = menusCreateSeam(store, tokens),
            updateMenu = menusUpdateSeam(store, tokens),
            moveMenu = menusMoveSeam(store, tokens),
            reorderMenus = menusReorderSeam(store, tokens),
            deleteMenu = menusDeleteSeam(store, tokens),
        ),
    )
  }
  var selectedClient by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    clientsVm.load()
    clientsVm.clients.value.firstOrNull()?.let { c ->
      selectedClient = c.clientId
      menusVm.load(c.clientId)
    }
  }
  return MenusState(clientsVm, menusVm, selectedClient, { c -> selectedClient = c })
}

/** 父级选项键（"" = 根哨兵，其余 = parentId 字符串；null/零值 = 根；编辑对话框与移动门共用口径）。 */
internal fun parentKeyOf(menu: SysMenu): String {
  val pid = menu.parentId?.toString()
  return if (pid == null || pid == ROOT_SENTINEL) "" else pid
}

/** 同父兄弟段（null 同段 = 根；sortOrder/title 排序，buildMenuTree 同口径）交换 delta 位后的全量 id 序列；越界为 null。 */
private fun swappedSiblingIds(
    menus: List<SysMenu>,
    menuId: String,
    delta: Int,
): List<String>? {
  val parentId = menus.firstOrNull { it.id.toString() == menuId }?.parentId
  val siblings =
      menus
          .filter { it.parentId == parentId }
          .sortedWith(compareBy({ it.sortOrder }, { it.title }))
          .map { it.id.toString() }
  val i = siblings.indexOf(menuId)
  val j = i + delta
  val swappable = i >= 0 && j >= 0 && j < siblings.size
  return if (swappable) {
    siblings.toMutableList().apply {
      this[i] = siblings[j]
      this[j] = menuId
    }
  } else {
    null
  }
}
