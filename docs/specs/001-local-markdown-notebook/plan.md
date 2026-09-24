# Implementation Plan: 本地 Markdown 笔记本

## Technical Context

- HarmonyOS 4 使用 Kotlin Android APK，HarmonyOS 5/6 使用 ArkTS Stage HAP。
- 数据根目录由用户选择的 Obsidian Vault 授权目录决定；索引仍只用于性能。
- Markdown 文件是事实源；索引仅用于搜索和列表性能。

## Constitution Check

- [x] 使用普通 Markdown 文件与相对附件路径。
- [x] 仓储层独立于 UI 和同步 Provider。
- [ ] 删除流程具备回收站和恢复验证。
- [x] 已实现防抖自动保存、返回/切页刷新和失败状态。
- [ ] 自动保存与异常恢复通过真机测试。
- [x] 1,000 篇笔记搜索逻辑具备 500 ms 自动化指标门槛。
- [ ] 在目标真机确认搜索输入无明显卡顿。

## Design

两端各自实现 `NotebookRepository` 等价边界；Markdown 规则、相对路径和共享样例
保持一致。UI 通过状态层调用仓储，不直接访问文件。保存采用“临时文件 → flush
→ 原子替换”更新目标文件。搜索索引可重建，不参与导出。

## Phases

1. 修正现有仓储为原子保存并补齐异常恢复。
2. 增加自动保存、删除/回收站与恢复。
3. 增加 Markdown 预览和可重建全文索引。
4. 优先完成 HarmonyOS 4 APK 的单测、1,000 篇性能测试及 Mate 60 真机验收。
5. 在 HarmonyOS 5/6 HAP 中对齐行为并通过同一组数据样例。
6. 为 Android APK 实现可访问的底部 Markdown 工具栏；随后在 HAP 对齐相同的 Markdown
   结果、选区语义、取消行为和验收样例。
7. 在两端设置页提供回收站项目统计和显式永久清空；后台逐项删除，附件保留，部分失败
   可重试，并通过共享 Vault 样例验证删除范围。
8. 在两端提供普通文件库管理：严格校验并创建/重命名 Markdown 与文件夹，使用 Provider
   原子移动进入根 `.trash/`，并以共享样例验证 Unicode 名称、深层附件相对路径和每日目录
   配置失效后的恢复。
9. 在两端提供“移动笔记及附件”三对象事务；仅在确认附件 bundle 独占、全部 Markdown 可读和目标无冲突时移动，并用 marker 恢复中断状态。同步配置与网络不参与本地提交，同 Vault 正在运行的同步仅通过结构变更租约与移动互斥。
10. 将设备本地每日笔记目录和失效提示按稳定 Vault 身份分区；Android 将旧全局值只迁移到当前已保存 Vault，并以纯 JVM 用例验证双 Vault 隔离、幂等迁移及定向重命名/重置。
