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
import io.xr.harness.app.membersSeams
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.ui.MembersViewModel
import kotlinx.coroutines.launch
import saas.identity.shared.models.SysRole
import saas.identity.shared.models.TenantMemberStatus
import saas.identity.shared.models.TenantMemberUserView

/**
 * 成员管理屏（swift MembersAdminView 同构，M01.F02 + M00.F02）：成员行（角色分配/编辑/挂起恢复/删除）+ 新建行（email 后端必填 REQ-009
 * Q1）+ 邀请行（嵌套视图扁平化追加）。 对话框族在 MembersDialogs.kt；数据面全在 MembersViewModel。
 */
@Composable
fun MembersScreen(onBack: () -> Unit, store: SessionStore, tokens: TokenHolder) {
  val scope = rememberCoroutineScope()
  val vm = rememberMembersViewModel(store, tokens)
  val members by vm.members.collectAsState()
  val roles by vm.roles.collectAsState()
  val phase by vm.phase.collectAsState()
  val busy = phase is MembersViewModel.Phase.Busy
  var dialogs by remember { mutableStateOf(MembersDialogTargets()) }
  val actions =
      MemberRowActions(
          onRoles = { dialogs = dialogs.copy(rolesFor = it) },
          onEdit = { dialogs = dialogs.copy(editing = it) },
          onToggleStatus = { m ->
            scope.launch {
              val to =
                  if (m.status == TenantMemberStatus.suspended) TenantMemberStatus.active
                  else TenantMemberStatus.suspended
              vm.changeStatus(memberID = m.id, to = to)
            }
          },
          onDelete = { dialogs = dialogs.copy(deleting = it) },
      )

  Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onBack, enabled = !busy) { Text(text = "返回") }
      Text(text = "成员管理", style = MaterialTheme.typography.headlineMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))
    MembersControlsSection(
        busy = busy,
        failedMessage = (phase as? MembersViewModel.Phase.Failed)?.message,
        onCreate = { u, p, e, onDone ->
          scope.launch {
            if (vm.create(username = u, password = p, email = e.ifBlank { null }, mobile = null)) {
              onDone()
            }
          }
        },
        onInvite = { e, onDone ->
          scope.launch {
            if (vm.invite(email = e.ifBlank { null }, mobile = null)) {
              onDone()
            }
          }
        },
    )
    Spacer(modifier = Modifier.height(8.dp))
    MembersList(members = members, roles = roles, busy = busy, actions = actions)
  }

  MembersDialogHosts(
      targets = dialogs,
      vm = vm,
      roles = roles,
      busy = busy,
      onDismiss = { dialogs = MembersDialogTargets() },
  )
}

/** 对话框目标束（同一时刻至多一个对话框打开；null = 关）。 */
private data class MembersDialogTargets(
    val rolesFor: TenantMemberUserView? = null,
    val editing: TenantMemberUserView? = null,
    val deleting: TenantMemberUserView? = null,
)

/** 对话框 host 段：提交回调持 VM 执行，成功才经 onDismiss 关（失败红字留在屏上可重试）。 */
@Composable
private fun MembersDialogHosts(
    targets: MembersDialogTargets,
    vm: MembersViewModel,
    roles: List<SysRole>,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  targets.rolesFor?.let { target ->
    MembersRolesDialog(
        member = target,
        roles = roles,
        busy = busy,
        onSave = { roleIds ->
          scope.launch {
            if (vm.assignRoles(roleIds, target.id)) {
              onDismiss()
            }
          }
        },
        onDismiss = onDismiss,
    )
  }
  targets.editing?.let { target ->
    MemberEditDialog(
        member = target,
        busy = busy,
        onSave = { email, mobile ->
          scope.launch {
            if (
                vm.update(
                    memberID = target.id,
                    email = email.ifBlank { null },
                    mobile = mobile.ifBlank { null },
                )
            ) {
              onDismiss()
            }
          }
        },
        onDismiss = onDismiss,
    )
  }
  targets.deleting?.let { target ->
    MemberDeleteDialog(
        member = target,
        busy = busy,
        onConfirm = {
          scope.launch {
            if (vm.remove(memberID = target.id)) {
              onDismiss()
            }
          }
        },
        onDismiss = onDismiss,
    )
  }
}

/** 成员列表（LazyColumn，行内动作经 actions 束上抛）。 */
@Composable
private fun MembersList(
    members: List<TenantMemberUserView>,
    roles: List<SysRole>,
    busy: Boolean,
    actions: MemberRowActions,
) {
  LazyColumn(modifier = Modifier.fillMaxWidth()) {
    members.forEach { m ->
      item(key = m.id.toString()) {
        MemberRow(member = m, roles = roles, busy = busy, actions = actions)
      }
    }
  }
}

