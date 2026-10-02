# Quickstart: Android 客户端实现加固

## 前置条件

- HarmonyOS 4 Mate 60 真机，已安装调试版 APK。
- 一个测试 Vault，包含下列样例笔记：
  - `Fixtures/rich-blocks.md`：围栏代码块、缩进代码块、表格、有序列表、两级嵌套列表、引用块、水平线、任务列表、YAML front matter 各至少一处。
  - `Fixtures/links.md`：至少一条相对链接 `[图](../assets/x/1-c.jpg)` 与一条站外链接。
  - 一个含 200 篇以上 `.md` 的目录。

## 构建与测试

```bash
cd android
./gradlew :app:testDebugUnitTest   # Markdown 编解码单元测试
./gradlew :app:assembleDebug       # 调试 APK
```

## 验证路径

### V1 内容保真（FR-801、FR-802）

1. 记录 `Fixtures/rich-blocks.md` 的原始内容。
2. 在 禾记（Heji Notes） 中打开该笔记，仅修改第一段文字，等待状态变为「已保存」。
3. 用外部工具比对文件：除被编辑段落外，其余行的文本与缩进必须逐字节一致。

### V2 未编辑不写入（FR-804）

1. 打开 `Fixtures/rich-blocks.md`，不做任何输入，直接返回文件列表。
2. 文件的修改时间与内容均不得变化。

### V3 自动保存生效（FR-805）

1. 打开今日笔记，输入一行文字后停止操作。
2. 状态应在约 0.6 秒后由「未保存」变为「已保存」。
3. 强制结束应用并重新打开该笔记，输入内容仍在。

### V4 链接保真（FR-803）

1. 打开 `Fixtures/links.md`，编辑正文中任意一段文字并保存。
2. 相对链接目标必须仍为 `../assets/x/1-c.jpg`，不得被改写为绝对 URL。

### V5 大目录（FR-809、FR-810、FR-811）

1. 进入含 200 篇以上笔记的目录。
2. 不得出现无响应弹窗；列表滚动应保持可用。

### V6 编辑器资源（FR-812）

1. 连续进出编辑器 20 次。
2. 通过 `adb shell dumpsys meminfo com.jambus.heji` 确认 `Views` 与进程内存不持续单调增长。

### V7 拍照与恢复（FR-806、FR-813、FR-814）

1. 在非每日笔记目录下的笔记中拍照并插入，确认图片显示且相对链接有效。
2. 在写入过程中强制结束应用，重新启动后进入同一目录：不得残留 `.tmp`、`.txn` 或 `.bak` 文件，笔记名恢复正常。

### V8 状态恢复（FR-808）

1. 在某个子目录中打开一篇笔记，旋转屏幕或触发进程重建。
2. 返回后仍处于该笔记的编辑界面；退出后回到原子目录并保留滚动位置。

### V9 字体缩放（FR-817）

1. 将系统字体调至最大。
2. 编辑器正文与列表文字随之放大，且不出现截断或重叠。

### V10 已有笔记读取失败（FR-819、FR-820）

1. 打开一篇已有笔记前撤销 Vault 授权，或使用测试文档提供方令该文件读取失败。
2. 页面不得显示默认标题正文或“已保存”，编辑和保存入口不可用。
3. 页面应说明原文件未修改，并可执行“重试”“重新选择 Vault”和“返回文件库”。

### V11 旋转与上下文恢复（FR-808、FR-822、FR-823）

1. 在子目录滚动列表后旋转，确认目录与滚动位置保持。
2. 打开笔记输入尚未自动保存的文字后旋转，确认仍处于同一笔记且文字和状态可恢复。
3. 拍照后调整模式与四个角点再旋转，确认仍处理同一张照片且插入点保持。
4. 在以上各阶段触发 Activity 重建；若系统无法恢复照片，必须解释并清理临时文件。

### V12 并发保存状态（FR-805、FR-807、FR-809、FR-821、FR-822）

1. 使用可延迟写入的测试文档提供方，在第一次保存未完成时继续输入第二段内容。
2. 第一次写入完成后界面仍须显示“未保存”或“正在保存”，不得提前显示“已保存”。
3. 只有包含第二段内容的最新修订落盘后才显示“已保存”，期间输入和滚动保持可响应。
4. 模拟最新修订写入失败，确认内容保留并可重试；返回时可选择继续编辑、重试或明确放弃。

### V13 同步状态重新进入（FR-824、FR-825、FR-826）

