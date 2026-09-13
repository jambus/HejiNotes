# Feature Specification: HarmonyOS 5/6 原生 HAP

**Status**: In Progress
**Priority**: P2
**Depends on**: 001-local-markdown-notebook, 002-camera-attachments,
004-harmony-4-5-compatibility, 005-harmonyos4-android-apk

## Goal

在 HarmonyOS 4 APK 核心闭环稳定后，为 HarmonyOS 5/6 提供原生 Stage HAP，
并保证原 Vault 可以继续使用。

## Functional Requirements

- **FR-601** HAP 必须使用 HarmonyOS 原生 Stage 模型和平台 API。
- **FR-602** 必须实现与首个 APK 相同的本地笔记、拍照和系统相机短视频 P1 能力。
- **FR-603** 必须通过规格 `004` 的共享 Markdown、附件和图片样例。
- **FR-604** 用户必须可以重新选择 APK 使用过的 Vault，无需数据格式转换。
- **FR-605** 平台权限或能力不同导致无法完全一致时，必须提供可见降级并记录差异。
- **FR-606** 必须分别在 HarmonyOS 5 与 HarmonyOS 6 Mate 手机上完成真机验收。
- **FR-607** HAP 必须在文件库能力完成后对齐 Android 的笔记与独占附件 bundle 移动、嵌套 assets 布局、链接重写及 marker 恢复；在此之前不得声称跨端对齐。
- **FR-608** HAP 的 `bundleName` 必须为 `com.jambus.heji`，并按系统语言显示“禾记”（中文）或 “Heji Notes”（其他语言）。该标识变化视为新安装，首次运行需重新选择既有 Vault；Vault 内 `.markbook` 元数据、Markdown 与附件路径保持不变。
- **FR-609** HAP 应用图标必须与 Android 采用相同的“打开笔记本中长出三叶树苗”品牌构图、深林绿/暖白/嫩绿颜色语义，且不包含文字或字母。
- **FR-610** HAP 的文件库、编辑器、拍照与恢复文案必须提供中文与 English，并在 SDK 10 基线
  跟随系统语言；应用内语言覆盖在兼容实现前不提供。语言呈现不得修改 Vault、Markdown、相对
  附件路径或保存语义。
- **FR-611** HAP 首屏不得在 UI 生命周期回调中同步读取所有 Markdown 全文。列表先显示可用
  文件元数据和异步标题/摘要，全文仅在打开编辑器或可取消索引更新时读取；长列表必须复用节点。

## Out of Scope

- 复用 Android UI 或将 APK 包装为 HAP。
- 在 APK 核心闭环完成前抢先扩展 HAP 独有功能。
