@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.xr.harness.ui.TenantApplicationsViewModel
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import saas.identity.shared.models.OAuthClient
import saas.identity.shared.models.TenantApplication

/**
 * 租户应用对话框族（swift TenantApplicationSubscribeSheet / DetailView 编辑段 同构，M00.F05，REQ-2026-012）。
 * 对话框本体只做绑定与禁点；提交回调由 ApplicationsScreen 持 VM 执行，成功才关（失败红字留在屏上可重试）。 未知 clientId 后端 404 / 重复订阅后端 400
 * 裸 SQL 泄漏——红字如实呈现，不做前置重名校验（REQ-012 探针口径）。
 */

/** wire 层 String 化后展示位取 ISO-8601 日期段长度（YYYY-MM-DD）。 */
internal const val ISO_DATE_PREFIX_LEN = 10

/** 订阅应用：client Picker（admin 面清单）必选 + 到期日（可空，YYYY-MM-DD，默认一年后）。 */
@Composable
internal fun AppSubscribeDialog(
    clients: List<OAuthClient>,
    busy: Boolean,
    failed: TenantApplicationsViewModel.Phase.Failed?,
    onSubscribe: (clientId: String, expireText: String) -> Unit,
    onDismiss: () -> Unit,
) {
  var clientId by remember { mutableStateOf("") }
  var expireText by remember { mutableStateOf(defaultExpireText()) }
  var parseError by remember { mutableStateOf(false) }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "订阅应用") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          AppClientPicker(clients = clients, selected = clientId, onPick = { clientId = it })
          OutlinedTextField(
              value = expireText,
              onValueChange = {
                expireText = it
                parseError = false
              },
              label = { Text(text = "到期（可空，YYYY-MM-DD）") },
              supportingText = {
                Text(
                    text = if (parseError) "格式应为 YYYY-MM-DD" else "到期时间默认一年后，可在列表改期或停用",
                    color =
                        if (parseError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              },
              singleLine = true,
          )
          failed?.let { Text(text = it.message, color = MaterialTheme.colorScheme.error) }
        }
      },
      confirmButton = {
        Button(
            onClick = {
              val parsed = runCatching { parseExpireInput(expireText) }.getOrNull()
              if (parsed != null || expireText.isBlank()) {
                onSubscribe(clientId, expireText)
              } else {
                parseError = true
              }
            },
            enabled = !busy && clientId.isNotEmpty(),
        ) {
          Text(text = "订阅")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/** 改期对话框：契约 partial-update「不传 = 不改」——「清空到期」不可达，需换期只能改期（CT I76 实证）。 */
@Composable
internal fun AppExpireDialog(
    app: TenantApplication,
    busy: Boolean,
    failed: TenantApplicationsViewModel.Phase.Failed?,
    onSave: (clientId: String, expire: OffsetDateTime) -> Unit,
    onDismiss: () -> Unit,
) {
  var expireText by
      remember(app.clientId) { mutableStateOf(app.expireTime?.take(ISO_DATE_PREFIX_LEN) ?: "") }
  var parseError by remember { mutableStateOf(false) }
  val parsed = parseExpireInput(expireText)

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "改期：${app.clientId}") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
              value = expireText,
              onValueChange = {
                expireText = it
                parseError = false
              },
              label = { Text(text = "到期（YYYY-MM-DD）") },
              supportingText = {
                Text(
                    text = if (parseError) "格式应为 YYYY-MM-DD" else "「清空到期」不可达（契约：不提交 = 不改）",
                    color =
                        if (parseError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              },
              singleLine = true,
          )
          failed?.let { Text(text = it.message, color = MaterialTheme.colorScheme.error) }
        }
      },
      confirmButton = {
        Button(
            onClick = {
              if (parsed != null) {
                onSave(app.clientId, parsed)
              } else {
                parseError = true
              }
            },
            enabled = !busy,
        ) {
          Text(text = "保存到期时间")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/** 移除订阅确认（危险操作：移除后该 client 不再属于当前租户，可重新订阅）。 */
@Composable
internal fun AppRemoveDialog(
    app: TenantApplication,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "移除对「${app.clientId}」的订阅？") },
      text = { Text(text = "移除后该 client 不再属于当前租户，可重新订阅。") },
      confirmButton = { Button(onClick = onConfirm, enabled = !busy) { Text(text = "移除") } },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/** 订阅对话框的 client Picker（admin 面清单，RolesAdminView 同款语义）。 */
@Composable
private fun AppClientPicker(
    clients: List<OAuthClient>,
    selected: String,
    onPick: (String) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    TextButton(onClick = { open = true }) {
      Text(text = "Client（必选）：${selected.ifEmpty { "选择 client" }}")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      clients.forEach { c ->
        DropdownMenuItem(
            text = { Text(text = c.clientId) },
            onClick = {
              open = false
              onPick(c.clientId)
            },
        )
      }
    }
  }
}

/** 到期输入解析：空 = null（可空契约原样上送）；YYYY-MM-DD → UTC 当日起始。非法格式抛（调用方红字）。 */
internal fun parseExpireInput(text: String): OffsetDateTime? =
    text
        .trim()
        .takeIf { it.isNotEmpty() }
        ?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() }

/** 订阅默认到期：一年后（UTC，取日期部分展示）。 */
internal fun defaultExpireText(): String =
    OffsetDateTime.now(ZoneOffset.UTC).plusYears(1).toLocalDate().toString()
