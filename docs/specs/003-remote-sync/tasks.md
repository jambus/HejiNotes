# Tasks: 远端同步

同步功能在 HarmonyOS 4 APK 核心闭环完成后启动，并分别在 APK 与 HAP 实现。
现有已完成项仅代表协议模型或 HAP 侧基础实现，不代表双端交付完成。

## Phase 1 协议无关核心

- [x] T301 定义 `SyncProvider`、`SyncResult` 和冲突动作。
- [x] T302 添加单边/双边变化冲突决策单测。
- [x] T303 定义文件快照、基线、删除标记和操作计划模型。
- [x] T304 实现 SHA-256 内容摘要和本地目录扫描。
- [x] T305 [P] 实现内存模拟 Provider 与故障注入测试。
- [x] T306 实现仅在完整成功后提交基线的 `SyncEngine`。

## Phase 2 Google Drive

- [x] T307 调研并验证 Google Drive 官方账号授权与 Drive scope；已配置 Mate 60 测试用 Android OAuth 客户端。
- [x] T308 Android：使用 Google Play 服务账号授权、短期令牌获取、重新认证和脱敏错误处理。
- [x] T309 Android：实现 Drive 文件夹选择、创建目录、递归目录映射和全量远端枚举。
- [x] T310 Android：实现首次确认后的双向上传、下载、冲突保留和成功后完成记录。
- [ ] T310a Android：补齐 Drive changes 游标、条件写入、删除回收站、可恢复上传与范围下载。

## Phase 3 NAS

- [ ] T311 调研威联通 TS-251D 与极空间可共同支持的通用协议。
- [ ] T312 [P] 在局域网和外网分别验证 NAS 登录、目录读写、断点和条件写入。
- [ ] T313 实现 NAS 配置、连接、双向同步和取消 UI。
- [ ] T314 [P] 建立两台目标 NAS 的协议兼容矩阵。

## Phase 4 体验与验收

- [x] T315 Android：实现远端配置、首次确认、进度、取消和结果 UI。
- [ ] T316 实现脱敏错误摘要、退避重试和重新认证。
- [ ] T317 运行双设备冲突、弱网、中断、删除恢复和重新认证验收。
- [x] T318 Android：以提供方无关的持久任务状态与前台后台服务承载 Google Drive 同步；
  离开页面后仍可继续编辑，完成/失败通知与应用内详情共用状态模型（FR-319）。
- [x] T319 Android：远端仅下载在最终提交前发现本地同路径文件时保留远端冲突副本，并将
  同步范围收敛为 Markdown 与 `assets/`，避免同步期间覆盖本地新内容（FR-320）。
- [ ] T320 Mate 60：验证后台同步离开设置页、编辑并保存本地笔记、旋转/Activity 重建、成功/
  失败/取消/中断通知、通知禁用兜底与同路径并发冲突保留。
- [ ] T321 以本地 `MoveBundle` 变化、严格快照和成功基线规划并传播笔记/附件 bundle 移动；验证目标先上传、旧路径回收、冲突保留、连续搬运和旧路径不下载。
- [x] T322 Android：以单一 `openSyncInput` 流同时计算 MD5 与 SHA-256，并在保存同步基线时
  复用未变化文件的本轮摘要；补充输入流打开次数、摘要正确性、读取失败关闭流和大附件回归测试
  （FR-322）。
- [x] T323 Android：将 Google Drive 的分层传输收敛到共享 `RemoteDriveSyncEngine` 与
  `DriveGateway` adapter 边界；Provider 名称、目录类型、opaque revision、绑定和基线不再写死
  为 Google，保持 `.trash/` 排除、普通删除不传播、条件写入、MoveBundle 与冲突保留语义。
- [x] T324 Android：按稳定 Vault URI 隔离 Google Drive 账号/root/名称/上次成功元数据；保留账号
  不匹配绑定并要求重新登录，同步启动前重验 Vault/账号/root，覆盖 A/B 隔离和旧标量幂等迁移。

## Phase 5 OneDrive（Android 优先）

- [x] T325 更新共享设计、Vault 绑定契约、规格与验收范围，明确 OneDrive 不改变 Markdown、
  附件相对路径、冲突和 `.trash/` 语义。
- [x] T326 Android：接入 MSAL 公共客户端登录与静默令牌获取；构建期注入应用注册 ID/签名哈希，
  令牌不进入普通偏好、Vault 或日志。
- [x] T327 Android：实现 Microsoft Graph 目录选择/创建、递归列举、下载、条件替换、复制、回收站
  删除，以及小文件直接上传和大文件上传会话。
- [x] T328 Android：将 OneDrive 接入统一后台任务、Vault 租约、冲突保留和成功基线；按
  provider/vault/account/root 隔离绑定与基线并增加策略测试。
- [ ] T329 Mate 60：配置真实 Entra 应用后验证个人/组织账号登录、目录选择、大文件、后台取消、
  令牌失效、双端冲突、MoveBundle 回收旧路径和进程中断恢复；完成前不得标记候选发布。
- [ ] T330 Android：实现并验证基于三方基线的双向可恢复删除传播（远端删除且本地未修改时移入本地 `.trash/`，本地删除且远端未修改时移入 Provider 回收站，并发修改安全保留，单测及真机验证）。
