package io.xr.harness.app

import io.ktor.client.statement.bodyAsText
import io.xr.harness.core.ApiGlue
import io.xr.harness.core.AuthError
import io.xr.harness.core.SessionStore
import io.xr.harness.core.TokenHolder
import saas.identity.shared.api.AdminClientsApi
import saas.identity.shared.api.ClientMenusApi
import saas.identity.shared.infrastructure.HttpResponse
import saas.identity.shared.models.ClientMenusMoveSysMenuRequest
import saas.identity.shared.models.CreateSysMenuRequest
import saas.identity.shared.models.OAuthClient
import saas.identity.shared.models.ReorderSysMenuRequest
import saas.identity.shared.models.SysMenu
import saas.identity.shared.models.UpdateSysMenuRequest

/**
 * 菜单管理 7 缝（swift APIGlue 同位，M04.F04 lean slice）：唯一摸 ClientMenusApi / AdminClientsApi(只读) 的地方。
 * clientId 字符串寻址；非 2xx 手解（生成 client 不抛）→ mapHttpError 抛出；传输异常归一 Unreachable。 形状=每缝外层护栏（AuthError
 * 透传 + 归一）+ 私有 call 函数，与 ReceiptWire/LoginWire 同位。
 */
fun menusListSeam(
    store: SessionStore,
    tokens: TokenHolder,
): suspend (String) -> List<SysMenu> {
  return { clientId ->
    try {
      listCall(store, tokens, clientId)
    } catch (e: AuthError) {
      throw e
    } catch (e: Exception) {
      throw AuthError.Unreachable(e)
    }
  }
}

private suspend fun listCall(
    store: SessionStore,
    tokens: TokenHolder,
    clientId: String,
): List<SysMenu> {
  val resp = callMenus(store, tokens).clientMenusListSysMenus(clientId)
  requireOk(resp)
  return resp.body()
}

fun menusCreateSeam(
    store: SessionStore,
    tokens: TokenHolder,
): suspend (String, CreateSysMenuRequest) -> SysMenu {
  return { clientId, request ->
    try {
      createCall(store, tokens, clientId, request)
    } catch (e: AuthError) {
      throw e
    } catch (e: Exception) {
      throw AuthError.Unreachable(e)
    }
  }
}

private suspend fun createCall(
    store: SessionStore,
    tokens: TokenHolder,
    clientId: String,
    request: CreateSysMenuRequest,
): SysMenu {
  val resp = callMenus(store, tokens).clientMenusCreateSysMenu(clientId, request)
  requireOk(resp)
  return resp.body()
}

fun menusUpdateSeam(
    store: SessionStore,
    tokens: TokenHolder,
): suspend (String, String, UpdateSysMenuRequest) -> SysMenu {
  return { clientId, menuId, request ->
    try {
      updateCall(store, tokens, clientId, menuId, request)
    } catch (e: AuthError) {
      throw e
    } catch (e: Exception) {
      throw AuthError.Unreachable(e)
    }
  }
}

private suspend fun updateCall(
    store: SessionStore,
    tokens: TokenHolder,
    clientId: String,
    menuId: String,
    request: UpdateSysMenuRequest,
): SysMenu {
  val resp = callMenus(store, tokens).clientMenusUpdateSysMenu(clientId, menuId, request)
  requireOk(resp)
  return resp.body()
}

fun menusMoveSeam(
    store: SessionStore,
    tokens: TokenHolder,
): suspend (String, String, String?) -> SysMenu {
  return { clientId, menuId, parentId ->
    try {
      moveCall(store, tokens, clientId, menuId, parentId)
    } catch (e: AuthError) {
      throw e
    } catch (e: Exception) {
      throw AuthError.Unreachable(e)
    }
  }
}

private suspend fun moveCall(
    store: SessionStore,
    tokens: TokenHolder,
    clientId: String,
    menuId: String,
    parentId: String?,
): SysMenu {
  val resp =
      callMenus(store, tokens)
          .clientMenusMoveSysMenu(
              clientId,
              menuId,
              ClientMenusMoveSysMenuRequest(parentId = parentId),
          )
  requireOk(resp)
  return resp.body()
}

fun menusReorderSeam(
    store: SessionStore,
    tokens: TokenHolder,
): suspend (String, String, List<String>) -> List<SysMenu> {
  return { clientId, menuId, orderedMenuIds ->
    try {
      reorderCall(store, tokens, clientId, menuId, orderedMenuIds)
    } catch (e: AuthError) {
      throw e
    } catch (e: Exception) {
      throw AuthError.Unreachable(e)
    }
  }
}

private suspend fun reorderCall(
    store: SessionStore,
    tokens: TokenHolder,
    clientId: String,
    menuId: String,
    orderedMenuIds: List<String>,
): List<SysMenu> {
  val resp =
      callMenus(store, tokens)
          .clientMenusReorderSysMenus(
              clientId,
              menuId,
              ReorderSysMenuRequest(orderedMenuIds = orderedMenuIds),
          )
  requireOk(resp)
  return resp.body()
}

fun menusDeleteSeam(
    store: SessionStore,
    tokens: TokenHolder,
): suspend (String, String) -> Unit {
  return { clientId, menuId ->
    try {
      deleteCall(store, tokens, clientId, menuId)
    } catch (e: AuthError) {
      throw e
    } catch (e: Exception) {
      throw AuthError.Unreachable(e)
    }
  }
}

private suspend fun deleteCall(
    store: SessionStore,
    tokens: TokenHolder,
    clientId: String,
    menuId: String,
) {
  val resp = callMenus(store, tokens).clientMenusDeleteSysMenu(clientId, menuId)
  requireOk(resp)
  return resp.body()
}

fun clientsListSeam(
    store: SessionStore,
    tokens: TokenHolder,
): suspend () -> List<OAuthClient> {
  return {
    try {
      val resp = callClients(store, tokens).adminClientsListClients()
      requireOk(resp)
      resp.body().items
    } catch (e: AuthError) {
      throw e
    } catch (e: Exception) {
      throw AuthError.Unreachable(e)
    }
  }
}

/** 菜单 Api 构造（唯一摸 ClientMenusApi 的入口；baseUrl 缺失显式抛）。 */
private suspend fun callMenus(
    store: SessionStore,
    tokens: TokenHolder,
): ClientMenusApi {
  val baseUrl = store.baseUrl ?: throw AuthError.ClientNotConfigured()
  return ClientMenusApi(
      baseUrl = ApiGlue.normalizeBaseURL(baseUrl),
      httpClientConfig = ApiGlue.bearerClientConfig(tokens),
  )
}

private suspend fun callClients(
    store: SessionStore,
    tokens: TokenHolder,
): AdminClientsApi {
  val baseUrl = store.baseUrl ?: throw AuthError.ClientNotConfigured()
  return AdminClientsApi(
      baseUrl = ApiGlue.normalizeBaseURL(baseUrl),
      httpClientConfig = ApiGlue.bearerClientConfig(tokens),
  )
}

/** 非 2xx 手解（生成 client 不抛）→ mapHttpError。 */
private suspend fun <T> requireOk(resp: HttpResponse<T>) {
  if (!resp.success) {
    val bodyText = runCatching { resp.response.bodyAsText() }.getOrNull()
    throw ApiGlue.mapHttpError(resp.status, bodyText)
  }
}
