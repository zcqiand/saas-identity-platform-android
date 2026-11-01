# SaaS 身份平台 Android 端

SaaS 身份平台 Android 端（`saas-identity-platform-android`）——一句话系统定位（待补）。

本仓为《（书稿信息待补）》案例（待补）的可运行配套工程，是书稿代码块的 **source of truth**。

## 快速开始

```bash
export PATH="/d/qiand-life/3-resources/8-ego/software/gradle-9.8.1/bin:$PATH"
export ANDROID_HOME="D:\qiand-life\3-resources\8-ego\software\android-sdk"
gradle test            # JVM 单测（无设备依赖）
gradle assembleDebug   # 构建 APK
```

> 依赖镜像与 wrapper 纪律见 CLAUDE.md；禁仓内 gradlew 入门（全局 gradle PATH）。

## 运行配置（应用内配置页）

后端基址与 clientId 不走 env，应用首启进**配置页**手填（swift 仓 ConfigView 同构；
缺失即拦，禁止默认值兜底，suite-hard-rules §1）：

| 项 | dev 值 | 说明 |
| :--- | :--- | :--- |
| API 地址 | `http://10.0.2.2:5104`（模拟器内访问宿主机）/ `http://localhost:5104`（宿主机直跑） | X04 aspnetcore 槽，后端切换归 backend-config 切换器 |
| clientId | IdP 侧已注册的 client 标识 | 必填业务身份字段（suite-hard-rules §3），android client 注册归 live 接线刀 |

## 功能特性

按特性分点描述核心功能（见 `docs/functions/function-tree.md`）。

## 技术栈

| 技术 | 版本 |
| :--- | :--- |
| Kotlin（AGP 9.4.1 内置）+ Jetpack Compose（BOM）+ Ktor Client（ktor-bom，生成区传输栈）+ kotlinx-serialization-json | 2.2.10 / 2026.09.00 / 3.6.0 / 1.11.0 |

> 依赖版本与 `version-lock.json` 的 `version_lock` 一致，不引入 lock 外的库。

## 配套书籍及章节映射

| 章 | 主题 | 对应源文件 |
| :--- | :--- | :--- |
| （待补） | | |

### 书一《Android 从入门到项目实践》（卷4）

- 书稿定位：xr-know-021，第 25、30、31、32、33、34 章正文代码块挂本仓 `source=` 锚点
- 配套版本：`v0.1.0-20261031`（registry 冻结 tag，代码以此为准）

