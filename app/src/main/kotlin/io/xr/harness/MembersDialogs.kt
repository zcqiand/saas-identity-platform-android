@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import saas.identity.shared.models.SysRole
import saas.identity.shared.models.TenantMemberUserView

/**
 * 成员对话框族（swift 角色分配/编辑/删除确认 同构，M01.F02）。 对话框本体只做绑定与禁点；提交回调由 MembersScreen 持 VM 执行，
 * 成功才关对话框（失败红字留在屏上可重试）。
 */

/** 角色分配对话框：勾选集合即提交集合（PUT 全量覆盖，空集 = 清空角色）。 */
@Composable
internal fun MembersRolesDialog(
    member: TenantMemberUserView,
    roles: List<SysRole>,
    busy: Boolean,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
  var checked by remember(member.id) { mutableStateOf(member.roleIds.toSet()) }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "分配角色：${member.username}") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          if (roles.isEmpty()) {
            Text(text = "角色清单为空（后端未配置）", color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          roles.forEach { role ->
            val id = role.id.toString()
            Row(verticalAlignment = Alignment.CenterVertically) {
              Checkbox(
                  checked = id in checked,
                  onCheckedChange = { on -> checked = if (on) checked + id else checked - id },
                  enabled = !busy,
              )
              Text(text = "${role.roleName}（${role.roleCode}）")
            }
          }
        }
      },
      confirmButton = {
        Button(
            onClick = { onSave(roles.map { it.id.toString() }.filter { it in checked }) },
            enabled = !busy,
        ) {
          Text(text = "保存")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/** 编辑对话框：email/mobile 部分段 PATCH，空串按 null 提交（生成物可空字段口径）。 */
@Composable
internal fun MemberEditDialog(
    member: TenantMemberUserView,
    busy: Boolean,
    onSave: (email: String, mobile: String) -> Unit,
    onDismiss: () -> Unit,
) {
  var email by remember(member.id) { mutableStateOf(member.email.orEmpty()) }
  var mobile by remember(member.id) { mutableStateOf("") }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "编辑：${member.username}") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
              value = email,
              onValueChange = { email = it },
              label = { Text(text = "邮箱") },
              singleLine = true,
          )
          OutlinedTextField(
              value = mobile,
              onValueChange = { mobile = it },
              label = { Text(text = "手机号（可空）") },
              singleLine = true,
          )
        }
      },
      confirmButton = {
        Button(onClick = { onSave(email, mobile) }, enabled = !busy && email.isNotBlank()) {
          Text(text = "保存")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/** 删除确认（危险操作：成员立即失去登录与成员关系，不可恢复）。 */
@Composable
internal fun MemberDeleteDialog(
    member: TenantMemberUserView,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "删除成员：${member.username}") },
      text = { Text(text = "危险操作：成员将立即失去登录与成员关系，不可恢复。") },
      confirmButton = { Button(onClick = onConfirm, enabled = !busy) { Text(text = "删除") } },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}
