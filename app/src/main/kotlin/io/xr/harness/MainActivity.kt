@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.xr.harness.app.SharedPreferencesStore
import io.xr.harness.app.SharedPreferencesTokenStore
import io.xr.harness.app.clientsListSeam
import io.xr.harness.app.listTenantsSeam
import io.xr.harness.app.loginSeam
import io.xr.harness.app.logoutSeam
import io.xr.harness.app.menusCreateSeam
import io.xr.harness.app.menusDeleteSeam
import io.xr.harness.app.menusListSeam
import io.xr.harness.app.menusMoveSeam
import io.xr.harness.app.menusReorderSeam
import io.xr.harness.app.menusUpdateSeam
import io.xr.harness.app.switchTenantSeam
import io.xr.harness.app.whoamiSeam
import io.xr.harness.core.ApiGlue
import io.xr.harness.core.MenuNode
import io.xr.harness.core.SessionState
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.core.WhoamiUser
import io.xr.harness.ui.AuthPhase
import io.xr.harness.ui.AuthViewModel
import io.xr.harness.ui.ClientsViewModel
import io.xr.harness.ui.MenusViewModel
import kotlinx.coroutines.launch
import saas.identity.shared.models.CreateSysMenuRequest
import saas.identity.shared.models.OAuthClient
import saas.identity.shared.models.SysMenu
import saas.identity.shared.models.SysMenuType
import saas.identity.shared.models.UpdateSysMenuRequest

/** 三态路由（swift App 层同构）：needsSetup → 配置表单 / needsLogin → 登录表单 / ready → 账户。 */
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { AppRoot() } }
  }
}

@Composable
fun AppRoot() {
  val context = LocalContext.current
  val prefsStore = remember { SharedPreferencesStore(context) }
  val tokenStore = remember { SharedPreferencesTokenStore(context) }
  // 持久化 token 启动预载进内存持有（ktor bearer 插件读 TokenHolder）
  val tokens = remember {
    TokenHolder().also {
      it.accessToken = tokenStore.loadAccess()
      it.refreshToken = tokenStore.loadRefresh()
    }
  }
  val store = remember { SessionStore(prefsStore, tokenStore) }
  val scope = rememberCoroutineScope()
  val vm = remember {
    AuthViewModel(
        store,
        AuthViewModel.Seams(
            login = loginSeam(store, tokens),
            whoami = whoamiSeam(store, tokens),
            logout = logoutSeam(store, tokens),
            switchTenant = switchTenantSeam(store, tokens),
            listTenants = listTenantsSeam(store, tokens),
        ),
    )
  }
  val session by vm.session.collectAsState()
  val user by vm.user.collectAsState()
  var showMenus by remember { mutableStateOf(false) }

  // 启动自愈：持久化会话 whoami 校验，失败即清密回登录
  LaunchedEffect(Unit) { vm.restore() }

  when (val s = session) {
    is SessionState.NeedsSetup ->
        ConfigScreen(
            onSave = { baseUrl, clientId ->
              store.saveConfig(baseUrl, clientId)
              vm.refreshSession()
            },
        )
    is SessionState.NeedsLogin ->
        LoginScreen(
            vm = vm,
            onReconfigure = { vm.reconfigure() },
        )
    is SessionState.Ready ->
        if (showMenus) {
          MenusScreen(onBack = { showMenus = false }, store = store, tokens = tokens)
        } else {
          AccountScreen(
              vm = vm,
              ready = s,
              user = user,
              onOpenMenus = { showMenus = true },
              onLogout = { scope.launch { vm.logout() } },
          )
        }
  }
}

@Composable
fun ConfigScreen(onSave: (String, String) -> Unit) {
  var baseUrl by remember { mutableStateOf("") }
  var clientId by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }

  Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
  ) {
    Text(text = "配置", style = MaterialTheme.typography.headlineMedium)
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = { Text(text = "API 地址") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = clientId,
        onValueChange = { clientId = it },
        label = { Text(text = "clientId") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    error?.let {
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = {
          try {
            onSave(ApiGlue.normalizeBaseURL(baseUrl), clientId.trim())
          } catch (e: IllegalArgumentException) {
            error = e.message
          }
        },
        enabled = baseUrl.isNotBlank() && clientId.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text(text = "保存")
    }
  }
}

@Composable
fun LoginScreen(vm: AuthViewModel, onReconfigure: () -> Unit) {
  val scope = rememberCoroutineScope()
  val phase by vm.phase.collectAsState()
  var username by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  val busy = phase is AuthPhase.Busy

  Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
  ) {
    Text(text = "登录", style = MaterialTheme.typography.headlineMedium)
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text(text = "用户名") },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(text = "密码") },
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    (phase as? AuthPhase.Failed)?.let { failed ->
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = failed.message, color = MaterialTheme.colorScheme.error)
    }
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = { scope.launch { vm.login(username, password) } },
        enabled = !busy && username.isNotBlank() && password.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text(text = if (busy) "登录中…" else "登录")
    }
    TextButton(onClick = onReconfigure, enabled = !busy) { Text(text = "重新配置") }
  }
}

