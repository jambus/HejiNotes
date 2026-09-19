# Implementation Plan: 拍照与本地附件

## Technical Context

Android 端使用系统相机 Intent/Activity Result，HarmonyOS 端使用
`ohos.want.action.imageCapture`。两端都将返回 URI 的内容复制到 Vault 附件目录，
使用拍摄时间加短随机码命名，并从文件签名字节确定扩展名和 MIME 类型。

Android 照片会话由应用级协调器原子推进阶段。正文提交是不可取消边界：边界前接受取消并回滚已知
未引用附件，边界后不做补偿性正文写入。提交前使用进入流程时的正文 SHA-256 防止覆盖外部修改；
提交后以重读链接结果决定确认、回滚或保留事务标记。

## Constitution Check

- [x] 使用系统相机，不额外申请直接控制相机权限。
- [x] 附件写入后使用相对 Markdown 链接。
- [x] 文件与正文按事务式流程提交。
- [x] 取消、空间不足和崩溃可通过事务标记恢复。
- [ ] 真机完成拍照、重启和连续多拍验证。

## Transaction

1. 对应平台的系统相机返回 URI。
2. 复制到 `.markbook/tmp/`，校验非空、同步落盘并识别文件签名。
3. 写入附件事务标记，再原子移动到笔记父目录的 `assets/<笔记文件名>/`。
4. 在当前选区插入相对链接并原子保存笔记。
5. 删除事务标记并更新预览；保存失败则回滚附件和正文。
6. 应用启动时扫描未完成标记：已被 Markdown 引用的附件保留，未引用附件删除。
7. 视频使用 `ACTION_VIDEO_CAPTURE`，持久化不含正文的待处理会话；确认后流式复制，校验
   大小、时长、容器签名字节/MIME 和 video 轨，在 hash 校验未外变的正文原光标处写入普通链接；
   final rename 结果不确定时保留 marker，已保存链接的 pending 会话只清理不重放。
8. 笔记 bundle 已移动时，从更新后的笔记父目录解析附件目标，旧根 `assets/` bundle 只作兼容读取。
