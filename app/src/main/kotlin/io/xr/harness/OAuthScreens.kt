@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.xr.harness

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.xr.harness.app.oauthSeams
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import io.xr.harness.ui.OAuthViewModel
import kotlinx.coroutines.launch

/**
 * OAuth 授权码流屏（swift OAuthView 同构，M04.F03，AC-1~AC-4）三段：签发授权码（code + state 回显）→ 换 token（store 入账）→
 * 刷新（轮换入账）。 token 对/租户渲染自 SessionStore（入账后经 tick 触发重读）；失败红字、原会话不动可重试。 redirectUri/scope
 * 固定默认值（非范围：可编辑 UI）。
 */
@Composable
fun OAuthScreen(onBack: () -> Unit, store: SessionStore, tokens: TokenHolder) {
  val scope = rememberCoroutineScope()
  val vm = remember { OAuthViewModel(store, oauthSeams(store, tokens)) }
  val phase by vm.phase.collectAsState()
  val lastCode by vm.lastCode.collectAsState()
  val lastState by vm.lastState.collectAsState()
  val busy = phase is OAuthViewModel.Phase.Busy
  val failed = phase as? OAuthViewModel.Phase.Failed
  // store 非可观察：换码/刷新入账后自增触发 token 行重读（swift session.refresh() 同位）
  var tick by remember { mutableIntStateOf(0) }

  Column(
      modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
  ) {
    Row(modifier = Modifier.fillMaxWidth()) {
      Button(onClick = onBack, enabled = !busy) { Text(text = "返回") }
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = "OAuth 授权码", style = MaterialTheme.typography.headlineMedium)
    }
    AuthorizeSection(
        busy = busy,
        lastCode = lastCode,
        lastState = lastState,
        onAuthorize = { scope.launch { vm.authorize() } },
    )
    SectionDivider()
    TokenSection(
        busy = busy,
        hasCode = lastCode != null,
        store = store,
        onExchange = {
          scope.launch {
            if (vm.exchangeCode()) {
              tick++
            }
          }
        },
    )
    SectionDivider()
    RefreshSection(
        busy = busy,
        hasRefresh = !store.refreshToken.isNullOrBlank(),
        onRefresh = {
          scope.launch {
            if (vm.refreshToken()) {
              tick++
            }
          }
        },
    )
    failed?.let {
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = it.message, color = MaterialTheme.colorScheme.error)
    }
  }
}

/** 第一步 authorize：签发 + code/state 回显（state 为本端生成且回传校验一致者）。 */
@Composable
private fun AuthorizeSection(
    busy: Boolean,
    lastCode: String?,
    lastState: String?,
    onAuthorize: () -> Unit,
) {
  SectionTitle(text = "第一步 · authorize")
  Button(onClick = onAuthorize, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
    Text(text = "签发授权码")
  }
  if (lastCode != null) {
    MonospaceRow(label = "授权码", value = lastCode)
    MonospaceRow(label = "state（回传一致）", value = lastState ?: "—")
  } else {
    Text(text = "尚未签发", color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  FooterNote(text = "state 自生成并要求后端回传一致（CSRF）；scope 恒传 openid")
}

/** 第二步 token（authorization_code）：换码入账 + token 对/租户渲染自 store（父层 tick 变更触发重读）。 */
@Composable
private fun TokenSection(
    busy: Boolean,
    hasCode: Boolean,
    store: SessionStore,
    onExchange: () -> Unit,
) {
  SectionTitle(text = "第二步 · token（authorization_code）")
  Button(
      onClick = onExchange,
      enabled = !busy && hasCode,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Text(text = "换 token")
  }
  if (store.hasToken) {
    MonospaceRow(label = "accessToken", value = store.accessToken)
    MonospaceRow(label = "refreshToken", value = store.refreshToken)
    MonospaceRow(label = "scope", value = OAuthViewModel.DEFAULT_SCOPE)
    MonospaceRow(label = "tenantId", value = store.tenantId ?: "—")
  } else {
    Text(text = "尚未入账", color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  FooterNote(text = "免 clientSecret（公共 client）")
}

/** 第三步 refresh（轮换）：refresh_token grant 返回全新 token 对并入账。 */
@Composable
private fun RefreshSection(busy: Boolean, hasRefresh: Boolean, onRefresh: () -> Unit) {
  SectionTitle(text = "第三步 · refresh（轮换）")
  Button(onClick = onRefresh, enabled = !busy && hasRefresh, modifier = Modifier.fillMaxWidth()) {
    Text(text = "刷新")
  }
  FooterNote(text = "refresh_token grant 返回全新 token 对并入账")
}

@Composable
private fun SectionTitle(text: String) {
  Text(text = text, style = MaterialTheme.typography.titleMedium)
  Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun MonospaceRow(label: String, value: String?) {
  Text(
      text = "$label：${value ?: "—"}",
      style = MaterialTheme.typography.bodySmall,
      fontFamily = FontFamily.Monospace,
  )
}

@Composable
private fun FooterNote(text: String) {
  Spacer(modifier = Modifier.height(4.dp))
  Text(
      text = text,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun SectionDivider() {
  Spacer(modifier = Modifier.height(12.dp))
  HorizontalDivider()
  Spacer(modifier = Modifier.height(12.dp))
}
