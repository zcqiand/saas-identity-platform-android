# SaaS 身份平台 Android 端

SaaS 身份平台 Android 端（`saas-identity-platform-android`）——一句话系统定位（待补）。

本仓为《（书稿信息待补）》案例（待补）的可运行配套工程，是书稿代码块的 **source of truth**。

## 快速开始

```bash
# 按 android 栈替换为实际命令
npm install        # 安装依赖
npm test           # 全量测试（无 Key / 无 Docker / 无网可跑）
npm run dev        # 本地开发
npm run build      # 生产构建
```

## env 契约

| 键 | 语义 | 缺失行为 |
| :--- | :--- | :--- |
| `API_BASE_URL` | 后端 API 基址。dev 值 `http://localhost:5104`（X04 aspnetcore 槽，后端切换归 backend-config 切换器） | **抛错**（fail-fast，禁止默认值兜底，suite-hard-rules §1） |

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
