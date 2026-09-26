# Tasks: Mate 双端适配

Android APK 实现由规格 `005` 跟踪，HarmonyOS 5/6 HAP 实现由规格 `006`
跟踪。本清单只负责两端共同契约和迁移验收。

- [x] T401 定义 Vault、附件、回收站、冲突副本和日期笔记路径规范，见
  `docs/contracts/vault-contract.md`。
- [x] T402 建立 `shared-testdata/`，覆盖中文、双向链接、标签和图片相对路径。
- [ ] T403 建立两端 Markdown 读写、图片命名和透视输出一致性测试。
- [ ] T404 在电脑 Obsidian 中打开两端生成的 Vault 并验证无需格式迁移。
- [ ] T405 验证 HarmonyOS 4 到 5/6 后重新授权原 Vault 并继续编辑。
- [ ] T406 验证跨端冲突保留、本地 `.trash/` 排除同步、远端可恢复删除、取消和离线恢复。
- [ ] T407 建立双安装包版本、签名、发布渠道和设备回归记录。
- [ ] T408 建立共享文件库管理 fixture，覆盖 Unicode/NFC/大小写名称冲突、`.md` 规范化、
  不支持的目录移动、深度附件路径和每日目录失效恢复。
- [ ] T409 建立视频链接（含空格）、`-v` 命名、MP4/3GP 元数据及不可读 Markdown 时保守恢复的共享 fixture。
- [ ] T410 建立嵌套 assets 和笔记 bundle 移动 fixture，并在 APK、HAP、Obsidian 中验证。
- [ ] T411 对齐 Android 任意 OneDrive 目录与 HAP app-folder 现状：两端分别记录授权范围、远端
  root、账号类型和 Provider 绑定，不跨 Provider 复用基线；共享 Vault 文件布局、相对链接、冲突与
  `.trash/` 排除语义保持一致。HAP 扩大权限或支持任意目录前须单独评审。
