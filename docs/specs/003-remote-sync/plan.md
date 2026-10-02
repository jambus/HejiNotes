# Implementation Plan: Google Drive、OneDrive 与 NAS 同步

## Architecture

`SyncEngine` 比较本地快照、远端变更和上次成功基线，生成操作计划；
`SyncProvider` 只负责协议 I/O；`SyncConflictResolver` 负责无协议依赖的冲突
决策。Android APK 与 HarmonyOS HAP 分别实现该边界；Google Drive 和 NAS
Provider 共用文件内容流、基线、断点与错误契约及测试样例。

## Constitution Check

- [x] Provider 与本地仓储边界已定义。
- [x] 冲突默认保留两份。
- [x] 登录状态优先使用系统安全存储，能力不足时仅驻留内存。
- [x] 增量状态只在整轮成功且两端摘要一致后提交。
- [ ] 当前 Android Google Drive/OneDrive 的真实环境故障注入通过（T317/T329/T330）。
- [ ] 后续两台目标 NAS 的协议兼容与故障注入通过（T311–T314），不混作 Android 当前云盘验收。

## 当前实现与验收边界（2026-10-02）

Drive 条件写入/回收、双 Provider 三方删除与 MoveBundle 已有实现及自动化覆盖；
剩余任务分别由 T317/T329/T330/T321 管理实云盘/设备证据。退避策略保留 T316，
Drive changes/可恢复上传/范围下载从组合条目拆到 T310b。NAS 后续协议工作独立管理。
不因已有模拟测试取消真实服务的 ETag、账号授权、弱网、取消和中断验收。
以下交付阶段保留为历史/长期顺序，当前稳定性收口以 `008/tasks.md` 为执行入口。

## Delivery Order

1. 定义文件快照、同步基线、操作计划和 Provider 契约。
2. 实现本地模拟 Provider，完成冲突和中断测试。
3. 实现 Google Drive 官方授权、增量变更和可恢复上传。
4. 调研并实现同时适配威联通 TS-251D、极空间的通用 NAS Provider，验证局域网
   与外网访问、证书和权限行为。
5. 增加配置、提供方无关的后台任务状态、进度、取消、通知、详情、错误摘要和手动同步 UI。
6. 完成多设备、弱网、令牌失效和 NAS 兼容性验证。
7. Android 将 Google Drive 传输逐步收敛到既有 `SyncProvider`、`SyncPlanner`、`SyncEngine`
   契约；早期“不传播普通删除”阶段已由当前三方基线可恢复删除替代，保持 MoveBundle 与删除保护语义，不能以复制 HAP
   实现替代 Android 的并发与恢复验证。
8. Android 复用已验证的分层文件同步引擎接入 OneDrive：MSAL 只负责账号与短期令牌，
   Microsoft Graph adapter 负责目录/内容/ETag/上传会话/回收站 I/O；Provider 名称、目录类型、
   绑定和基线显式隔离，不复制冲突决策。

## Android OneDrive First Slice

OneDrive 与 Google Drive 使用相同的本地严格快照、冲突副本、MoveBundle、Vault 租约、后台
任务状态和成功基线提交规则。应用注册参数由构建环境提供；仓库不包含客户端密钥。MSAL 在
系统安全边界内缓存账号会话，后台服务只按绑定账号静默取得当前访问令牌。

Microsoft Graph adapter 对上传内容先落到可重建的应用缓存文件以获得精确长度：小文件直接
上传，大文件使用上传会话分块发送；成功、失败和取消都清理缓存。预授权下载 URL、令牌和传输
缓存不进入 Vault、普通偏好或日志。接入基于三方基线的双向可恢复删除传播，远端删除
移入本地 `.trash/`，本地删除移入 OneDrive 回收站，并发修改优先保留。已验证的 MoveBundle 旧路径可
进入 OneDrive 回收站。

## Android Google Drive First Slice

Android APK 交付可在 Mate 60 使用的手动双向同步闭环：Google Play 服务账号授权、
Drive Vault 文件夹选择与新建、首次范围确认、递归比较、上传、下载、保留冲突副本和可见结果。
实现直接使用 Drive REST API，不引入已废弃的 Drive Android API，也不把访问令牌写入
SharedPreferences、Vault 或日志。

基于三方基线提供双向可恢复删除传播：远端删除且本地未修改时移入本地 `.trash/`，本地删除且远端
未修改时移入 Provider 回收站，任一方有新修改安全保留不删。它是安全可用的电脑 Obsidian 互通基础，而非
依赖实时 webhook 的增量同步实现。后续阶段接入 Drive changes 游标、可恢复上传与范围下载；本地 `.trash/`
始终排除在同步范围外。

后台执行采用每个 Vault 唯一的前台后台任务。其状态、通知和详情页只依赖通用任务模型，
Google Drive 仅提供授权与传输实现。网络传输不占用编辑器；远端下载在最终提交前重新检查
同路径本地文件，发现并发本地改动时保留两份内容。

显式“移动笔记及附件”在本地提交后产生 provider-neutral 变化历史。同步以本地快照、远端
快照、上次成功基线和变化历史做三方规划，先上传/验证新路径，再将基线后未变化的旧路径移入
Drive 回收站。普通删除由三方基线可恢复删除传播独立处理；同一 Vault 的同步与结构移动使用同一原子租约。

本地摘要在每轮扫描中以单一输入流同时产生 MD5 和 SHA-256；后续基线优先复用该快照，仅在
本轮实际写入或替换后的文件需要重新验证时再读取，避免大型附件的无意义双读。

Google Drive 的非敏感选择元数据按已保存 SAF tree URI 分区。设置页读取当前 Vault 的完整绑定，
将账号不匹配显示为重新登录；后台任务在获取令牌和远端枚举前重新读取并校验 Vault/账号/root
三元组。旧全局标量仅迁移到其中记录的 Vault，不作为其他 Vault 的默认值。

## T316 实施与验证（0.6.7 / 19）

`SafeReadRetry` 负责有限尝试、服务等待建议、取消与时间预算；两个 adapter 仅在读取连接/
响应建立阶段调用。OneDrive 的认证刷新与临时重试共享尝试预算；写入保持单次请求。
同步引擎返回保留计数的终止结果，后台服务据 AUTH_REQUIRED 更新已有 OneDrive 重登录偏好。
覆盖两个 Provider 的脚本化 HTTP、连接释放、权限/认证/取消、无写入与流重放，以及引擎旧基线/
搬运确认门禁。独立全套单测/APK 和原 reviewer 复查后，证据统一记录在 `008/quickstart.md`。
T317/T329/T330 和 T848 的真实云盘、SAF、设备证据继续独立开放；不新增数据库或持久格式。
