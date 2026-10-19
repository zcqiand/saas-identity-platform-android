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
import io.xr.harness.app.loginSeam
import io.xr.harness.core.ApiGlue
import io.xr.harness.core.SessionState
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
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
  val store = remember { SessionStore(SharedPreferencesStore(context)) }
  val tokens = remember { TokenHolder() }
  val scope = rememberCoroutineScope()
  val vm = remember { AuthViewModel(store, AuthViewModel.Seams(login = loginSeam(store, tokens))) }
  val session by vm.session.collectAsState()

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
        AccountScreen(
            ready = s,
            onLogout = { scope.launch { vm.logout() } },
        )
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

@Composable
fun AccountScreen(ready: SessionState.Ready, onLogout: () -> Unit) {
  Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
  ) {
    Text(text = "已登录", style = MaterialTheme.typography.headlineMedium)
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = "用户：${ready.username}")
    ready.tenantId?.let { Text(text = "租户：$it") }
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text(text = "退出登录") }
  }
}
