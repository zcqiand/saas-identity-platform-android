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

## 快速链接

- [CLAUDE.md](CLAUDE.md) — 开发约定与编码规范
- [系统架构.md](docs/ARCHITECTURE.md) — 结构 / 边界 / 数据流 / 决策
- [功能规格.md](docs/functions/function-tree.md) — 功能名称、描述与验收标准
- [未来开发计划](PLAN.md) — 待办与迭代方向
- [更新日志](CHANGELOG.md) — 版本变更记录
