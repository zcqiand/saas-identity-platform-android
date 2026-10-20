@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.xr.harness.core.AuthError
import io.xr.harness.ui.RolesViewModel
import kotlinx.coroutines.launch
import saas.identity.shared.models.OAuthClient
import saas.identity.shared.models.SysMenu
import saas.identity.shared.models.SysRole

/**
 * 角色对话框族（swift RoleCreateSheet / RoleDetailAdminView / RoleMenuGrantsSection 同构，M00.F03/M00.F04）。
 * 对话框本体只做绑定与禁点；提交回调由 RolesScreen 持 VM 执行，成功才关对话框（失败红字留在屏上可重试）。 菜单清单复用 REQ-008 menusListSeam
 * 缝（role.clientId 定源，Q4）；勾选态本地 Set（menuIds 后端顺序不保证，探针实证）。
 */

/**
 * 新建角色：clientId Picker 必选（Q3：复用 admin 面清单，不发明自由输入）/roleCode/roleName 必填。重名 roleCode 后端 500 空
 * body（Q1）——红字如实呈现。
 */
@Composable
internal fun RoleCreateDialog(
    clients: List<OAuthClient>,
    busy: Boolean,
    onCreate: (clientId: String, roleCode: String, roleName: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
  var clientId by remember { mutableStateOf("") }
  var roleCode by remember { mutableStateOf("") }
  var roleName by remember { mutableStateOf("") }
  var description by remember { mutableStateOf("") }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "新建角色") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          RoleClientPicker(clients = clients, selected = clientId, onPick = { clientId = it })
          OutlinedTextField(
              value = roleCode,
              onValueChange = { roleCode = it },
              label = { Text(text = "角色编码") },
              singleLine = true,
          )
          OutlinedTextField(
              value = roleName,
              onValueChange = { roleName = it },
              label = { Text(text = "角色名") },
              singleLine = true,
          )
          OutlinedTextField(
              value = description,
              onValueChange = { description = it },
              label = { Text(text = "描述（可空）") },
              singleLine = true,
          )
        }
      },
      confirmButton = {
        Button(
            onClick = { onCreate(clientId, roleCode, roleName, description) },
            enabled =
                !busy && clientId.isNotEmpty() && roleCode.isNotBlank() && roleName.isNotBlank(),
        ) {
          Text(text = "创建")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/** 编辑对话框：roleName/description PATCH partial + 状态启停；roleCode/tenantId 只读（契约不可改口径）。 */
@Composable
internal fun RoleEditDialog(
    role: SysRole,
    busy: Boolean,
    onSave: (roleName: String, description: String, status: Int) -> Unit,
    onDismiss: () -> Unit,
) {
  var roleName by remember(role.id) { mutableStateOf(role.roleName) }
  var description by remember(role.id) { mutableStateOf(role.description.orEmpty()) }
  var status by remember(role.id) { mutableStateOf(if (role.status == 1) 1 else 0) }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "编辑：${role.roleCode}") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
              text = "编码：${role.roleCode}（不可改）",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
              value = roleName,
              onValueChange = { roleName = it },
              label = { Text(text = "角色名") },
              singleLine = true,
          )
          OutlinedTextField(
              value = description,
              onValueChange = { description = it },
              label = { Text(text = "描述（可空）") },
              singleLine = true,
          )
          PickerRow(
              label = "状态：${if (status == 1) "启用" else "停用"}",
              enabled = !busy,
              options = listOf(1 to "启用", 0 to "停用"),
              onPick = { status = it },
          )
        }
      },
      confirmButton = {
        Button(
            onClick = { onSave(roleName, description, status) },
            enabled = !busy && roleName.isNotBlank(),
        ) {
          Text(text = "保存")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/** 删除确认（危险操作：删除后引用该角色的成员绑定由后端规则裁决）。 */
@Composable
internal fun RoleDeleteDialog(
    role: SysRole,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "删除角色：${role.roleCode}") },
      text = { Text(text = "危险操作：删除后引用该角色的成员绑定由后端规则裁决，不可恢复。") },
      confirmButton = { Button(onClick = onConfirm, enabled = !busy) { Text(text = "删除") } },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/**
 * 菜单授权对话框：该 client 菜单多选勾选 + 保存授权（PUT 全量替换，提交按菜单清单序稳定排列，未勾选 = 移除）+ 清空（DELETE 二次确认）。 id 全来自清单返回值——UI
 * 不可能产生未知 id（后端 400 FK 路径不可达，AC-4）；授权网络态在 RolesViewModel.grants （Q1），失败红字经 vm.phase 由本对话框呈现。
 */
@Composable
internal fun RoleGrantsDialog(
    role: SysRole,
    vm: RolesViewModel,
    listMenus: suspend (String) -> List<SysMenu>,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val phase by vm.phase.collectAsState()
  var state by remember { mutableStateOf(GrantsUiState()) }
  var seeded by remember { mutableStateOf(false) }

  GrantsLoadEffect(
      role = role,
      vm = vm,
      listMenus = listMenus,
      onLoaded = { menus, error ->
        if (menus != null) state = state.copy(menus = menus)
        if (error != null) state = state.copy(menusError = error)
      },
      onSeeded = {
        state = state.copy(selected = it)
        seeded = true
      },
  )

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "菜单授权：${role.roleCode}") },
      text = {
        GrantsBody(
            state = state,
            busy = busy,
            failed = phase as? RolesViewModel.Phase.Failed,
            onToggle = { id, on ->
              state = state.copy(selected = if (on) state.selected + id else state.selected - id)
            },
        )
      },
      confirmButton = {
        GrantsConfirmSection(
            role = role,
            vm = vm,
            state = state,
            busy = busy,
            onSelected = { state = state.copy(selected = it) },
        )
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "关闭") } },
  )
}

