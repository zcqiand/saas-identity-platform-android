package io.xr.harness.config

/**
 * 后端 API 基址。缺失即抛错——禁止默认值兜底（suite-hard-rules §1）。 dev 值见 README（saas=5104 / lab=5204 槽）；Phase 1 接线时按
 * multi-repo-family.md §6 URL 表核对。env 注入便于单测，不引框架。
 */
class AppConfig(private val env: Map<String, String> = System.getenv()) {

  fun apiBaseUrl(): String =
      checkNotNull(env["API_BASE_URL"]) {
        "API_BASE_URL 未设置。禁止默认值兜底（suite-hard-rules §1）；dev 值见 README。"
      }
}
