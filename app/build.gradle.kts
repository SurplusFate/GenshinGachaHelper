import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

android {
    namespace = "com.genshin.gachahelper"
    compileSdk = 36

    signingConfigs {
        create("release") {
            // 口令/别名一律外置，禁止硬编码入库
            storeFile = file(signingValue("keystore.file", "KEYSTORE_FILE") ?: "$rootDir/release.keystore")
            storePassword = signingValue("keystore.storePassword", "KEYSTORE_STORE_PASSWORD")
            keyAlias = signingValue("keystore.keyAlias", "KEYSTORE_KEY_ALIAS") ?: "gacha-release"
            keyPassword = signingValue("keystore.keyPassword", "KEYSTORE_KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.genshin.gachahelper"
        minSdk = 26
        targetSdk = 36
        versionCode = 55
        versionName = "1.8.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            // 与 release 使用同一签名密钥，避免 debug / release 包签名不一致导致覆盖安装失败
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
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
    implementation(libs.okhttp.logging)

    // Kotlin
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Image
    implementation(libs.coil.compose)

    // Gson
    implementation(libs.gson)

    // QR Code
    implementation(libs.zxing.core)

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
