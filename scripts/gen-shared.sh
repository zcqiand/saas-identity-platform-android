#!/usr/bin/env bash
# scripts/gen-shared.sh — 从家族 shared 契约生成 typed client + DTO（openapi-generator kotlin/jvm-ktor）
#
# 三层生成铁律（suite 硬规则 §4「API 面只认生成物」）：
#   整棵生成区 → app/src/main/kotlin/saas/identity/shared/ 一棵树（本脚本）：
#     api/               API 缝（suspend fun 返回 HttpResponse<T>，直接进 ViewModel）
#     models/            @Serializable DTO
#     auth/              鉴权方案桩（ApiKey/HttpBasic/HttpBearer/OAuth）
#     infrastructure/    ApiClient + RequestConfig + HttpResponse 等传输基建
#   布局事实与选型依据见 suite 仓 ADR-0052（kotlin/jvm-ktor spike 转正）。
#
# 生成区每次 regen 先删后写——手改会被机械抹掉；禁止任何绕过脚本的手工编辑。
#
# 用法：bash scripts/gen-shared.sh   （无 DB 依赖；L4.codegen.idempotent strict 复放本脚本）

set -euo pipefail

# 本仓是 submodule：git rev-parse 返回外层 suite 根，禁用（fastapi 同款坑）
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.."

SHARED_PKG=app/src/main/kotlin/saas/identity/shared
SHARED_DIR="$(cd ../saas-identity-platform-shared && pwd)"
GEN_TMP=.openapi-tmp/gen
ZONE_ARGS=(-- "$SHARED_PKG")

echo "[gen-shared] step 1/4 — shared 仓 emit:openapi（TypeSpec SSOT 单 emit）"
(cd "$SHARED_DIR" && npm run emit:openapi)

echo "[gen-shared] step 2/4 — openapi-generator kotlin/jvm-ktor（openapitools.json 钉 7.25.0）"
rm -rf "$GEN_TMP"
npx --yes @openapitools/openapi-generator-cli generate \
  -g kotlin \
  --library jvm-ktor \
  -i "$SHARED_DIR/generated/openapi/openapi.yaml" \
  -o "$GEN_TMP" \
  --package-name saas.identity.shared \
  --model-package saas.identity.shared.models \
  --api-package saas.identity.shared.api \
  --additional-properties=hideGenerationTimestamp=true \
  --additional-properties=serializationLibrary=kotlinx_serialization \
  --type-mappings "object=JsonElement,kotlin.Any=kotlinx.serialization.json.JsonElement" \
  --import-mappings "UUID=kotlin.uuid.Uuid,JsonElement=kotlinx.serialization.json.JsonElement"

echo "[gen-shared] step 3/4 — 搬运生成区（显式白名单四件，先删后写；ADR-0052 client 布局）"
GEN_SHARED="$GEN_TMP/src/main/kotlin/saas/identity/shared"
for d in "$GEN_SHARED/api" "$GEN_SHARED/models" "$GEN_SHARED/auth"; do
  if [ ! -d "$d" ] || [ -z "$(find "$d" -name '*.kt' -print -quit)" ]; then
    echo "[gen-shared] FATAL: 生成产物包目录缺失或为空：$d（布局漂移？对 ADR-0052）" >&2
    exit 1
  fi
done
if [ ! -f "$GEN_SHARED/infrastructure/ApiClient.kt" ]; then
  echo "[gen-shared] FATAL: 生成产物支撑件缺失：$GEN_SHARED/infrastructure/ApiClient.kt（布局漂移？对 ADR-0052）" >&2
  exit 1
fi
rm -rf "$SHARED_PKG"
# 后处理（python，禁 sed 拼 \n——Git Bash sed 不展开替换串里的 \n，
# 2026-10-19 实证把 package 行糊成 packagesaas...，见 suite 记忆 android-stack-ledger）：
#   1) Uuid OptIn：import-mapping 落 kotlin.uuid.Uuid，在 Kotlin 2.2.10 是实验态 API，
#      含它的文件须 @file:OptIn(ExperimentalUuidApi)（ktor 栈 2.4.21 已转正故无此步）
#   2) 空名枚举项定点补名：generator 对空串枚举值给空标识符（产非法 Kotlin `("")`）；
#      @SerialName 保 wire 值，补名对齐 java generator 的 EMPTY 惯例。无命中即无操作，天然幂等。
find "$GEN_SHARED" -name '*.kt' -print0 | python -c '
import sys, re
for path in sys.stdin.buffer.read().split(b"\0"):
    if not path:
        continue
    p = path.decode("utf-8")
    with open(p, encoding="utf-8") as f:
        text = f.read()
    orig = text
    if "kotlin.uuid.Uuid" in text and "ExperimentalUuidApi" not in text:
        text = re.sub(r"^(package .*)$",
                      "@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)\n\n\\1",
                      text, count=1, flags=re.M)
    text = re.sub(r"^    (\"\")([,;]?)$", r"    EMPTY(\"\")\1", text, flags=re.M)
    if text != orig:
        with open(p, "w", encoding="utf-8", newline="") as f:
            f.write(text)
'
mkdir -p "$SHARED_PKG"
cp -r "$GEN_SHARED/api" "$SHARED_PKG/api"
cp -r "$GEN_SHARED/models" "$SHARED_PKG/models"
cp -r "$GEN_SHARED/auth" "$SHARED_PKG/auth"
cp -r "$GEN_SHARED/infrastructure" "$SHARED_PKG/infrastructure"
rm -rf .openapi-tmp

# 漂移 fail-loud（fastapi 同款）：生成区与 git HEAD 不一致即红。untracked 也算——
# 防生成漏 git add 后门禁读旧产物假绿（池项 5.31 教训）。
if ! git diff --exit-code --quiet "${ZONE_ARGS[@]}" 2>/dev/null \
  || [ -n "$(git ls-files --others --exclude-standard -- "$SHARED_PKG")" ]; then
  echo "[gen-shared] FATAL: 生成区与 git HEAD 不一致（含 untracked 新文件）" >&2
  echo "[gen-shared]        处理：确认 shared .tsp 变更合法后 git add 生成区并 commit，再重跑本脚本核绿" >&2
  exit 1
fi
echo "[gen-shared] OK — 生成区已与 HEAD 同步（API 面只认生成物，硬规则 §4）"
