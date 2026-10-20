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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import io.xr.harness.app.adminTenantsSeams
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.ui.AdminTenantsViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import saas.identity.shared.models.Tenant
import saas.identity.shared.models.TenantStatus
import saas.identity.shared.models.UpdateTenantRequest

/**
 * 租户维护屏（swift TenantsAdminView 同构，M00.F01，AC-1~AC-6）：列表（name 主行 + key·status 副行）→ 编辑（改名 + status
 * Picker + 删除二次确认）+ 新建（tenantKey/name）。 寻址 UUID id（Q1）；status 字符串枚举（Q2）；删除级联危险操作走二次确认。 数据流全在
 * AdminTenantsViewModel，本层只渲染与转发。
 */
@Composable
fun TenantsScreen(onBack: () -> Unit, store: SessionStore, tokens: TokenHolder) {
  val scope = rememberCoroutineScope()
  val vm = remember { AdminTenantsViewModel(adminTenantsSeams(store, tokens)) }
  val tenants by vm.tenants.collectAsState()
  val phase by vm.phase.collectAsState()
  val busy = phase is AdminTenantsViewModel.Phase.Busy
  val failed = phase as? AdminTenantsViewModel.Phase.Failed
  var dialogs by remember { mutableStateOf(TenantDialogTargets()) }

  Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
    TenantsHeader(
        onBack = onBack,
        busy = busy,
        onCreate = { dialogs = dialogs.copy(creating = true) },
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (tenants.isEmpty()) {
      Text(
          text = if (busy) "加载中…" else "无租户",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    TenantsList(tenants = tenants, busy = busy, onEdit = { dialogs = dialogs.copy(editing = it) })
    Text(
        text = "共 ${tenants.size} 个 · status 切换即生效（active/suspended）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    failed?.let {
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = it.message, color = MaterialTheme.colorScheme.error)
    }
  }

  TenantDialogHosts(
      targets = dialogs,
      wiring =
          TenantWiring(
              vm = vm,
              busy = busy,
              failed = failed,
              onDismiss = { dialogs = TenantDialogTargets() },
          ),
  )

  LaunchedEffect(Unit) { vm.load() }
}

/** 头部：返回 + 标题 + 新建入口（swift toolbar primaryAction 同位）。 */
@Composable
private fun TenantsHeader(onBack: () -> Unit, busy: Boolean, onCreate: () -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = onBack, enabled = !busy) { Text(text = "返回") }
    Text(text = "租户管理（平台 admin）", style = MaterialTheme.typography.headlineMedium)
    Button(onClick = onCreate, enabled = !busy) { Text(text = "新建租户") }
  }
}

/** 对话框目标束（同一时刻至多一个对话框打开；null/false = 关）。 */
private data class TenantDialogTargets(
    val creating: Boolean = false,
    val editing: Tenant? = null,
)

/** 对话框接线束（VM + busy + 失败态 + 关闭回调，避免多参长列）。 */
private data class TenantWiring(
    val vm: AdminTenantsViewModel,
    val busy: Boolean,
    val failed: AdminTenantsViewModel.Phase.Failed?,
    val onDismiss: () -> Unit,
)

/** 租户清单（行点击进编辑）。 */
@Composable
private fun TenantsList(
    tenants: List<Tenant>,
    busy: Boolean,
    onEdit: (Tenant) -> Unit,
) {
  LazyColumn(modifier = Modifier.fillMaxWidth()) {
    tenants.forEach { tenant ->
      item(key = tenant.id.toString()) { tenantRow(tenant, busy, onEdit) }
    }
  }
}

