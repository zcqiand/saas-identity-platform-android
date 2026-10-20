@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import saas.identity.shared.models.SysMenu
import saas.identity.shared.models.SysMenuType
import saas.identity.shared.models.UpdateSysMenuRequest

/**
 * 菜单编辑/删除对话框族（swift MenuEditView / delete confirmationDialog 同构，M04.F04）。 Host 层持有接线（父级变更先 move 再
 * update，失败即止不连发）；对话框本体只做绑定与禁点。
 */

/** 编辑对话框 Host：父级变更先 move（null = 回根）成功再 update 字段段。 */
@Composable
internal fun MenuEditDialogHost(
    target: SysMenu,
    menus: List<SysMenu>,
    vm: io.xr.harness.ui.MenusViewModel,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  MenuEditDialog(
      target = target,
      menus = menus,
      busy = busy,
      onConfirm = { title, path, parentKey, status ->
        scope.launch {
          if (parentKey != parentKeyOf(target)) {
            if (!vm.move(target.id.toString(), parentKey.ifEmpty { null })) {
              return@launch
            }
          }
          if (
              vm.update(
                  target.id.toString(),
                  UpdateSysMenuRequest(
                      title = title,
                      path = path.ifBlank { null },
                      status = status,
                  ),
              )
          ) {
            onDismiss()
          }
        }
      },
      onDismiss = onDismiss,
  )
}

/** 编辑对话框（title/path/status + 父菜单 Picker：根或目录且非自身）。 */
@Composable
private fun MenuEditDialog(
    target: SysMenu,
    menus: List<SysMenu>,
    busy: Boolean,
    onConfirm: (title: String, path: String, parentKey: String, status: Int) -> Unit,
    onDismiss: () -> Unit,
) {
  var title by remember(target.id) { mutableStateOf(target.title) }
  var path by remember(target.id) { mutableStateOf(target.path.orEmpty()) }
  var status by remember(target.id) { mutableStateOf(if (target.status == 1) 1 else 0) }
  var parentKey by remember(target.id) { mutableStateOf(parentKeyOf(target)) }
  val parentOptions =
      listOf("" to "（根级）") +
          menus
              .filter { it.type == SysMenuType.directory && it.id != target.id }
              .map { it.id.toString() to it.title }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "编辑：${target.title}") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
              value = title,
              onValueChange = { title = it },
              label = { Text(text = "标题") },
              singleLine = true,
          )
          OutlinedTextField(
              value = path,
              onValueChange = { path = it },
              label = { Text(text = "路径（可空）") },
              singleLine = true,
          )
          PickerRow(
              label = "状态：${if (status == 1) "启用" else "停用"}",
              enabled = !busy,
              options = listOf(1 to "启用", 0 to "停用"),
              onPick = { status = it },
          )
          PickerRow(
              label =
                  "父菜单：" +
                      (parentOptions.firstOrNull { it.first == parentKey }?.second ?: parentKey),
              enabled = !busy,
              options = parentOptions,
              onPick = { parentKey = it },
          )
        }
      },
      confirmButton = {
        Button(
            onClick = { onConfirm(title, path, parentKey, status) },
            enabled = !busy && title.isNotBlank(),
        ) {
          Text(text = "保存")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/** 删除确认 Host：删除成功才关对话框（失败红字留在屏上可重试）。 */
@Composable
internal fun MenuDeleteDialogHost(
    target: SysMenu,
    vm: io.xr.harness.ui.MenusViewModel,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  MenuDeleteDialog(
      target = target,
      busy = busy,
      onConfirm = {
        scope.launch {
          if (vm.deleteMenu(target.id.toString())) {
            onDismiss()
          }
        }
      },
      onDismiss = onDismiss,
  )
}

/** 删除确认（危险操作：子菜单将失去父级（渲染时挂根），不可恢复）。 */
@Composable
private fun MenuDeleteDialog(
    target: SysMenu,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "删除菜单：${target.title}") },
      text = { Text(text = "危险操作：删除后不可恢复；子菜单将失去父级（渲染时挂根）。") },
      confirmButton = { Button(onClick = onConfirm, enabled = !busy) { Text(text = "删除") } },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/** 通用单选行（状态/父菜单 Picker 共用交互）。 */
@Composable
private fun <T> PickerRow(
    label: String,
    enabled: Boolean,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    TextButton(onClick = { open = true }, enabled = enabled) { Text(text = label) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      options.forEach { (value, text) ->
        DropdownMenuItem(
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
