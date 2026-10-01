# Heji Notes 对话交接记录

整理日期：2026-10-02（Asia/Shanghai）
仓库：`/Users/jambus/workstation/git-repro/HejiNotes`

本文是本次对话的决策、实现、问题与环境配置摘要，供归档后继续工作。最新行为以源码、
`docs/contracts/vault-contract.md`、平台规格和 `docs/RELEASE_NOTES.md` 为准。

## 1. 用户需求与已完成行为

### 附件浏览与视频正文展示

- 文件库最初只展示 Markdown 并隐藏 `assets`。现在可逐层进入 `assets` / `attachments`，
  展示图片、视频及其他文件的名称、类型和可用大小，点击文件调用系统查看器。
- 本地 MP4/3GP Markdown 链接在笔记正文渲染为 16:9 视频卡片，可在正文直接播放。
  持久化内容仍是普通 Markdown 相对链接，不写入播放器 HTML 或设备绝对路径。
- 修复双播放按钮：初始仅显示自定义中央播放按钮；首次点击后启用原生控制栏，自定义按钮
  随后隐藏，暂停时不再叠加。
- 正文末尾为图片/视频时提供可编辑尾行，便于继续书写；媒体后的光标行为已接入。

### 正文删除媒体与 assets 单文件删除

- 正文长按媒体提供两个选项：
  1. **仅从正文删除（保留附件）**：普通正文编辑，只移除所选引用，不创建附件删除标记。
  2. **删除正文引用并永久删除附件**：持久标记先于 DOM 修改，条件保存正文后检查引用，
     确认安全再删除该引用指向的确切文件。
- 删除节点绑定稳定媒体令牌与原路径，防止异步准备期间删错节点；事务期间锁定编辑。
- 组合删除会扫描整个 Vault 的 Markdown（含 `.trash`、`assets`、`attachments`），共享引用、
  不可读内容、外部替换、同步占用和无法确认结果时保留附件并显示清理状态。
- 图片/视频单文件可在附件浏览器直接永久删除，使用独立 `DIRECT_CONFIRMED` 事务，
  不伪造笔记提交阶段。损坏/不可读持久标记不能授权删除。
- 不按拍摄 ID 推测删除原图、校正图或其他兄弟文件；只操作所选文件。
- 曾出现“正文发生外部变化或无法安全保存”的误报：`PREPARED` 标记的保存后哈希为空，
  解析器过滤空行导致字段丢失。已改为保留位置空字段，并补真实 `PREPARED` 往返测试。

### assets 文件夹删除及最终交互

- 允许删除精确小写 `assets` / `attachments` 根目录及其下附件文件夹。
- 用户最终要求：**左滑露出单个红色“删除”文字按钮**，不常驻垃圾桶图标。
  文件夹主体用于进入目录；长按和 TalkBack 提供等价操作。
- 确认框继续明确说明永久删除、不可撤销，并显示冻结清单的文件数与子目录数。
- 确认前严格递归读取目录快照，包含路径、Provider 身份、大小（不可用时为 `-1`）及文件
  SHA-256。确认后重新核对 Vault、清单、活动保存/媒体事务以及全 Vault 引用。
- 操作运行于正文保存串行队列，受 `VaultSaveLock` 和结构租约保护；删除前重复清单/引用
  检查。只调用一次 Provider 整目录删除，禁止逐项递归删除。
- Provider 返回 false 或抛错后仍严格复核目录是否存在；仅确认不存在才报告成功。
- 文件夹删除不持久化自动续删任务；中断或失败后的重试须重新检查并确认。

## 2. 空 assets 目录误报修复

用户报告：目录已无照片/视频，删除仍显示“存在无法安全确认的 Markdown 引用”。

根因：`AssetFolderDeleteOperation.references()` 的残余文本启发式无论目录是否有文件，
都把任意笔记说明或代码里的 `assets/` 判为 `AMBIGUOUS`。此前测试还明确要求空目录代码
路径被拒绝，因此不是 Provider 删除失败。

修复边界：严格递归快照确认整棵树无文件时（允许空子目录），在显式目标/WikiLink 检查后，
跳过残余说明、代码和原始路径文字匹配。实际指向目录/其前缀的链接、已识别但目标路径不安全
的链接、不可读 Markdown 仍阻止删除。非空树继续保留模糊引用保护，零字节文件也属于非空。
确认后新增文件仍使清单失配，阻止旧确认执行。

主要文件：

- `android/app/src/main/java/com/jambus/heji/AssetFolderDelete.kt`
- `android/app/src/test/java/com/jambus/heji/AssetFolderDeleteTest.kt`
- 相关集成：`MainActivity.kt`、`VaultRepository.kt`、`AppNoteSaveCoordinator.kt`、
  `VaultBrowserPolicy.kt`、`SwipeActionRow.kt`、`UiText.kt`
- 工作记录：规格 `005` 的 FR-537、T569/T570，以及空目录修复 T571。

本对话完成源码与独立审查时，工具链尚缺失，未声称生成新 APK。2026-10-02 核对时，当前
HEAD 为 `64b558f Fix for assets folder delete issue`，已包含该修复；此提交由后续工作产生，
本对话未执行 Git 提交或推送。T571 仍显示未完成，后续可依据现有 fresh 验证补齐记录。

