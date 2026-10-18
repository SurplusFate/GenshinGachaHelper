pluginManagement {
    repositories {
        // 沙箱/国内网络环境：官方源（dl.google.com / plugins.gradle.org）常被拦截，
        // 优先走可达的阿里云镜像，缺的构件从镜像补、已缓存的命中本地缓存。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "GenshinGachaHelper"
include(":app")