/** 账户页（swift AccountView 同构）：当前用户 + 租户成员段（点选切换，当前带 ✓，busy 禁点防连击）。 */
@Composable
fun AccountScreen(
    vm: AuthViewModel,
    ready: SessionState.Ready,
    user: WhoamiUser?,
    onOpenMenus: () -> Unit,
    onLogout: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val phase by vm.phase.collectAsState()
  val tenants by vm.tenants.collectAsState()
  val busy = phase is AuthPhase.Busy

  // 进页拉租户成员真值（swift .task listTenants 同位）；切租户成功后重拉跟随新 ✓
  LaunchedEffect(ready.tenantId) { vm.loadTenants() }

  Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
  ) {
    Text(text = "已登录", style = MaterialTheme.typography.headlineMedium)
    Spacer(modifier = Modifier.height(16.dp))
    user?.displayName?.let { Text(text = "昵称：$it") }
    Text(text = "用户：${ready.username}")
    ready.tenantId?.let { Text(text = "租户：$it") }
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = "租户成员", style = MaterialTheme.typography.titleMedium)
    if (tenants.isEmpty()) {
      Text(text = "无成员关系", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    tenants.forEach { t ->
      val isCurrent = t.tenantId == ready.tenantId
      TextButton(
          onClick = { scope.launch { vm.switchTenant(t.tenantId) } },
          enabled = !busy && !isCurrent,
      ) {
        Text(
            text =
                buildString {
                  append(t.name ?: t.tenantId)
                  if (isCurrent) {
                    append(" ✓")
                  }
                  t.detail?.let { append("（$it）") }
                },
        )
      }
    }
    (phase as? AuthPhase.Failed)?.let { failed ->
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = failed.message, color = MaterialTheme.colorScheme.error)
    }
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = onOpenMenus, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
      Text(text = "菜单管理")
    }
    Button(onClick = onLogout, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
      Text(text = "退出登录")
    }
  }
}

@Composable
fun MenusScreen(onBack: () -> Unit, store: SessionStore, tokens: TokenHolder) {
  val scope = rememberCoroutineScope()
  val st = rememberMenusState(store, tokens)
  val clients by st.clientsVm.clients.collectAsState()
  val phase by st.menusVm.phase.collectAsState()
  val busy = phase is MenusViewModel.Phase.Busy
  var createTitle by remember { mutableStateOf("") }
  var renaming by remember { mutableStateOf<SysMenu?>(null) }
  var renameTitle by remember { mutableStateOf("") }

  Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onBack, enabled = !busy) { Text(text = "返回") }
      Text(text = "菜单管理", style = MaterialTheme.typography.headlineMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))
    MenusClientPicker(
        clients = clients,
        selected = st.selectedClient,
        busy = busy,
        onSelect = { c ->
          st.selectClient(c)
          scope.launch { st.menusVm.load(c) }
        },
    )
    Spacer(modifier = Modifier.height(8.dp))
    MenusCreateRow(
        title = createTitle,
        busy = busy,
        onTitleChange = { createTitle = it },
        onCreate = {
          val t = createTitle
          scope.launch {
            if (st.menusVm.create(CreateSysMenuRequest(title = t, type = SysMenuType.menu))) {
              createTitle = ""
            }
          }
        },
    )
    (phase as? MenusViewModel.Phase.Failed)?.let { failed ->
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = failed.message, color = MaterialTheme.colorScheme.error)
    }
    Spacer(modifier = Modifier.height(8.dp))
    MenusTree(
        tree = st.menusVm.tree,
        busy = busy,
        onRename = { m ->
          renaming = m
          renameTitle = m.title
        },
        onDelete = { m -> scope.launch { st.menusVm.deleteMenu(m.id.toString()) } },
    )
  }

  renaming?.let { target ->
    MenusRenameDialog(
        target = target,
        title = renameTitle,
        onTitleChange = { renameTitle = it },
        onConfirm = {
          scope.launch {
            if (
                st.menusVm.update(
                    target.id.toString(),
                    UpdateSysMenuRequest(title = renameTitle),
                )
            ) {
              renaming = null
            }
          }
        },
        onDismiss = { renaming = null },
    )
  }
}

/** client 选择器（AdminClients 只读子集；全量清单不传分页）。 */
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
            onClick = { onSelect(c.clientId) },
        )
      }
    }
  }
}

/** 树递归行：标题 + 类型/排序副行 + 改名/删除。 */
@Composable
private fun menuNodeRows(
    node: MenuNode,
    depth: Int,
    busy: Boolean,
    onRename: (SysMenu) -> Unit,
    onDelete: (SysMenu) -> Unit,
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
      TextButton(onClick = { onRename(m) }, enabled = !busy) { Text(text = "改名") }
      TextButton(onClick = { onDelete(m) }, enabled = !busy) { Text(text = "删除") }
    }
  }
  node.children.forEach { child -> menuNodeRows(child, depth + 1, busy, onRename, onDelete) }
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

/** 改名对话框（部分请求 update：只提交 title）。 */
@Composable
private fun MenusRenameDialog(
    target: SysMenu,
    title: String,
    onTitleChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = "改名：${target.title}") },
      text = { OutlinedTextField(value = title, onValueChange = onTitleChange, singleLine = true) },
      confirmButton = {
        Button(onClick = onConfirm, enabled = title.isNotBlank()) { Text(text = "保存") }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
  )
}

/** 树只读渲染（递归缩进；改名/删除回调上抛）。 */
@Composable
private fun MenusTree(
    tree: List<MenuNode>,
    busy: Boolean,
    onRename: (SysMenu) -> Unit,
    onDelete: (SysMenu) -> Unit,
) {
  LazyColumn(modifier = Modifier.fillMaxWidth()) {
    tree.forEach { root ->
      item(key = root.menu.id.toString()) { menuNodeRows(root, 0, busy, onRename, onDelete) }
    }
  }
}

/** 菜单页控制器束：双 VM 接线 + 首选 client 自动装载。 */
private class MenusState(
    val clientsVm: ClientsViewModel,
    val menusVm: MenusViewModel,
    val selectedClient: String?,
    val selectClient: (String) -> Unit,
)

@Composable
private fun rememberMenusState(store: SessionStore, tokens: TokenHolder): MenusState {
  val clientsVm = remember {
    ClientsViewModel(ClientsViewModel.Seams(listClients = clientsListSeam(store, tokens)))
  }
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