/** 授权对话框本地态束（菜单清单 + 清单加载红字 + 勾选集——单 mutableState 便于子段整束传递）。 */
private data class GrantsUiState(
    val menus: List<SysMenu> = emptyList(),
    val menusError: String? = null,
    val selected: Set<String> = emptySet(),
)

/** 授权对话框确认区：保存（PUT 全量替换，提交按菜单清单序稳定排列）+ 清空入口 + 清空二次确认 host。 */
@Composable
private fun GrantsConfirmSection(
    role: SysRole,
    vm: RolesViewModel,
    state: GrantsUiState,
    busy: Boolean,
    onSelected: (Set<String>) -> Unit,
) {
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var confirmClear by remember { mutableStateOf(false) }
  Column {
    Button(
        onClick = {
          scope.launch {
            val ordered =
                state.menus.map { it.id.toString() }.filter { state.selected.contains(it) }
            if (vm.saveGrants(roleID = role.id, menuIds = ordered)) {
              // 勾选态以响应为准（顺序不保证照存不重排）。
              onSelected(vm.grants.value?.menuIds?.toSet() ?: emptySet())
            }
          }
        },
        enabled = !busy && state.menus.isNotEmpty(),
    ) {
      Text(text = "保存授权")
    }
    TextButton(
        onClick = { confirmClear = true },
        enabled = !busy && (vm.grants.value?.menuIds?.isEmpty() == false),
    ) {
      Text(text = "清空授权", color = MaterialTheme.colorScheme.error)
    }
  }
  if (confirmClear) {
    RoleClearConfirmDialog(
        role = role,
        busy = busy,
        onConfirm = {
          scope.launch {
            if (vm.clearGrants(roleID = role.id)) {
              onSelected(emptySet())
              confirmClear = false
            }
          }
        },
        onDismiss = { confirmClear = false },
    )
  }
}

/** 授权态装载段：菜单清单进段拉一次（失败红字不静默）+ 授权态播种（只播一次，防重播盖掉未保存勾选）。 */
@Composable
private fun GrantsLoadEffect(
    role: SysRole,
    vm: RolesViewModel,
    listMenus: suspend (String) -> List<SysMenu>,
    onLoaded: (menus: List<SysMenu>?, error: String?) -> Unit,
    onSeeded: (Set<String>) -> Unit,
) {
  LaunchedEffect(role.id) {
    // 菜单清单：进段拉一次（client 定源自 role.clientId）；wire guard 已把传输层异常归一
    // AuthError——此处收窄捕获即完整失败面。
    try {
      onLoaded(listMenus(role.clientId), null)
    } catch (e: AuthError) {
      onLoaded(null, "菜单清单加载失败：${e.message ?: "未知错误"}")
    }
    // 授权态播种：loadGrants 成功才落地（失败红字保留，不清勾选）；只播一次由父层 seeded 标记防重播。
    if (vm.loadGrants(roleID = role.id)) {
      onSeeded(vm.grants.value?.menuIds?.toSet() ?: emptySet())
    }
  }
}

/** 授权对话框正文段：错误/空态红字 + 菜单勾选清单 + VM 失败红字。 */
@Composable
private fun GrantsBody(
    state: GrantsUiState,
    busy: Boolean,
    failed: RolesViewModel.Phase.Failed?,
    onToggle: (String, Boolean) -> Unit,
) {
  Column(
      modifier = Modifier.verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    state.menusError?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
    if (state.menus.isEmpty() && state.menusError == null) {
      Text(text = "该 client 无菜单可授", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // 菜单勾选清单（勾选态本地 Set——menuIds 后端顺序不保证，消费端 Set 语义）。
    state.menus.forEach { menu ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = menu.id.toString() in state.selected,
            onCheckedChange = { on -> onToggle(menu.id.toString(), on) },
            enabled = !busy,
        )
        Column {
          Text(text = menu.title)
          Text(
              text = menu.path ?: menu.id.toString(),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    failed?.let { Text(text = it.message, color = MaterialTheme.colorScheme.error) }
  }
}

/** 清空授权二次确认体（危险操作：清空后该角色失去所有菜单访问，可重新勾选保存恢复）。 */
@Composable
private fun RoleClearConfirmDialog(
    role: SysRole,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "清空角色「${role.roleCode}」的全部菜单授权？") },
      text = { Text(text = "清空后该角色失去所有菜单访问，可重新勾选保存恢复。") },
      confirmButton = { Button(onClick = onConfirm, enabled = !busy) { Text(text = "清空") } },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/** 新建对话框的 client Picker（admin 面清单，Q3）。 */
@Composable
private fun RoleClientPicker(
    clients: List<OAuthClient>,
    selected: String,
    onPick: (String) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  androidx.compose.foundation.layout.Box {
    TextButton(onClick = { open = true }) { Text(text = "Client：${selected.ifEmpty { "必选" }}") }
    androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      clients.forEach { c ->
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(text = "${c.clientName}（${c.clientId}）") },
            onClick = {
              open = false
              onPick(c.clientId)
            },
        )
      }
    }
  }
}

/** 通用单选行（状态 Picker 共用交互）。 */
@Composable
private fun <T> PickerRow(
    label: String,
    enabled: Boolean,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  androidx.compose.foundation.layout.Box {
    TextButton(onClick = { open = true }, enabled = enabled) { Text(text = label) }
    androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      options.forEach { (value, text) ->
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(text = text) },
            onClick = {
              open = false
              onPick(value)
            },
        )
      }
    }
  }
}
