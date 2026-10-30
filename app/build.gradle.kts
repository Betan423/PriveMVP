import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Built-in public assist endpoint (see `make public`). Read from local.properties or the environment,
// never from git: the repo is public and the token only keeps strangers off the Gemini proxy.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun buildSetting(key: String, env: String): String =
    (System.getenv(env) ?: localProps.getProperty(key) ?: "").trim()
fun kotlinString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.example.privagentcollector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.privagentcollector"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ASSIST_BASE_URL", kotlinString(buildSetting("privagent.assistUrl", "PRIVAGENT_ASSIST_URL")))
        buildConfigField("String", "ASSIST_TOKEN", kotlinString(buildSetting("privagent.assistToken", "PRIVAGENT_ASSIST_TOKEN")))
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests; android.jar only ships stubs.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
