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
import io.xr.harness.app.tenantApplicationsSeams
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.ui.AdminClientsViewModel
import io.xr.harness.ui.TenantApplicationsViewModel
import kotlinx.coroutines.launch
import saas.identity.shared.models.OAuthClient
import saas.identity.shared.models.TenantApplication

/**
 * 租户应用屏（swift TenantApplicationsAdminView 同构，M00.F05，REQ-2026-012）：订阅清单（clientId 主行 + 状态·到期 副行）+
 * 订阅对话框（client Picker + 到期）+ 行改期/启停/移除（移除二次确认）。 寻址 clientId 字符串列；启停总带当前 expireTime 提交（契约 partial：不传
 * = 不改，防误清）。数据流全在 TenantApplicationsViewModel，本层只渲染与转发。
 */
@Composable
fun ApplicationsScreen(onBack: () -> Unit, store: SessionStore, tokens: TokenHolder) {
  val scope = rememberCoroutineScope()
  val clientsVm = remember { AdminClientsViewModel(adminClientsSeams(store, tokens)) }
  val vm = rememberApplicationsViewModel(store, tokens)
  val applications by vm.applications.collectAsState()
  val phase by vm.phase.collectAsState()
  val clients by clientsVm.clients.collectAsState()
  val busy = phase is TenantApplicationsViewModel.Phase.Busy
  val failed = phase as? TenantApplicationsViewModel.Phase.Failed
  var dialogs by remember { mutableStateOf(ApplicationsDialogTargets()) }
  val actions =
      AppRowActions(
          onExpire = { dialogs = dialogs.copy(expiring = it) },
          onToggle = { app -> scope.launch { toggleApp(vm, app) } },
          onRemove = { dialogs = dialogs.copy(removing = it) },
      )

  Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
    ApplicationsHeader(
        onBack = onBack,
        busy = busy,
        onSubscribe = { dialogs = dialogs.copy(subscribing = true) },
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (applications.isEmpty()) {
      Text(
          text = if (busy) "加载中…" else "无订阅应用",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    ApplicationsList(applications = applications, busy = busy, actions = actions)
    failed?.let {
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = it.message, color = MaterialTheme.colorScheme.error)
    }
  }

  ApplicationsDialogHosts(
      targets = dialogs,
      wiring =
          ApplicationsWiring(
              vm = vm,
              clients = clients,
              busy = busy,
              failed = failed,
              onDismiss = { dialogs = ApplicationsDialogTargets() },
          ),
  )

  LaunchedEffect(Unit) {
    // 并发拉订阅清单 + client 清单（订阅 sheet 的 Picker 数据源）。
    vm.load()
    clientsVm.load()
  }
}

/** 头部：返回 + 标题 + 订阅入口（swift toolbar primaryAction 同位）。 */
@Composable
private fun ApplicationsHeader(onBack: () -> Unit, busy: Boolean, onSubscribe: () -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = onBack, enabled = !busy) { Text(text = "返回") }
    Text(text = "租户应用", style = MaterialTheme.typography.headlineMedium)
    Button(onClick = onSubscribe, enabled = !busy) { Text(text = "订阅应用") }
  }
  Text(
      text = "订阅应用（当前租户）· 寻址 clientId 字符串列，到期时间随订阅/更新维护",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** 启停（PATCH status + 当前 expireTime——契约 partial 不传 = 不改，防误清）。 */
private suspend fun toggleApp(vm: TenantApplicationsViewModel, app: TenantApplication) {
  vm.update(
      clientId = app.clientId,
      status = if (app.status == 1) 0 else 1,
      expireTime = app.expireTime,
  )
}

/** 对话框目标束（同一时刻至多一个对话框打开；null/false = 关）。 */
private data class ApplicationsDialogTargets(
    val subscribing: Boolean = false,
    val expiring: TenantApplication? = null,
    val removing: TenantApplication? = null,
)

/** 行动作束（回调上抛 ApplicationsScreen 持有对话框状态）。 */
private data class AppRowActions(
    val onExpire: (TenantApplication) -> Unit,
    val onToggle: (TenantApplication) -> Unit,
    val onRemove: (TenantApplication) -> Unit,
)

/** 对话框接线束（VM + client 清单 + busy + 失败态 + 关闭回调，避免多参长列）。 */
private data class ApplicationsWiring(
    val vm: TenantApplicationsViewModel,
    val clients: List<OAuthClient>,
    val busy: Boolean,
    val failed: TenantApplicationsViewModel.Phase.Failed?,
    val onDismiss: () -> Unit,
)

/** 订阅清单（行内动作经 actions 束上抛）。 */
@Composable
private fun ApplicationsList(
    applications: List<TenantApplication>,
    busy: Boolean,
    actions: AppRowActions,
) {
  LazyColumn(modifier = Modifier.fillMaxWidth()) {
    applications.forEach { app ->
      item(key = app.clientId) { appRow(app = app, busy = busy, actions = actions) }
    }
  }
}

/** 订阅行：clientId 主行 + 状态·到期副行 + 改期/启停/移除。 */
@Composable
private fun appRow(
    app: TenantApplication,
    busy: Boolean,
    actions: AppRowActions,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
    Text(
        text = app.clientId + if (app.status == 1) "" else "（停用）",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = "${if (app.status == 1) "启用" else "停用"} · ${expireLabel(app)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      TextButton(onClick = { actions.onExpire(app) }, enabled = !busy) { Text(text = "改期") }
      TextButton(onClick = { actions.onToggle(app) }, enabled = !busy) {
        Text(text = if (app.status == 1) "停用" else "启用")
      }
      TextButton(onClick = { actions.onRemove(app) }, enabled = !busy) { Text(text = "移除") }
    }
  }
}