1. 启动包含多个文件的同步，在出现进度后返回设置并旋转屏幕。
2. 设置页仍显示当前阶段和数量，点击后可返回同一任务的进度，且没有重复同步。
3. 分别验证完成、明确取消、冲突和失败；每种结果均可从设置页重新进入查看。
4. 关闭并重建 Activity 后，最近结果仍保留到下一次同步替换或用户明确清除。

### V14 双向同步与基线漂移（FR-829）

1. 对 Google Drive / OneDrive 各使用独立测试 Vault：云端新增 `new.md`、本地缺失，
   同步后确认同路径内容完整落盘，再同步为未变化。
2. 成功同步 `delete.md` 后仅在本地删除，云端不改；下次同步确认云端确切项目进入回收站。
3. 成功同步 `updated.md` 后删除本地副本并更新云端正文；下次同步必须下载新正文，不回收云端。
4. 同步期间分别修改云端与本地内容（包含同长度正文），在最终基线校验发现漂移时显示未完成，
   上次成功时间/基线不前移；重试分别下载/上传修改，不能一直显示未变化。
5. 用旧版生成的测试基线验证首次内容校验；旧基线描述不同内容时不得据此回收云端，
   两边均存在且方向不明确时保留冲突副本。无元数据 MD5 的 OneDrive 也执行相同规则。
6. 下载校验中断网或取消，确认不提交基线、不确认移动历史、不误报完成。

### V15 同步路径、下载确认与错误追溯（FR-830、FR-831、FR-832）

1. 对两家各建立专用测试目录：同一父目录两个同名文件夹、同名文件/文件夹；同步必须
   在上传、下载或回收前拒绝，提示路径歧义，目录和所有内容不合并、不改名。
2. 用故障 Provider 模拟暂存损坏、rename 改名、提交后返回空或抛异常，确认不报成功，
   原始副本和可能已提交内容保留；启动恢复不能误删 `.markbook-sync-*.pending/.previous`。
3. 下载期间在外部移动、重命名或替换目标父目录，确认原文件/备份不被删除，最终提交失败。
4. 同步期间保存同一路径本地笔记，确认内容先验检查/提交锁保护编辑；网络传输不占用编辑锁。
5. 先运行 Google，再运行 OneDrive 的失败/取消/成功；各自详情仍保留各自结果。切换 Vault
   不串结果，旋转、返回前台和刷新保持所选 Provider。跨 Vault 活动任务显示等待提示。
6. 分别制造授权、权限、网络、限流、远端变化、本地校验和基线错误，检查中英文原因
   明确且不存在账号、访问令牌、远端 URL 或原始异常。旧记录不能重新解析敏感异常为路径。

### V16 云端协议与拒绝/迁移边界（FR-833–FR-837）

1. 在测试传输层分别返回 null、空、弱、通配和非法 ETag；Google 覆盖/回收必须在 HTTP
   修改请求和上传输入读取前拒绝，并显示前置条件不可用，不清理原内容。
2. 注入正常 EOF 但摘要不符的下载，以及无元数据摘要时两次独立读取不一致；原本地文件
   与备份不改动，旧基线不提交。正确内容传输期间改变源修订/目录或取消，也必须拒绝提交。
3. 在初始扫描后移动、改名或替换远端目录；上传/冲突复制不得使用旧目录缓存。新建目录
   若返回错误身份、类型、改名或产生歧义，不上传文件，结果保持未完成。
4. A Vault 同步期间切到 B 启动：先明确拒绝；服务端 race 拒绝也须恢复 UI，A 继续执行，
   其最新任务、租约与取消状态不得被 B 的拒绝替换。
5. 使用含原始 URI、旧错误消息的真实 JSON 偏好模拟记录；读取后检查存储已脱敏，
   重复读取不重复哈希、不重复写入；迁移与进度并发不丢失活动任务，Provider/Vault 仍隔离。
6. 真实 Google 账号验收需另外记录响应 ETag 和条件更新/回收行为；模拟 HTTP/摘要测试
   不能证明 Google 实际支持。父目录最后一次检查后的跨网络变化仍可能发生，最终比较
   必须拒绝成功；不声称整个远端目录跨请求原子。

## 记录

每次真机验收按 [`../005-harmonyos4-android-apk/quickstart.md`](../005-harmonyos4-android-apk/quickstart.md)
记录设备、系统、APK 版本、步骤结果和例外；未通过项须注明用户影响与后续计划。
本次 D16 自动化/构建门槛见 [同步基线验收记录](sync-baseline-acceptance.md)。

