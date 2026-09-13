# Tasks: HarmonyOS 5/6 原生 HAP

- [x] T601 使用 DevEco SDK 根目录构建 debug HAP。产物为
  `entry/build/default/outputs/default/entry-default-unsigned.hap`；使用
  `.../sdk/default` 会触发 `00303168`，不得使用该路径。签名流程见
  `signing.md`。
- [x] T602 审计现有 ArkTS 实现与 APK P1 功能基线差异。
- [x] T603 对齐 Vault 选择、每日笔记、编辑和保存行为。
- [ ] T604 对齐拍照、裁剪、四点透视校正和附件恢复，并采用
  `assets/<笔记文件名>/` 的短文件名契约；待 Android 真机核心闭环验收后执行。
- [ ] T605 运行 Hypium 和共享格式样例。
- [ ] T606 使用 APK 生成的 Vault 验证无需转换即可继续编辑。
- [ ] T607 在 HarmonyOS 5 Mate 手机上执行核心回归。
- [ ] T608 在 HarmonyOS 6 Mate 手机上执行核心回归。
- [ ] T609 记录平台降级差异、安装包版本和验收结果。
- [ ] T610 对齐 Android Markdown 工具栏的撤销、重做、标题、加粗、斜体、标签、链接、
  表格输出、选区、取消和无障碍语义。
- [ ] T611 对齐回收站项目统计、不可撤销确认、附件保留、后台清空和部分失败重试语义。
- [ ] T612 对齐普通文件库的新建、命名校验、重命名、Provider 原子目录回收站、深层附件
  相对路径和每日目录配置恢复语义。
- [ ] T613 对齐 APK 的系统相机短视频确认、`-v.{mp4|3gp}` 相对链接、180 秒/200 MiB 限制、待处理会话与保守恢复语义。
- [ ] T614 对齐 Android 的笔记与独占 assets bundle 移动、链接重写、marker 恢复和移动后继续捕获；完成前保持跨端验收未通过。
- [x] T615 HAP：将 bundle 标识改为 `com.jambus.heji`，提供“禾记”与 “Heji Notes”本地化应用名，并记录新安装后重新选择 Vault 的验收要求（FR-608）。
- [x] T616 HAP：将应用图标替换为深林绿底、暖白色打开笔记本及从中缝生长的三叶树苗（FR-609）。
- [ ] T617 HAP：将当前文件库、编辑器、拍照和恢复文案迁移至中文与 English 资源；SDK 10
  基线跟随系统语言，应用内覆盖留待兼容实现与真机验证（FR-610）。
- [ ] T618 HAP：将 `NotebookRepository.list()` 拆为异步的笔记元数据/有界标题摘要加载；
  `aboutToAppear` 不得同步 `readTextSync()` 遍历全文，打开编辑器时才读取完整 Markdown
  （FR-611）。
- [ ] T619 HAP：将文件库 `ForEach` 改为 `LazyForEach + IDataSource`，以 `NoteSummary` 而非
  常驻全文驱动列表；搜索索引更新可取消且不阻塞首屏（FR-611）。
- [ ] T620 HAP：补充大 Vault 冷启动、异步摘要失败、搜索取消和长列表节点复用的自动化/真机
  证据；分别在 HarmonyOS 5 与 6 Mate 上记录启动、滚动和内存表现（FR-611）。
