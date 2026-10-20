@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness

import androidx.compose.foundation.layout.Arrangement
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
import io.xr.harness.app.menusListSeam
import io.xr.harness.app.rolesSeams
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.ui.AdminClientsViewModel
import io.xr.harness.ui.RolesViewModel
import kotlinx.coroutines.launch
import saas.identity.shared.models.OAuthClient
import saas.identity.shared.models.SysRole

/**
 * 角色管理屏（swift RolesAdminView 同构，M00.F03 + M00.F04，REQ-2026-010/011）：角色列表（client Picker 本地过滤，全量拉取
 * 后前端滤）+ 行编辑/启停/删除 + 新建对话框 + 菜单授权对话框（勾选 = 全量替换，未勾选 = 移除；对话框族在 RolesDialogs.kt）。 数据流全在
 * RolesViewModel，本层只渲染与转发。
 */
@Composable
fun RolesScreen(onBack: () -> Unit, store: SessionStore, tokens: TokenHolder) {
  val scope = rememberCoroutineScope()
  val clientsVm = remember { AdminClientsViewModel(adminClientsSeams(store, tokens)) }
  val vm = rememberRolesViewModel(store, tokens)
  val roles by vm.roles.collectAsState()
  val phase by vm.phase.collectAsState()
  val clients by clientsVm.clients.collectAsState()
  val busy = phase is RolesViewModel.Phase.Busy
  var selectedClientId by remember { mutableStateOf("") }
  var dialogs by remember { mutableStateOf(RolesDialogTargets()) }
  val filtered =
      if (selectedClientId.isEmpty()) roles else roles.filter { it.clientId == selectedClientId }
  val actions =
      RoleRowActions(
          onEdit = { dialogs = dialogs.copy(editing = it) },
          onGrants = { dialogs = dialogs.copy(grantsFor = it) },
          onToggle = { r ->
            scope.launch { vm.setStatus(roleID = r.id, to = if (r.status == 1) 0 else 1) }
          },
          onDelete = { dialogs = dialogs.copy(deleting = it) },
      )

  Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onBack, enabled = !busy) { Text(text = "返回") }
      Text(text = "角色管理", style = MaterialTheme.typography.headlineMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))
    RolesFilterRow(
        clients = clients,
        selected = selectedClientId,
        busy = busy,
        onSelect = { selectedClientId = it },
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { dialogs = dialogs.copy(creating = true) }, enabled = !busy) {
        Text(text = "新建角色")
      }
    }
    RolesList(roles = filtered, busy = busy, actions = actions)
    RolesPhaseBanner(phase = phase)
  }

  RolesDialogHosts(
      targets = dialogs,
      wiring =
          RolesWiring(
              vm = vm,
              clients = clients,
              store = store,
              tokens = tokens,
              busy = busy,
              onDismiss = { dialogs = RolesDialogTargets() },
          ),
  )

  LaunchedEffect(Unit) {
    // AC-1：进页并发拉角色 + client 清单（新建对话框 Picker 数据源）。
    vm.load()
    clientsVm.load()
  }
}

/** VM 失败红字段（失败态非阻塞：列表仍可用，可重试）。 */
@Composable
private fun RolesPhaseBanner(phase: RolesViewModel.Phase) {
  (phase as? RolesViewModel.Phase.Failed)?.let { failed ->
    Spacer(modifier = Modifier.height(8.dp))
    Text(text = failed.message, color = MaterialTheme.colorScheme.error)
  }
}

/** 对话框目标束（同一时刻至多一个对话框打开；null/false = 关）。 */
private data class RolesDialogTargets(
    val creating: Boolean = false,
    val editing: SysRole? = null,
    val grantsFor: SysRole? = null,
    val deleting: SysRole? = null,
)

/** 行动作束（回调上抛 RolesScreen 持有对话框状态）。 */
private data class RoleRowActions(
    val onEdit: (SysRole) -> Unit,
    val onGrants: (SysRole) -> Unit,
    val onToggle: (SysRole) -> Unit,
    val onDelete: (SysRole) -> Unit,
)

/** client 过滤 Picker（「全部」= 空串哨兵；过滤在前端做，避免每切一次 client 打一次网）。 */
@Composable
private fun RolesFilterRow(
    clients: List<OAuthClient>,
    selected: String,
    busy: Boolean,
    onSelect: (String) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  androidx.compose.foundation.layout.Box {
    TextButton(onClick = { open = true }, enabled = !busy) {
      Text(text = "Client：${selected.ifEmpty { "全部" }}")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      DropdownMenuItem(
          text = { Text(text = "全部") },
          onClick = {
            open = false
            onSelect("")
          },
      )
      clients.forEach { c ->
        DropdownMenuItem(
            text = { Text(text = c.clientId) },
            onClick = {
              open = false
              onSelect(c.clientId)
            },
        )
      }
    }
  }
}

