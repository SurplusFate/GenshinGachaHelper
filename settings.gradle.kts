pluginManagement {
    repositories {
        // 阿里云镜像只给国内本地环境用。GitHub Actions 是境外 runner，
        // 镜像排在官方源之前会让 Gradle 在 KSP 插件解析阶段直接 not found
        // （2026-09-21 已在 7dbc31a 踩过一次，v1.9.32 合并时又被带回来）。
        // 这里用 CI 环境变量二分，两边各走各的。
        if (System.getenv("CI") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "GenshinGachaHelper"
include(":app")