# CLAUDE.md — SaaS 身份平台 Android 端

> 书稿配套仓 + harness 门禁仓双身份。入口，不是手册。L0 门强制上限 60 行。
> 本仓为《（书稿信息待补）》案例（待补）（SaaS 身份平台 Android 端）的可运行配套工程，是书稿代码块的 **source of truth**。

## 1. 项目定位

SaaS 身份平台 Android 端（`saas-identity-platform-android`，技术栈 `android`）。一句话定位见 README.md。

## 2. 铁律

- **TDD**：每个模块先写失败测试 → 跑确认失败 → 实现 → 跑确认绿 → commit
- **版本钉死**：依赖与 `version-lock.json` 的 `version_lock` 一致；不引入 lock 外的库
- **tag 即放行**：全量回归绿后打 `v<MAJOR>.<MINOR>.<PATCH>-<YYYYMMDD>`（如 `v0.3.54-20260826`）
- **mock-friendly**：安装 + 测试必须在无 Key、无 Docker、无网下全绿
- **功能清单是锚点**：改 `docs/functions/function-tree.md` 走 `/tree-change` 提案，由人批准；
  改功能与改功能清单必须同一个 commit；废弃只改状态，编号永不复用；禁止给 skip 的测试挂功能 ID

- 禁止 env/API base 键写默认值兜底（fail-fast，suite-hard-rules §1；AppConfig 缺失必须抛错）
- 禁止绕过 shared 契约生成物手写 API 面（suite-hard-rules §4，Phase 1 起生效）
- 依赖钉死 version-lock.json（gradle/libs.versions.toml 是唯一钉版面，禁裸写版本）
- 注解处理一律 KSP 禁 kapt（Phase 1 接 Room/Hilt 时生效；新栈无历史包袱）
- Maven 依赖一律走阿里云镜像（settings.gradle.kts repositories 序，禁删镜像位）
- 生成区（`app/src/main/kotlin/saas/identity/shared/`，ADR-0052 typed client）禁手改——regen 走 `bash scripts/gen-shared.sh`（漂移 fail-loud）；detekt/ktfmt 排除已按 saas/identity/shared/saas/identity/entity 钉（ktor 同纪律）

## 3. 技术栈与版本（钉死于 version-lock.json）

Android 原生（Kotlin 2.2.10（AGP 9.4.1 内置，版本随 AGP 无覆盖属性）+ Jetpack Compose BOM 2026.09.00 + AGP 9.4.1 + 全局 Gradle 9.8.1 + JUnit 6.1.3 + ktfmt-gradle 0.27.0 + detekt 1.23.8）。Maven 依赖走阿里云镜像；门命令首词一律 gradle（EXEC_ALLOWLIST 已含；须先装机 ANDROID_HOME+SDK——cmdline-tools 落 3-resources/8-ego/software/android-sdk，ktor 同款前置）；不用仓内 gradlew（NoDefaultCurrentDirectoryInExePath=1 下 cmd 不搜 cwd，ktor 同坑）。X12 槽不分配端口：无 dev server，四门全 CLI，JVM 单测无设备依赖，模拟器不装。明细见 `version-lock.json` 与 README.md 技术栈表。

门禁命令见 `.harness/stack.json`。**不要改它来让门变松。**

## 4. 验收

- 在 **suite 根目录** 跑 `python scripts/gate.py -p saas-identity-platform-android`；exit 0 才算完成
- 本地命令见 README.md「快速开始」

## 5. 指向别处

- 功能清单（唯一锚点） → `docs/functions/function-tree.md`
- 需求 → 任务 → 功能影响 → `docs/requirements/`
- 流程/设计 与功能对齐 → `docs/design/`（人评审，机器只查引用）
- 决策背景 → `docs/adr/`；编码细则 → `docs/conventions/`（不进主上下文）
- 待办与迭代方向 → `PLAN.md`；版本变更 → `CHANGELOG.md`

## 6. 工作循环

0. **开工前分诊**：先过 `using-skills`，把激活 skill 的清单落成 todo。
   顺序：规格(brainstorming)→计划(writing-plans)→测试先红(red-first)→实现(executing-plans)
1. 读 `.state/session.json` 恢复上下文
2. 最小改动
3. 跑 `python scripts/gate.py -p saas-identity-platform-android`；exit 1 回到第 2 步；exit 2 停下问人
4. `/handoff` 更新 `.state/session.json`

> 家族值表与治理规则：suite docs/families/saas.md 与 docs/conventions/multi-repo-family.md §10
