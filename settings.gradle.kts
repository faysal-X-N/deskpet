pluginManagement {
    repositories {
        // 阿里云国内镜像 — Google
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        // 阿里云国内镜像 — Maven Central
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        // 阿里云国内镜像 — Gradle Plugin
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 阿里云国内镜像 — JCenter（兜底）
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        maven { url = uri("https://jitpack.io") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云国内镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "DeskPet"
include(":app")