## GitHub #2 / FR-838 · 设置同步分组验收（0.6.4 / 16）

- 自动化（2026-10-02）：在 `android/` 使用已有 OpenJDK 17 和 Android SDK 执行
  `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon` 成功（14s；36 tasks，11 executed）。
  45 suites / 360 tests，0 failures/errors/skips；本次重新执行单测并编译/打包 UI 改动。
  首次构建发现新分组中 Context 接收者类型错误，已修正并执行上述完整验证。
  DevEco JBR 本机不可用，使用已有 OpenJDK 17；保留原有 AGP/SDK 和 deprecated API 警告。
  fresh APK：`android/app/build/outputs/apk/debug/app-debug.apk`，7,194,684 bytes；SHA-256
  `7e064e5c08e01f1c9a69f074336fccd0b1fc938f5da2795690a5d01d59659e70`。未安装/发布。
- 静态检查：设置仅构建两个 Provider 分组；各分组独立查询当前 Vault 的记录并固定详情目标；
  配置入口和持续状态沿用既有实现。中英文资源一致；行触控高度沿用 68dp；未改引擎或存储。
- Mate 真机：未执行。需分别检查无记录、仅单服务记录、双服务记录、运行中、失败/取消、
  未连接/重登录的展示和详情返回；中文/English、深浅色、最大字体与横竖屏均需人工检查。
- 按 `review_checklist.md`：范围/设计追溯、Provider 归属及配置导航静态通过；Vault/保存/相机
  行为不受本次改动影响；设备布局、辅助技术和实际点击仍待验证，不以构建替代。

## FR-839 · 裁剪边缘验收（0.6.5 / 17）

- 自动化（2026-10-02）：使用已有 OpenJDK 17（本机无 DevEco JBR）与 Android SDK，
  在 `android/` 执行 `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`，
  16s 成功，36 tasks / 17 executed；46 suites / 363 tests，0 failures/errors/skips。
  新增 3 项几何测试覆盖横竖屏、密度 1/2.75/3、普通与细长照片、完整源坐标往返、
  极小/零区域。fresh APK 为 `android/app/build/outputs/apk/debug/app-debug.apk`，
  7,195,584 bytes，SHA-256 `3d6b429da712e60a6b2ee67c63d2104f0b6b89777e39b01f3bd67a4c02f8bfc1`。
  保留既有 AGP/SDK 与 deprecated API 警告；未安装、未发布。
- 原 reviewer 最终只读复查通过，无待修复发现。设计评审清单的范围追溯、完整原图、
  原始坐标与默认选区、单一绘制/触摸矩形和密度热区静态通过；设备交互项待验收。
- 设备验收未执行：Mate 60 拍摄横向、纵向、长宽照片，确认默认整图且四角远离
  手机边缘；四角拖至原图边界与向内裁剪、切换模式、旋转后仍可准确操作。检查
  深浅色与最大字体。插入后重启并确认图片与相对 Markdown 链接有效；HAP 同样
  单独执行拍照/重启/链接检查，不将 Android 证据当作 HAP 验收。


## 2026-10-02 · 生命周期开发修订

最终修订自动化通过，设备证据待执行。初次 369 项及中间 APK 已被下述修复后结果替代。

- 独立 tester 在 `android/` 使用已有 JDK 17（DevEco JBR 不可用）和 Android SDK 执行：
  `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --rerun-tasks`。
  Gradle 缓存锁在沙箱内不可写，授权缓存写入后 22s 构建成功，36 tasks 全部执行。
- 最终 XML：48 suites / 374 tests，0 failures/errors/skips。包含生命周期 3 项、回滚策略 4 项、
  保存协调时序 23 项、修订协调器 9 项；覆盖旧监听交接/显式消费、重复和过期操作、缺缓存终态、
  取消后清理门禁、Provider false/异常/读取失败/替换/marker 残留、旧失败快照及跨笔记真实重试、
  阻塞写入期间的原子修订推进。模拟端口不是实际 SAF。