/** 行动作束（回调上抛 MembersScreen 持有对话框状态）。 */
private data class MemberRowActions(
    val onRoles: (TenantMemberUserView) -> Unit,
    val onEdit: (TenantMemberUserView) -> Unit,
    val onToggleStatus: (TenantMemberUserView) -> Unit,
    val onDelete: (TenantMemberUserView) -> Unit,
)

/** 成员行：username 主行 + status/email/角色副行 + 角色/编辑/挂起恢复/删除（挂起态显示恢复）。 */
@Composable
private fun MemberRow(
    member: TenantMemberUserView,
    roles: List<SysRole>,
    busy: Boolean,
    actions: MemberRowActions,
) {
  val roleNames =
      member.roleIds
          .mapNotNull { id -> roles.firstOrNull { it.id.toString() == id }?.roleName }
          .ifEmpty { member.roleIds }
  val roleText = roleNames.joinToString("、").ifEmpty { "无" }
  val metaLine = "${statusLabel(member.status)} · ${member.email ?: "（无邮箱）"} · 角色：$roleText"
  Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
    Text(text = member.username, style = MaterialTheme.typography.bodyLarge)
    Text(
        text = metaLine,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      TextButton(onClick = { actions.onRoles(member) }, enabled = !busy) { Text(text = "角色") }
      TextButton(onClick = { actions.onEdit(member) }, enabled = !busy) { Text(text = "编辑") }
      TextButton(onClick = { actions.onToggleStatus(member) }, enabled = !busy) {
        Text(text = if (member.status == TenantMemberStatus.suspended) "恢复" else "挂起")
      }
      TextButton(onClick = { actions.onDelete(member) }, enabled = !busy) { Text(text = "删除") }
    }
  }
}

/** 成员状态中文标签（invitee/suspended/disabled 口径直译）。 */
private fun statusLabel(status: TenantMemberStatus): String =
    when (status) {
      TenantMemberStatus.active -> "活跃"
      TenantMemberStatus.invited -> "已邀请"
      TenantMemberStatus.suspended -> "已挂起"
      TenantMemberStatus.disabled -> "已停用"
    }

/** 控制段：新建行 + 邀请行 + 操作失败红字（输入各自局部持有，成功经 onDone 清空）。 */
@Composable
private fun MembersControlsSection(
    busy: Boolean,
    failedMessage: String?,
    onCreate: (username: String, password: String, email: String, onDone: () -> Unit) -> Unit,
    onInvite: (email: String, onDone: () -> Unit) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    MembersCreateRow(busy = busy, onCreate = onCreate)
    MembersInviteRow(busy = busy, onInvite = onInvite)
    failedMessage?.let {
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = it, color = MaterialTheme.colorScheme.error)
    }
  }
}

/** 新建行：username/password/email 三段输入 + 新建（email 后端必填 REQ-009 Q1，UI 层校验非空）。 */
@Composable
private fun MembersCreateRow(
    busy: Boolean,
    onCreate: (username: String, password: String, email: String, onDone: () -> Unit) -> Unit,
) {
  var username by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var email by remember { mutableStateOf("") }
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text(text = "用户名") },
        modifier = Modifier.weight(1f),
        singleLine = true,
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(text = "密码") },
        modifier = Modifier.weight(1f),
        singleLine = true,
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(text = "邮箱") },
        modifier = Modifier.weight(1f),
        singleLine = true,
    )
    Button(
        onClick = {
          onCreate(username, password, email) {
            username = ""
            password = ""
            email = ""
          }
        },
        enabled = !busy && username.isNotBlank() && password.isNotBlank() && email.isNotBlank(),
    ) {
      Text(text = "新建")
    }
  }
}

/** 邀请行：被邀邮箱输入 + 邀请（响应为嵌套视图，VM 扁平化）。 */
@Composable
private fun MembersInviteRow(
    busy: Boolean,
    onInvite: (email: String, onDone: () -> Unit) -> Unit,
) {
  var inviteEmail by remember { mutableStateOf("") }
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
        value = inviteEmail,
        onValueChange = { inviteEmail = it },
        label = { Text(text = "被邀邮箱") },
        modifier = Modifier.weight(1f),
        singleLine = true,
    )
    Button(
        onClick = { onInvite(inviteEmail) { inviteEmail = "" } },
        enabled = !busy && inviteEmail.isNotBlank(),
    ) {
      Text(text = "邀请")
    }
  }
}

/** 成员页 VM 装配：租户上下文取 SessionStore 持久值，进页自动装载。 */
@Composable
private fun rememberMembersViewModel(store: SessionStore, tokens: TokenHolder): MembersViewModel {
  val vm = remember {
    MembersViewModel(tenantId = { store.tenantId }, seams = membersSeams(store, tokens))
  }
  LaunchedEffect(Unit) { vm.load() }
  return vm
}