/** 租户行：name 主行 + key·status 副行，点击进编辑。 */
@Composable
private fun tenantRow(
    tenant: Tenant,
    busy: Boolean,
    onEdit: (Tenant) -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
  ) {
    TextButton(onClick = { onEdit(tenant) }, enabled = !busy) { Text(text = tenant.name) }
    Text(
        text = "${tenant.tenantKey} · ${if (tenant.status == TenantStatus.active) "启用" else "停用"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** 对话框 host 段：按目标分派；提交回调持 VM 执行，成功才经 onDismiss 关（失败红字留在屏上可重试）。 */
@Composable
private fun TenantDialogHosts(
    targets: TenantDialogTargets,
    wiring: TenantWiring,
) {
  if (targets.creating) {
    TenantCreateHost(wiring)
  }
  targets.editing?.let { target -> TenantEditHost(wiring, target) }
}

/** 新建 host：tenantKey/name 必填（重复 409 红字可重试；服务端默认 status=active）。 */
@Composable
private fun TenantCreateHost(wiring: TenantWiring) {
  val scope = rememberCoroutineScope()
  TenantCreateDialog(
      busy = wiring.busy,
      failed = wiring.failed,
      onCreate = { key, name ->
        scope.launch {
          if (
              wiring.vm.create(
                  saas.identity.shared.models.CreateTenantRequest(tenantKey = key, name = name),
              )
          ) {
            wiring.onDismiss()
          }
        }
      },
      onDismiss = wiring.onDismiss,
  )
}

/** 编辑回调束（保存/删除/关闭，避免编辑对话框多参长列）。 */
private data class TenantEditActions(
    val onSave: (id: Uuid, name: String, status: TenantStatus) -> Unit,
    val onDelete: (id: Uuid) -> Unit,
    val onDismiss: () -> Unit,
)

/** 编辑 host：改名 + status Picker + 删除确认（级联危险操作）。 */
@Composable
private fun TenantEditHost(wiring: TenantWiring, target: Tenant) {
  val scope = rememberCoroutineScope()
  TenantEditDialog(
      tenant = target,
      tenants = wiring.vm.tenants.collectAsState().value,
      busy = wiring.busy,
      failed = wiring.failed,
      actions =
          TenantEditActions(
              onSave = { id, name, status ->
                scope.launch {
                  if (wiring.vm.update(id, UpdateTenantRequest(name = name, status = status))) {
                    wiring.onDismiss()
                  }
                }
              },
              onDelete = { id ->
                scope.launch {
                  if (wiring.vm.deleteTenant(id)) {
                    wiring.onDismiss()
                  }
                }
              },
              onDismiss = wiring.onDismiss,
          ),
  )
}

/** 新建租户对话框：tenantKey（唯一标识）/name 均必填 trim。 */
@Composable
private fun TenantCreateDialog(
    busy: Boolean,
    failed: AdminTenantsViewModel.Phase.Failed?,
    onCreate: (tenantKey: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
  var tenantKey by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  val valid = tenantKey.isNotBlank() && name.isNotBlank()

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "新建租户") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
              value = tenantKey,
              onValueChange = { tenantKey = it },
              label = { Text(text = "tenantKey（唯一标识）") },
              singleLine = true,
          )
          OutlinedTextField(
              value = name,
              onValueChange = { name = it },
              label = { Text(text = "租户名称") },
              singleLine = true,
          )
          Text(
              text = "tenantKey 重复返回 409；创建后默认 status=active",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          failed?.let { Text(text = it.message, color = MaterialTheme.colorScheme.error) }
        }
      },
      confirmButton = {
        Button(
            onClick = { onCreate(tenantKey.trim(), name.trim()) },
            enabled = !busy && valid,
        ) {
          Text(text = "创建")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/** 编辑租户对话框：详情只读段 + 改名 + status Picker + 删除确认（嵌套，级联危险操作）。 */
@Composable
private fun TenantEditDialog(
    tenant: Tenant,
    tenants: List<Tenant>,
    busy: Boolean,
    failed: AdminTenantsViewModel.Phase.Failed?,
    actions: TenantEditActions,
) {
  // vm.tenants 是唯一真相（update/delete 原位替换后快照会陈旧）
  val current = tenants.firstOrNull { it.id == tenant.id } ?: tenant
  var name by remember(current.id) { mutableStateOf(current.name) }
  var status by remember(current.id) { mutableStateOf(current.status) }
  var confirmDelete by remember { mutableStateOf(false) }

  if (confirmDelete) {
    TenantDeleteConfirmDialog(
        tenant = current,
        busy = busy,
        onDelete = { actions.onDelete(current.id) },
        onCancel = { confirmDelete = false },
    )
  }

  AlertDialog(
      onDismissRequest = actions.onDismiss,
      title = { Text(text = current.name) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
              text = "tenantKey：${current.tenantKey} · id：${current.id}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
              value = name,
              onValueChange = { name = it },
              label = { Text(text = "租户名称") },
              singleLine = true,
          )
          PickerRow(
              label = "状态",
              options = listOf(TenantStatus.active to "启用", TenantStatus.suspended to "停用"),
              selected = status,
              onPick = { status = it },
          )
          TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
            Text(text = "删除租户", color = MaterialTheme.colorScheme.error)
          }
          Text(
              text = "status 是字符串枚举 active/suspended（与 OAuth 的 Int 口径不同）",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          failed?.let { Text(text = it.message, color = MaterialTheme.colorScheme.error) }
        }
      },
      confirmButton = {
        Button(
            onClick = { actions.onSave(current.id, name.trim(), status) },
            enabled = !busy && name.isNotBlank(),
        ) {
          Text(text = "保存")
        }
      },
      dismissButton = { TextButton(onClick = actions.onDismiss) { Text(text = "取消") } },
  )
}

/** 删除二次确认（级联危险操作：租户及成员关系、应用订阅级联处理，不可恢复）。 */
@Composable
private fun TenantDeleteConfirmDialog(
    tenant: Tenant,
    busy: Boolean,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onCancel,
      title = { Text(text = "删除 ${tenant.tenantKey}？") },
      text = { Text(text = "租户及其成员关系、应用订阅将被级联处理，不可恢复") },
      confirmButton = {
        Button(onClick = onDelete, enabled = !busy) { Text(text = "删除（级联清理成员与订阅）") }
      },
      dismissButton = { TextButton(onClick = onCancel) { Text(text = "取消") } },
  )
}

/** 通用 Picker 行（Box + DropdownMenu，RolesDialogs 同款语义）。 */
@Composable
private fun PickerRow(
    label: String,
    options: List<Pair<TenantStatus, String>>,
    selected: TenantStatus,
    onPick: (TenantStatus) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  androidx.compose.foundation.layout.Box {
    TextButton(onClick = { open = true }) {
      Text(text = "$label：${options.firstOrNull { it.first == selected }?.second ?: "选择"}")
    }
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