- `./scripts/verify-vault-contract.sh`、中英文新消息检查和 `git diff --check` 通过。
  原 reviewer 修复后最终只读复查通过，无本轮范围内待修复发现；主协调者另汇总 XML 并核对包/哈希。
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`，`com.jambus.heji`，0.6.6（18），
  7,206,512 bytes；生成时间 2026-10-02 19:53:27 +0800。
  SHA-256：`ced4af141980f561279a2e9526538418474acd9f08998a450d29aa87c259c06e`。
  `apksigner verify --print-certs` 通过，公共证书 SHA-256 与原版本一致：
  `b3be289894592585b98c0ed9076f98612e7cddb7c7574c6343ce0b400d018e20`。
- 本轮没有安装、操作个人 Vault/云盘或运行设备验收。Activity 旋转/重建、真正缺缓存 UI、
  位图资源、实际 SAF 回滚、Mate APK 与 HAP 拍照→重启→图片/相对链接检查仍待执行。
- 安全限制：不能确认写入或清理时禁止普通重试，提示重启应用后重新打开 Vault 核查。
  `recoverVault()` 返回成功不被当作所有照片 marker 均已解决的证明；持久化 marker 格式未变化。

## 0.6.7（19）· T316 / D25–D27 验证

开发者受影响 7 suites / 69 tests、独立 0.6.7 完整 49 suites / 389 tests 与原 reviewer 复查通过。
0.6.7 输出随后被并行 UI 构建覆盖；当前集成包证据见下节。
新增测试针对两个 adapter 的读取尝试/等待预算、401/403、连接释放、取消、无写入/流重放，
以及引擎认证终止和取消不推进旧基线；这些仍是 JVM/模拟 HTTP 证据。
真实账号、SAF Provider、弱网、Mate UI 和设备路径由 T317/T329/T330/T848 继续验收。

## FR-843 · 按钮与刷新反馈（0.6.8 / 20）

- 自动化（2026-10-02）：本机无 DevEco JBR，使用已有 OpenJDK 17 和 Android SDK；在 `android/`
  执行 `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`，最终修订 13s 成功，
  36 tasks / 8 executed；49 suites / 389 tests，0 failures/errors/skips。保留已有 AGP/SDK 与 deprecated API 警告。
- fresh APK：`android/app/build/outputs/apk/debug/app-debug.apk`，7,214,292 bytes；SHA-256
  `5c0f3816654b38c7567fef8418d4e889b20a052c6c9efbff406c962c3582f061`。本包包含已有同步工作树修订，未安装/未发布。
- 原 reviewer 在颜色修正后最终复查通过，无待修复发现。`git diff --check` 通过。
  按设计清单静态确认本地化、Provider/滚动保持、后台更新静默、禁用态与无新增延迟页面重建；
  主/次按钮日夜文字对比按 normal/pressed/波纹叠加值核算，最低 5.06:1。
- Mate 未执行：按 T859 验证深浅色按压/焦点、禁用状态、中文/English、TalkBack、最大字体、
  重复刷新与滚动保持；裁剪模式仅按钮背景呈现改变，实际拍照插入/重启/相对链接仍待设备证据。
  不以单测或构建代替设备证据；本次不修改 HAP，不声称两端真机通过。

## 0.6.8（20）· 本轮最终集成验证

- 同步 T316 / D25–D27 已通过原 reviewer 复查；并行 FR-843 UI 改动的集成只读评审也通过。
  新 UI 的点击/刷新保持原同步与取消处理路径，渲染/辅助技术及 Mate 行为仍待实测。
- 独立 tester 使用已有 JDK 17（DevEco JBR 不可用）及 Android SDK，在 android/ 执行
  `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --rerun-tasks`，
  24s 成功，36 tasks 全部执行。49 suites / 389 tests，0 failures/errors/skips。
  `verify-vault-contract.sh`、`git diff --check` 与公共证书签名校验通过。
- 构建前后 129 个 Android 源码/测试/资源/构建文件指纹完全一致，聚合 SHA-256：
  `570468d5b2d7ecea0aa17b8ebf7f81c5e20c5d79d572ef652af27321ac6f4ff7`。
- 最终包为 com.jambus.heji、0.6.8（20）、7,214,292 bytes；生成于 2026-10-02 21:01:43 +0800。
  SHA-256：`5c0f3816654b38c7567fef8418d4e889b20a052c6c9efbff406c962c3582f061`。
  已保存独立交付副本 `/private/tmp/heji-sync-retry-delivery-20261002/HejiNotes-current-debug.apk`，
  副本哈希与 fresh APK 一致；主协调者另核对副本与 XML。
- 公共证书 SHA-256 与原版本一致：
  `b3be289894592585b98c0ed9076f98612e7cddb7c7574c6343ce0b400d018e20`；未读取签名密钥。
- 读取预算按端点建立阶段计；OneDrive Graph 与显式重定向下载各最多三次/30 秒等待，
  整次下载最多六次请求/60 秒重试等待。消费者读取及原有上传会话进度核验不纳入新重试机制。
- 自动化限制：累计多次 Retry-After 的精确边界、服务偏好联动、初始 token/刷新前取消、
  带既有错误和完成搬运的终止组合、每种写入失败组合，部分仅有源码复核。
  没有把这些静态结论写作额外测试通过；本轮没有真实云盘、SAF 或 Mate 覆盖。
  T317/T329/T330/T848 保持开放，当前包未安装或发布，仍为非候选开发版。

## D28 / FR-844 · 系统 VPN 排查（0.6.9 / 21）

源码检查：GoogleDriveApi 与 OneDriveApi 默认 `URL.openConnection()`，未发现物理网络绑定、
显式直连代理、自定义 DNS 或 TLS 放宽。Android 默认网络及 VPN 应用名单的行为依据
[默认网络说明](https://developer.android.com/develop/connectivity/network-ops/reading-network-state) 和
[分应用 VPN 说明](https://developer.android.com/develop/connectivity/vpn)。
GoogleAuthUtil 获取令牌依赖 Google Play 服务自身联网，位于 Drive adapter 构造之前。

2026-10-02 本轮只读设备检查：Mate 60 当前安装 0.6.8（20）；网络服务记录包含 VPN 传输类型，
但这些记录不能证明禾记流量进入隧道或命中代理规则。仅过滤安全字段读取 Google 任务记录，
得到中断/运行状态、零处理计数及 UNKNOWN；没有可用的后台服务具体异常类日志。
因此本次不能确认用户“网络异常”的实际 DNS/连接/授权根因；未安装或启动同步，未输出
Vault/账号/远端目标，未读取 VPN 订阅、令牌或私钥。

规则模式真机步骤（T861，待授权/执行）：

1. 确认 Fclash 的 VPN 隧道已启用；若使用允许名单，加入禾记 `com.jambus.heji`，若使用
   排除名单，确保禾记不在其中。Google 登录失败时同时检查 Google Play 服务联网范围。
2. 保持规则模式，在连接/请求面板检查同步 API `www.googleapis.com` 的实际规则与出口，
   不能只因应用进入隧道就认为请求不再 DIRECT。登录请求按实际观测域名检查，不猜测固定名单。
3. 保存应用名单后按客户端提示重连 VPN。用专用测试 Vault 触发授权与同步，分别记录阶段、
   安全原因、完成计数、取消与网络切换；不要求切全局模式或关闭 TLS 验证。
4. JVM HTTP 测试不能代替以上实测。

自动化与包证据（2026-10-02）：

- 开发者受影响测试 38 项通过；独立 tester 使用已有 JDK 17（DevEco JBR 不可用）及 Android SDK，
  在 android/ 执行 `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --rerun-tasks`。
  24s 成功，36 项任务全部执行；49 suites / 397 tests，0 failures/errors/skips。
- 构建前后 131 个 Android 源码/测试/资源/构建文件逐项 SHA-256 相同；`git diff --check`、
  Vault 契约验证及中英文 strings XML 校验通过，原 reviewer 最终复核无阻塞问题。
- fresh APK：`android/app/build/outputs/apk/debug/app-debug.apk`，com.jambus.heji，0.6.9（21），
  7,217,844 bytes，生成于 2026-10-02 21:20:05 +0800。SHA-256：
  `38a0f64ff060ed59a5d55aafbcb5109780c111a19381a8a06d702f60faca63c7`。
  公共签名校验通过，证书 SHA-256 与原版本一致：
  `b3be289894592585b98c0ed9076f98612e7cddb7c7574c6343ce0b400d018e20`；主协调者另核对 XML 与 APK 哈希。
- 自动化覆盖两个 Provider 的 DNS/连接恢复、三次上限、取消、嵌套超时的 TLS 不重试、
  下载流与全部 gateway 修改操作不重放；直接类型诊断/语义优先/脱敏，以及下载故障保留
  原内容、子类型和取消。脚本化 HTTP 不等于真实节点、DNS、Play 服务授权或 SAF 行为。
- 本轮未安装或启动真实同步、未修改 VPN 设置；规则模式端点命中及授权阶段根因仍未知，
  T861、T317/T329/T330/T848 继续开放。仅工作树交付，无本轮 Git 暂存/提交/推送。
