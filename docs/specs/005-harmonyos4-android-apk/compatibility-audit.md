# Android 29–34 行为变化与 targetSdk 34 独立兼容性迁移审计

## 1. 迁移目标与范围 (FR-531, T556)
本审计针对 HejiNotes Android APK (`com.jambus.heji`) 将 `targetSdk` 从 28 迁移至 34 进行全项行为分析与前置准备。当前工程实际 `targetSdk` 仍保持为 28，确保在提升至 HarmonyOS 4（基于 AOSP 12）以及后续 Android 14+ 环境过程中，分阶段稳妥推进 Vault 存储契约、后台同步与媒体捕获稳定性。

---

## 2. 核心系统行为变化与适配状态

| 平台版本 | 关键变更项 | 影响范围 | HejiNotes 当前适配状态与策略 |
| :--- | :--- | :--- | :--- |
| **Android 29 (API 29)** | 分区存储 (Scoped Storage) | 文件访问 | **兼容（架构天然契合）**。HejiNotes 从设计之初即完全依赖 SAF (`DocumentFile` 树形授权 URI) 操作外部 Vault，绝不使用直接 Linux 路径读写公共外部存储。当前 targetSdk 28 下行为一致。 |
| **Android 29 (API 29)** | 全屏手势排除限制 | 边缘手势交互 | **初步优化（待交互验证）**。`PhotoEditorView` 在 API 29+ 采用按需计算并限制排除范围在 200dp 系统预算内，已彻底移出 `onDraw` 并实现拖动刷新与矩形去重，待系统侧滑手势真机验证。 |
| **Android 30 (API 30)** | 软件包可见性 (`<queries>`) | 外部 Intent 唤起 | **初步适配（待高版本验证）**。相机拍照使用标准 `MediaStore.ACTION_IMAGE_CAPTURE`，由系统捕获活动处理；Google 登录使用 Play Services Auth SDK。 |
| **Android 31 (API 31)** | Intent PendingIntent 必须显式声明可变性 (`FLAG_IMMUTABLE`) | 通知与后台服务 | **代码已就绪**。`BackgroundSyncService` 内部构建的 PendingIntent 已显式包含 `PendingIntent.FLAG_IMMUTABLE`，消除 targetSdk 31+ 崩溃隐患。 |
| **Android 33 (API 33)** | 通知运行时权限 (`POST_NOTIFICATIONS`) | 同步状态通知 | **清单已声明（待动态权限适配）**。清单已加入 `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`。当提升至 `targetSdk 33+` 时，设置页后台同步首次启动前增加运行时权限申请检查。 |
| **Android 34 (API 34)** | 前台服务类型强约束 (`foregroundServiceType`) | 后台同步服务 | **清单已声明（待调度回归）**。清单已声明 `android.permission.FOREGROUND_SERVICE_DATA_SYNC`，并且 `BackgroundSyncService` 声明 `android:foregroundServiceType="dataSync"`。 |
| **Android 34 (API 34)** | 广播接收器导出安全标志 | 动态广播 | **无风险**。当前代码未注册全局未指定导出的动态 BroadcastReceiver。 |

---

## 3. 工具链与构建兼容性审计

- **compileSdk**: 当前已设置为 `34`。
- **Android Gradle Plugin (AGP)**: 当前为 `7.3.0`。
  - AGP 7.3.0 原生测试覆盖至 compileSdk 33，在 `compileSdk = 34` 时可通过 `gradle.properties` 的 `android.suppressUnsupportedCompileSdk=34` 消除警告。
- **Java / Kotlin Target**:
  - 当前保持 `JavaVersion.VERSION_1_8` / JVM target `1.8`，完全兼容当前 DevEco-Studio JBR 17 与 Android 运行时，无字节码不兼容风险。

---

## 4. 迁移验证结论与后续步骤

1. 当前工程 `targetSdk` 维持为 28，相关权限（`FOREGROUND_SERVICE_DATA_SYNC` 与 `POST_NOTIFICATIONS`）和代码（`FLAG_IMMUTABLE`）属于前置就绪。
2. 清单权限、PendingIntent 不可变标志和前台服务类型已就绪；实际提升至 `targetSdk 33+` 时的通知动态授权申请和高版本后台执行限制待在切换版本时结合真机回归落实，在此之前维持 targetSdk 28 前置准备状态。
3. 按照 T556 规范，后续分阶段正式切换 `targetSdk 34` 时，需结合 Mate 60 真机回归确认 HarmonyOS 4 兼容层的通知拦截与后台唤醒表现。
