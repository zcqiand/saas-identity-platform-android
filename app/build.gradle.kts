plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
}

android {
  namespace = "io.xr.harness"
  compileSdk = 37

  defaultConfig {
    applicationId = "io.xr.harness"
    minSdk = 29
    targetSdk = 37
    versionCode = 1
    versionName = "0.1.0"
  }

  buildFeatures { compose = true }

  // 骨架期不出 release 件；assembleDebug 是 L3 诚实断言，签名配置 Phase 1+ 再立
  buildTypes { release { isMinifyEnabled = false } }
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.core)

  // 生成区依赖收口 ktor-bom（ktor 家族仓同款纪律）：版本一处管，禁裸写。
  implementation(platform(libs.ktor.bom))
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.client.auth)
  implementation(libs.ktor.serialization.kotlinx.json)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  // trace listener 直接 import platform API（ktor 同款）：JUnit 6 起 launcher 必须显式声明
  testImplementation(libs.junit.platform.engine)
  testImplementation(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  // trace_cmd 的 -Dharness.trace=true 落在 Gradle JVM；显式传给测试 JVM（ktor 同款）。
  // 工作目录钉 rootDir：HarnessTraceListener 写相对路径 .state/trace.json（ktor 契约件
  // 零改动），多模块布局下测试 JVM 默认 cwd=模块目录会落错位（app/.state/）。
  workingDir = rootDir
  systemProperty("harness.trace", System.getProperty("harness.trace"))
}

detekt {
  buildUponDefaultConfig = true
  config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
  // 生成区（Phase 1 codegen 落地处）禁手改、不进静态门；占位符由 init_project.py 渲染
  // 成本家族生成区路径。app 模块布局，source 相对 app/ 目录。
  source.setFrom(
      fileTree("src/main/kotlin") { exclude("saas/identity/shared/**", "saas/identity/entity/**") },
      fileTree("src/test/kotlin"),
  )
}

// ktfmt 生成区排除走任务级 exclude（ktfmt 0.27.0 srcSetPathExclusionPattern 只认 source
// set 路径的实证结论，ktor 同款）；app 模块相对路径带 app/ 前缀。
tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtBaseTask>().configureEach {
  exclude("saas/identity/shared/**")
  exclude("saas/identity/entity/**")
}
