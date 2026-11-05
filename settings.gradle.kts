// 仓库策略：国内默认走阿里云镜像（快），CI（海外 runner）直接走官方源。
//
// 为什么必须分叉：Gradle 只在仓库返回 **404** 时才换下一个源；返回 5xx
// （阿里云实测出现过 502 Bad Gateway）会**直接中止整次解析**，镜像的每次抖动
// 都会让流水线在 `configuring root project` 阶段失败，连编译都到不了。
// 2026-11-05 的 CI #31 就是这么挂的——与代码无关，纯镜像故障。
//
// GitHub Actions 默认注入 CI=true，所以 CI 上自动走官方源；
// 本地想强制走官方源：DESKPET_MAVEN=official ./gradlew ...
//
// 注意：pluginManagement 必须是本文件的第一个语句块，因此这里的判断写在块内。

pluginManagement {
    val officialOnly = System.getenv("CI") != null ||
        System.getenv("DESKPET_MAVEN") == "official"
    repositories {
        if (!officialOnly) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    val officialOnly = System.getenv("CI") != null ||
        System.getenv("DESKPET_MAVEN") == "official"
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!officialOnly) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "DeskPet"
include(":app")
