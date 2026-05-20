import org.gradle.api.Project
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun loadDotEnv(file: File): Map<String, String> {
    if (!file.exists() || !file.isFile) {
        return emptyMap()
    }
    return file
        .readLines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@mapNotNull null
            }
            val withoutExport = trimmed.removePrefix("export ").trim()
            val separatorIndex = withoutExport.indexOf('=')
            if (separatorIndex <= 0) {
                return@mapNotNull null
            }
            val key = withoutExport.substring(0, separatorIndex).trim()
            var value = withoutExport.substring(separatorIndex + 1).trim()
            if (
                value.length >= 2 &&
                ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
            ) {
                value = value.substring(1, value.length - 1)
            }
            key.takeIf { it.isNotEmpty() }?.let { it to value }
        }
        .toMap()
}

val dotEnvValues = loadDotEnv(rootProject.file(".env"))

fun Project.resolveConfigValue(
    defaultValue: String = "",
    vararg keys: String,
): String {
    keys.forEach { key ->
        providers.gradleProperty(key).orNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    keys.forEach { key ->
        providers.environmentVariable(key).orNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    keys.forEach { key ->
        dotEnvValues[key]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    return defaultValue
}

fun Project.resolveIntConfig(
    defaultValue: Int,
    vararg keys: String,
): Int = resolveConfigValue(defaultValue.toString(), *keys).toIntOrNull() ?: defaultValue

val agentApiBaseUrl = resolveConfigValue("", "AGENT_API_BASE_URL", "agentApiBaseUrl")
val agentApiKey = resolveConfigValue("", "AGENT_API_KEY", "agentApiKey")
val agentModel = resolveConfigValue("", "AGENT_MODEL", "agentModel")
val agentRouteModel = resolveConfigValue("", "AGENT_ROUTE_MODEL", "agentRouteModel")
val agentWebSearchBaseUrl = resolveConfigValue("", "AGENT_WEB_SEARCH_BASE_URL", "agentWebSearchBaseUrl")
val agentMaxTurns = resolveIntConfig(12, "AGENT_MAX_TURNS", "agentMaxTurns")
val agentPlannerConnectTimeoutMs = resolveIntConfig(20000, "AGENT_PLANNER_CONNECT_TIMEOUT_MS", "agentPlannerConnectTimeoutMs")
val agentPlannerReadTimeoutMs = resolveIntConfig(90000, "AGENT_PLANNER_READ_TIMEOUT_MS", "agentPlannerReadTimeoutMs")
val agentRouteConnectTimeoutMs = resolveIntConfig(12000, "AGENT_ROUTE_CONNECT_TIMEOUT_MS", "agentRouteConnectTimeoutMs")
val agentRouteReadTimeoutMs = resolveIntConfig(25000, "AGENT_ROUTE_READ_TIMEOUT_MS", "agentRouteReadTimeoutMs")

android {
    namespace = "com.lmx.xiaoxuanagent"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.lmx.xiaoxuanagent"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "AGENT_API_BASE_URL", buildConfigString(agentApiBaseUrl))
        buildConfigField("String", "AGENT_API_KEY", buildConfigString(agentApiKey))
        buildConfigField("String", "AGENT_MODEL", buildConfigString(agentModel))
        buildConfigField("String", "AGENT_ROUTE_MODEL", buildConfigString(agentRouteModel))
        buildConfigField("String", "AGENT_WEB_SEARCH_BASE_URL", buildConfigString(agentWebSearchBaseUrl))
        buildConfigField("int", "AGENT_MAX_TURNS", agentMaxTurns.toString())
        buildConfigField("int", "AGENT_PLANNER_CONNECT_TIMEOUT_MS", agentPlannerConnectTimeoutMs.toString())
        buildConfigField("int", "AGENT_PLANNER_READ_TIMEOUT_MS", agentPlannerReadTimeoutMs.toString())
        buildConfigField("int", "AGENT_ROUTE_CONNECT_TIMEOUT_MS", agentRouteConnectTimeoutMs.toString())
        buildConfigField("int", "AGENT_ROUTE_READ_TIMEOUT_MS", agentRouteReadTimeoutMs.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