## 3. 版本与验证证据

- 早期改动按用户要求归入 Android `0.5.2 / versionCode 11`。
- 后续仓库演进为 `0.6.1 / versionCode 13`。继续工作时保留后续同步等修改，不回退到 0.5.2。
- 2026-09-30 附件目录实现与复审曾通过 300 项 Android 测试、APK 构建、Vault 样例检查和
  HAP 兼容构建。历史 APK 不作为本次空目录修复的产物。
- 2026-10-02 核对当前报告：43 suites / 340 tests；附件目录专项 23 tests，0 failures /
  errors / skips。`docs/RELEASE_NOTES.md` 记录独立测试者已用 JDK 17 + Android SDK 完成
  `:app:testDebugUnitTest :app:assembleDebug --no-daemon`；包含其他任务的同步加固改动。
- 当前 APK：`android/app/build/outputs/apk/debug/app-debug.apk`，7,183,932 bytes。
  SHA-256：`ffe0239698bf0fd10c80fe437c3c05ce820cda09440a07998bc775e87b807a8e`。
  整理时已实际核对文件大小、校验值和 XML 报告，但没有在本次文档整理中重新运行构建。
- Mate 60 实际 SAF、滑动/TalkBack、目录删除、权限撤销、外部变化、旋转、中断恢复及真实云盘
  验收仍待完成。版本为非候选、未发布开发基线；编译和 JVM 测试不是设备证据。

## 4. Java / Android SDK 环境交接

Android APK 可仅使用 JDK + Android SDK 命令行工具，Android Studio 不是必需。
HAP 仍需另外恢复 DevEco Node/Hvigor 和匹配 SDK；Android 环境不替代 HAP 工具链。

当前已核对：

- Homebrew：`/opt/homebrew`。
- OpenJDK 17：`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`。
- OpenJDK 21：`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`。
- jEnv 已注册 17 / 21；项目 `.java-version` 内容为 `17`，该文件尚未跟踪。
- SDK：`/Users/jambus/Library/Android/sdk`；已安装 `platforms/android-34`、
  `build-tools/30.0.3` 和 `platform-tools/adb`。
- Homebrew `android-commandlinetools` 提供 `sdkmanager`。
- `~/.zshrc` 已包含 jEnv 初始化、`ANDROID_HOME` 与 platform-tools PATH。

曾遇到并解决：

1. `jenv add` 失败：当时只有 Java 21，Java 17 尚未安装。
2. Homebrew JDK 下载在 `pkg-containers.githubusercontent.com` TLS 连接失败：提供清华
   bottles/API 镜像及 Temurin/Microsoft JDK 17 替代来源；之后已核对 openjdk@17 安装成功。
3. `java -version` 找不到运行时：当前终端没加载 jEnv；用户执行以下命令后确认正常：

   ```bash
   source ~/.zshrc
   jenv enable-plugin export
   jenv rehash
   command -v java
   java -version
   echo "$JAVA_HOME"
   ```

4. `adb: command not found`：adb 已安装，缺少 PATH；已核对配置文件现在有 SDK 配置，
   尚未收到用户明确反馈 adb 命令验证结果。

推荐 shell 配置（避免再次手动固定 JAVA_HOME，交给 jEnv）：

```bash
export PATH="$HOME/.jenv/bin:$PATH"
eval "$(jenv init -)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

查看与切换：

```bash
jenv versions
jenv shell 21
jenv shell --unset
sdkmanager --sdk_root="$ANDROID_HOME" --list_installed
adb version
```

当前项目构建命令（旧 DevEco JBR 路径不可用，用已安装的 Homebrew JDK）：

```bash
cd /Users/jambus/workstation/git-repro/HejiNotes/android
JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

## 5. 后续工作与注意事项

1. 先读当前 `AGENTS.md`，再检查 Git 状态。用户最新规则：L1 单代理；L3 审查 → 开发 →
   独立测试 → 原审查者复审，默认 Sol `gpt-6.1-sol / medium`，不切换已运行协调者。
2. 以当前源码与新报告为准补齐 T571 的验证状态及日期，检查旧环境阻塞记录是否需要明确标为历史。
3. 在 Mate 上验证空 assets（普通说明/代码路径）可删；显式目录链接仍拒绝；非空模糊引用、
   不可读、确认后新增内容仍拒绝；一次整体删除与取消行为符合 T570。
4. 核对新终端的 `java -version`、`echo "$JAVA_HOME"`、`adb version` 和 SDK 安装列表。
5. 恢复 HAP 工具链后执行 `./scripts/build-hap.sh`，保留单独的平台/设备证据。
6. 当前工作区还有其他任务的同步基线、下载提交、失败状态等未提交改动（涉及 `008`，例如
   `SyncFailure.kt`、`VerifiedSyncDownload.kt`）。不能把整个 dirty diff 视为本对话所有权，
   不覆盖、撤销或代为提交；`.automation/` 与 `.java-version` 等未跟踪内容也需保留。
7. 本次仅创建交接文件，不归档 Codex 任务、不变更 Git 状态、不安装到设备或发布 APK。