| 章 | 章题 | 对应源文件 |
| :--- | :--- | :--- |
| 25 | 与后端联调实战 | `app/src/main/kotlin/saas/identity/shared/api/AuthApi.kt` + `app/src/main/kotlin/saas/identity/shared/api/OauthApi.kt` + `app/src/main/kotlin/saas/identity/shared/infrastructure/ApiClient.kt` + `app/src/main/kotlin/saas/identity/shared/infrastructure/RequestConfig.kt` + `app/src/main/kotlin/io/xr/harness/core/ApiGlue.kt` + `app/src/main/kotlin/saas/identity/shared/models/TokenRequest.kt` |
| 30 | 案例二：SaaS 架构与多租户 | `app/src/main/kotlin/saas/identity/shared/api/AdminTenantsApi.kt` + `app/src/main/kotlin/io/xr/harness/app/AdminTenantsWire.kt` + `app/src/main/kotlin/io/xr/harness/ui/AdminTenantsViewModel.kt` + `app/src/main/kotlin/io/xr/harness/TenantScreens.kt` + `app/src/main/kotlin/saas/identity/shared/models/Tenant.kt` + `app/src/main/kotlin/saas/identity/shared/models/CreateTenantRequest.kt` + `app/src/main/kotlin/saas/identity/shared/models/AdminTenantsListTenants200Response.kt` + `app/src/main/kotlin/io/xr/harness/app/LoginWire.kt` + `app/src/main/kotlin/io/xr/harness/ui/AuthViewModel.kt` + `app/src/main/kotlin/io/xr/harness/MainActivity.kt` + `app/src/main/kotlin/io/xr/harness/core/SessionStore.kt` + `app/src/main/kotlin/saas/identity/shared/models/SwitchTenantResponse.kt` + `app/src/main/kotlin/saas/identity/shared/api/MeApi.kt` + `app/src/test/kotlin/io/xr/harness/ui/AdminTenantsViewModelTest.kt` + `app/src/test/kotlin/io/xr/harness/ui/AuthViewModelTest.kt` |
| 31 | 案例二：登录认证与 SSO | `app/src/main/kotlin/saas/identity/shared/api/AuthApi.kt` + `app/src/main/kotlin/saas/identity/shared/api/OauthApi.kt` + `app/src/main/kotlin/io/xr/harness/app/LoginWire.kt` + `app/src/main/kotlin/io/xr/harness/ui/AuthViewModel.kt` + `app/src/main/kotlin/io/xr/harness/app/EncryptedTokenStore.kt` + `app/src/main/kotlin/io/xr/harness/core/SessionStore.kt` + `app/src/main/kotlin/saas/identity/shared/models/LoginRequest.kt` + `app/src/main/kotlin/saas/identity/shared/models/LoginResponse.kt` + `app/src/main/kotlin/saas/identity/shared/models/AuthorizeCodeRequest.kt` + `app/src/main/kotlin/saas/identity/shared/models/OAuthAuthorize200Response.kt` + `app/src/main/kotlin/saas/identity/shared/models/TokenRequest.kt` + `app/src/main/kotlin/saas/identity/shared/models/TokenResponse.kt` + `app/src/test/kotlin/io/xr/harness/ui/AuthViewModelTest.kt` + `app/src/test/kotlin/io/xr/harness/ui/OAuthViewModelTest.kt` + `app/src/test/kotlin/io/xr/harness/app/EncryptedTokenStoreTest.kt` + `app/src/test/kotlin/io/xr/harness/core/SessionStoreTest.kt` |
| 32 | 案例二：角色与菜单授权（RBAC） | `app/src/main/kotlin/saas/identity/shared/api/TenantRolesApi.kt` + `app/src/main/kotlin/saas/identity/shared/api/TenantRoleMenusApi.kt` + `app/src/main/kotlin/saas/identity/shared/api/ClientMenusApi.kt` + `app/src/main/kotlin/io/xr/harness/app/RolesWire.kt` + `app/src/main/kotlin/io/xr/harness/ui/RolesViewModel.kt` + `app/src/main/kotlin/io/xr/harness/RolesScreens.kt` + `app/src/main/kotlin/io/xr/harness/RolesDialogs.kt` + `app/src/main/kotlin/saas/identity/shared/models/SysRole.kt` + `app/src/main/kotlin/saas/identity/shared/models/CreateSysRoleRequest.kt` + `app/src/main/kotlin/saas/identity/shared/models/UpdateSysRoleRequest.kt` + `app/src/main/kotlin/saas/identity/shared/models/RoleMenuGrant.kt` + `app/src/main/kotlin/saas/identity/shared/models/SetSysRoleMenusRequest.kt` + `app/src/main/kotlin/io/xr/harness/core/MenuTree.kt` + `app/src/main/kotlin/saas/identity/shared/models/EffectiveMenuNode.kt` + `app/src/test/kotlin/io/xr/harness/ui/RolesViewModelTest.kt` + `app/src/test/kotlin/io/xr/harness/core/MenuTreeTest.kt` |
| 33 | 案例二：成员管理与应用集成 | `app/src/main/kotlin/saas/identity/shared/api/TenantMembersApi.kt` + `app/src/main/kotlin/io/xr/harness/app/MembersWire.kt` + `app/src/main/kotlin/io/xr/harness/ui/MembersViewModel.kt` + `app/src/main/kotlin/io/xr/harness/MembersScreens.kt` + `app/src/main/kotlin/io/xr/harness/MembersDialogs.kt` + `app/src/main/kotlin/saas/identity/shared/models/TenantMember.kt` + `app/src/main/kotlin/saas/identity/shared/models/TenantMemberView.kt` + `app/src/main/kotlin/saas/identity/shared/models/TenantMembersListTenantUsers200Response.kt` + `app/src/main/kotlin/saas/identity/shared/models/TenantMembersInviteTenantUserRequest.kt` + `app/src/main/kotlin/saas/identity/shared/models/TenantMembersChangeTenantUserStatusRequest.kt` + `app/src/main/kotlin/saas/identity/shared/models/SetTenantMemberRolesRequest.kt` + `app/src/main/kotlin/saas/identity/shared/api/TenantApplicationsApi.kt` + `app/src/main/kotlin/io/xr/harness/app/ApplicationsWire.kt` + `app/src/main/kotlin/io/xr/harness/ui/TenantApplicationsViewModel.kt` + `app/src/main/kotlin/io/xr/harness/ApplicationsScreens.kt` + `app/src/main/kotlin/saas/identity/shared/models/TenantApplication.kt` + `app/src/main/kotlin/saas/identity/shared/models/SubscribeTenantApplicationRequest.kt` + `app/src/main/kotlin/saas/identity/shared/api/MeApi.kt` + `app/src/main/kotlin/saas/identity/shared/models/TenantMembership.kt` + `app/src/test/kotlin/io/xr/harness/ui/MembersViewModelTest.kt` + `app/src/test/kotlin/io/xr/harness/ui/TenantApplicationsViewModelTest.kt` + `app/src/test/kotlin/io/xr/harness/ui/AuthViewModelTest.kt` |
| 34 | 案例二：联调验收与生产发布 | `app/build.gradle.kts` + `gradle/libs.versions.toml` + `app/src/test/kotlin/io/xr/harness/Fn.kt` + `app/src/test/kotlin/io/xr/harness/FnAnnotationSmokeTest.kt` + `app/src/test/kotlin/io/xr/harness/HarnessTraceListener.kt` + `app/src/test/kotlin/io/xr/harness/core/SessionStoreTest.kt` + `app/src/test/kotlin/io/xr/harness/ui/AuthViewModelTest.kt` + `app/src/test/kotlin/io/xr/harness/ui/AdminTenantsViewModelTest.kt` |
## 快速链接

- [CLAUDE.md](CLAUDE.md) — 开发约定与编码规范
- [系统架构.md](docs/ARCHITECTURE.md) — 结构 / 边界 / 数据流 / 决策
- [功能规格.md](docs/functions/function-tree.md) — 功能名称、描述与验收标准
- [未来开发计划](PLAN.md) — 待办与迭代方向
- [更新日志](CHANGELOG.md) — 版本变更记录