private fun expireLabel(app: TenantApplication): String =
    app.expireTime?.let { "到期：${it.toLocalDate()}" } ?: "无到期时间"

/** 对话框 host 段：按目标分派；提交回调持 VM 执行，成功才经 onDismiss 关（失败红字留在屏上可重试）。 */
@Composable
private fun ApplicationsDialogHosts(
    targets: ApplicationsDialogTargets,
    wiring: ApplicationsWiring,
) {
  if (targets.subscribing) {
    AppSubscribeHost(wiring)
  }
  targets.expiring?.let { target -> AppExpireHost(wiring, target) }
  targets.removing?.let { target -> AppRemoveHost(wiring, target) }
}

/** 订阅 host：到期文本经 parseExpireInput 解析（对话框已校验格式）。 */
@Composable
private fun AppSubscribeHost(wiring: ApplicationsWiring) {
  val scope = rememberCoroutineScope()
  AppSubscribeDialog(
      clients = wiring.clients,
      busy = wiring.busy,
      failed = wiring.failed,
      onSubscribe = { clientId, expireText ->
        scope.launch {
          val expire = parseExpireInput(expireText)
          if (wiring.vm.subscribe(clientId = clientId, expireTime = expire)) {
            wiring.onDismiss()
          }
        }
      },
      onDismiss = wiring.onDismiss,
  )
}

/** 改期 host：PATCH partial 总带新到期提交。 */
@Composable
private fun AppExpireHost(wiring: ApplicationsWiring, target: TenantApplication) {
  val scope = rememberCoroutineScope()
  AppExpireDialog(
      app = target,
      busy = wiring.busy,
      failed = wiring.failed,
      onSave = { clientId, expire ->
        scope.launch {
          if (
              wiring.vm.update(
                  clientId = clientId,
                  status = target.status,
                  expireTime = expire,
              )
          ) {
            wiring.onDismiss()
          }
        }
      },
      onDismiss = wiring.onDismiss,
  )
}

/** 移除 host（危险操作确认）。 */
@Composable
private fun AppRemoveHost(wiring: ApplicationsWiring, target: TenantApplication) {
  val scope = rememberCoroutineScope()
  AppRemoveDialog(
      app = target,
      busy = wiring.busy,
      onConfirm = {
        scope.launch {
          if (wiring.vm.remove(clientId = target.clientId)) {
            wiring.onDismiss()
          }
        }
      },
      onDismiss = wiring.onDismiss,
  )
}

/** 租户应用页 VM 装配：租户上下文取 SessionStore 持久值。 */
@Composable
private fun rememberApplicationsViewModel(
    store: SessionStore,
    tokens: TokenHolder,
): TenantApplicationsViewModel = remember {
  TenantApplicationsViewModel(
      tenantId = { store.tenantId },
      seams = tenantApplicationsSeams(store, tokens),
  )
}
