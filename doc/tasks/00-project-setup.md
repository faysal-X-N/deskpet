# 任务 00 — 项目搭建

> 依赖：无（最先执行）  
> 阻塞：所有后续任务

## 任务列表

- [ ] **T00-01 创建 Gradle 项目结构**
  - **描述**：使用 Android Studio 模板创建 Kotlin + Compose 空项目，配置包名 `com.deskpet`
  - **输入**：无
  - **输出**：`build.gradle.kts`（根+app）、`settings.gradle.kts`、`gradle.properties`
  - **验收**：`./gradlew assembleDebug` 构建成功

- [ ] **T00-02 配置 build.gradle.kts 依赖**
  - **描述**：按详细设计 §4.3 添加所有依赖（Compose、DataStore、Coil+GIF、Coroutines、kotlinx-serialization、lifecycle-service）
  - **输入**：T00-01 的初始 build.gradle.kts
  - **输出**：`app/build.gradle.kts`（含完整依赖）
  - **验收**：Gradle sync 成功，无 unresolved reference

- [ ] **T00-03 创建 AndroidManifest.xml**
  - **描述**：按详细设计 §4 声明权限（SYSTEM_ALERT_WINDOW）和服务（PetOverlayService，stopWithTask=true）
  - **输入**：T00-01 的初始 manifest
  - **输出**：`app/src/main/AndroidManifest.xml`
  - **验收**：编译通过，manifest 无语法错误

- [ ] **T00-04 创建 DeskPetApp（Application 类）**
  - **描述**：按详细设计 §5 创建 Application 类，初始化 PetStore→PetRepository、ImageDecoder、Coil
  - **输入**：T00-02（Coil 依赖）、T00-03（manifest 需引用）
  - **输出**：`app/src/main/java/com/deskpet/DeskPetApp.kt`
  - **验收**：编译通过，manifest 中 `android:name=".DeskPetApp"` 生效

- [ ] **T00-05 创建 Compose 主题文件**
  - **描述**：创建 Theme.kt、Color.kt、Type.kt，使用 Material3 默认主题
  - **输入**：无
  - **输出**：`ui/theme/Theme.kt`、`Color.kt`、`Type.kt`
  - **验收**：编译通过
