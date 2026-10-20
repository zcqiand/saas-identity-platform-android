@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.xr.harness.app.SharedPreferencesStore
import io.xr.harness.app.SharedPreferencesTokenStore
import io.xr.harness.app.listTenantsSeam
import io.xr.harness.app.loginSeam
import io.xr.harness.app.logoutSeam
import io.xr.harness.app.switchTenantSeam
import io.xr.harness.app.whoamiSeam
import io.xr.harness.core.ApiGlue
import io.xr.harness.core.SessionState
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.core.WhoamiUser
import io.xr.harness.ui.AuthPhase
import io.xr.harness.ui.AuthViewModel
import kotlinx.coroutines.launch

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
  var showMembers by remember { mutableStateOf(false) }
  var showRoles by remember { mutableStateOf(false) }
  var showApplications by remember { mutableStateOf(false) }

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
        } else if (showMembers) {
          MembersScreen(onBack = { showMembers = false }, store = store, tokens = tokens)
        } else if (showRoles) {
          RolesScreen(onBack = { showRoles = false }, store = store, tokens = tokens)
        } else if (showApplications) {
          ApplicationsScreen(onBack = { showApplications = false }, store = store, tokens = tokens)
        } else {
          AccountScreen(
              vm = vm,
              ready = s,
              user = user,
              nav =
                  AccountNav(
                      onOpenMenus = { showMenus = true },
                      onOpenMembers = { showMembers = true },
                      onOpenRoles = { showRoles = true },
                      onOpenApplications = { showApplications = true },
                      onLogout = { scope.launch { vm.logout() } },
                  ),
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

/** 导航动作束（管理屏入口 + 登出，束形降参防 LongParameterList）。 */
internal data class AccountNav(
    val onOpenMenus: () -> Unit,
    val onOpenMembers: () -> Unit,
    val onOpenRoles: () -> Unit,
    val onOpenApplications: () -> Unit,
    val onLogout: () -> Unit,
)

/** 账户页（swift AccountView 同构）：当前用户 + 租户成员段（点选切换，当前带 ✓，busy 禁点防连击）。 */
@Composable
private fun AccountScreen(
    vm: AuthViewModel,
    ready: SessionState.Ready,
    user: WhoamiUser?,
    nav: AccountNav,
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
    Button(onClick = nav.onOpenMenus, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
      Text(text = "菜单管理")
    }
    Button(onClick = nav.onOpenMembers, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
      Text(text = "成员管理")
    }
    Button(onClick = nav.onOpenRoles, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
      Text(text = "角色管理")
    }
    Button(onClick = nav.onOpenApplications, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
      Text(text = "租户应用")
    }
    Button(onClick = nav.onLogout, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
      Text(text = "退出登录")
    }
  }
}
