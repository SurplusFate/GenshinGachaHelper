import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// ===== 签名凭据读取 =====
// 优先级：环境变量 > local.properties。
// local.properties 已被 .gitignore 屏蔽，真实 keystore 口令不会进入版本库；
// CI 侧改用随机生成的一次性口令（见 .github/workflows/android.yml）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(propName: String, envName: String): String? =
    keystoreProps.getProperty(propName) ?: System.getenv(envName)

// release.keystore 不入库。只有同时取到 keystore 文件与口令时才启用 release 签名；
// 否则 release 产物为 unsigned，而 assembleDebug 始终可用（不再因缺 keystore 直接失败）。
val releaseKeystore: File? = signingValue("keystore.file", "KEYSTORE_FILE")
    ?.let { file(it) }
    ?.takeIf { it.exists() }
    ?: rootProject.file("release.keystore").takeIf { it.exists() }
val releaseStorePassword: String? = signingValue("keystore.storePassword", "KEYSTORE_STORE_PASSWORD")
val releaseKeyAlias: String = signingValue("keystore.keyAlias", "KEYSTORE_KEY_ALIAS") ?: "gacha-release"
val releaseKeyPassword: String? = signingValue("keystore.keyPassword", "KEYSTORE_KEY_PASSWORD")
val hasReleaseSigning: Boolean = releaseKeystore != null && !releaseStorePassword.isNullOrBlank()

android {
    namespace = "com.genshin.gachahelper"
    compileSdk = 36

    signingConfigs {
        // 口令/别名一律外置，禁止硬编码入库；凭据缺失时不创建签名配置
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.genshin.gachahelper"
        minSdk = 26
        targetSdk = 36
        versionCode = 65
        versionName = "1.9.4"

        // 无 androidTest 源码集，不再声明悬空的 instrumentation runner
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            // 使用 Android 默认 debug keystore：全新 clone 无需任何签名配置即可 assembleDebug
        }
        release {
            isMinifyEnabled = true
            // 未提供签名凭据时不设置 signingConfig，产物为 unsigned
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        // AuthViewModel 等处以 BuildConfig.DEBUG 门控调试信息，需显式开启
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        // AppLog 会调用 android.util.Log，JVM 单测中未 mock 会抛 RuntimeException；
        // 让未 mock 的 Android 方法返回默认值，保证 DsSignerTest 等纯逻辑测试可运行
        unitTests.isReturnDefaultValues = true
    }
}

// Room schema 导出目录（跟踪数据库结构演进）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kyant.backdrop)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Paging
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)

    // Kotlin
    implementation(libs.kotlinx.coroutines.android)

    // Gson
    implementation(libs.gson)

    // QR Code
    implementation(libs.zxing.core)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