/** 角色列表（行内动作经 actions 束上抛）。 */
@Composable
private fun RolesList(
    roles: List<SysRole>,
    busy: Boolean,
    actions: RoleRowActions,
) {
  LazyColumn(modifier = Modifier.fillMaxWidth()) {
    roles.forEach { r ->
      item(key = r.id.toString()) { roleRow(role = r, busy = busy, actions = actions) }
    }
  }
}

/** 角色行：roleCode 主行（预设徽标）+ roleName·clientId·状态副行 + 编辑/授权/启停/删除。 */
@Composable
private fun roleRow(
    role: SysRole,
    busy: Boolean,
    actions: RoleRowActions,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
    Text(
        text = role.roleCode + if (role.isPreset) "（预设）" else "",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = "${role.roleName} · ${role.clientId} · ${if (role.status == 1) "启用" else "停用"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      TextButton(onClick = { actions.onEdit(role) }, enabled = !busy) { Text(text = "编辑") }
      TextButton(onClick = { actions.onGrants(role) }, enabled = !busy) { Text(text = "授权") }
      TextButton(onClick = { actions.onToggle(role) }, enabled = !busy) {
        Text(text = if (role.status == 1) "停用" else "启用")
      }
      TextButton(onClick = { actions.onDelete(role) }, enabled = !busy) { Text(text = "删除") }
    }
  }
}

/** 对话框接线束（VM + client 清单 + store/tokens 授权缝源 + busy + 关闭回调，避免多参长列）。 */
private data class RolesWiring(
    val vm: RolesViewModel,
    val clients: List<OAuthClient>,
    val store: SessionStore,
    val tokens: TokenHolder,
    val busy: Boolean,
    val onDismiss: () -> Unit,
)

/** 对话框 host 段：按目标分派；提交回调持 VM 执行，成功才经 onDismiss 关（失败红字留在屏上可重试）。 */
@Composable
private fun RolesDialogHosts(targets: RolesDialogTargets, wiring: RolesWiring) {
  if (targets.creating) {
    RolesCreateHost(wiring)
  }
  targets.editing?.let { target -> RolesEditHost(wiring, target) }
  targets.grantsFor?.let { target -> RolesGrantsHost(wiring, target) }
  targets.deleting?.let { target -> RolesDeleteHost(wiring, target) }
}

/** 新建 host。 */
@Composable
private fun RolesCreateHost(wiring: RolesWiring) {
  val scope = rememberCoroutineScope()
  RoleCreateDialog(
      clients = wiring.clients,
      busy = wiring.busy,
      onCreate = { clientId, code, name, desc ->
        scope.launch {
          if (
              wiring.vm.create(
                  clientId = clientId,
                  roleCode = code,
                  roleName = name,
                  description = desc.ifBlank { null },
              )
          ) {
            wiring.onDismiss()
          }
        }
      },
      onDismiss = wiring.onDismiss,
  )
}

/** 编辑 host：setStatus-then-update（状态有变才先切状态，任一步失败红字留屏）。 */
@Composable
private fun RolesEditHost(wiring: RolesWiring, target: SysRole) {
  val scope = rememberCoroutineScope()
  RoleEditDialog(
      role = target,
      busy = wiring.busy,
      onSave = { name, desc, status ->
        scope.launch {
          val moved =
              if (status != target.status) {
                wiring.vm.setStatus(roleID = target.id, to = status)
              } else {
                true
              }
          if (
              moved &&
                  wiring.vm.update(
                      roleID = target.id,
                      roleName = name,
                      description = desc.ifBlank { null },
                  )
          ) {
            wiring.onDismiss()
          }
        }
      },
      onDismiss = wiring.onDismiss,
  )
}

/** 授权 host：菜单清单缝由 store/tokens 现场装配（role.clientId 定源）。 */
@Composable
private fun RolesGrantsHost(wiring: RolesWiring, target: SysRole) {
  RoleGrantsDialog(
      role = target,
      vm = wiring.vm,
      listMenus = menusListSeam(wiring.store, wiring.tokens),
      busy = wiring.busy,
      onDismiss = wiring.onDismiss,
  )
}

/** 删除 host（危险操作确认）。 */
@Composable
private fun RolesDeleteHost(wiring: RolesWiring, target: SysRole) {
  val scope = rememberCoroutineScope()
  RoleDeleteDialog(
      role = target,
      busy = wiring.busy,
      onConfirm = {
        scope.launch {
          if (wiring.vm.remove(roleID = target.id)) {
            wiring.onDismiss()
          }
        }
      },
      onDismiss = wiring.onDismiss,
  )
}

/** 角色页 VM 装配：租户上下文取 SessionStore 持久值。 */
@Composable
private fun rememberRolesViewModel(store: SessionStore, tokens: TokenHolder): RolesViewModel =
    remember {
      RolesViewModel(tenantId = { store.tenantId }, seams = rolesSeams(store, tokens))
    }
