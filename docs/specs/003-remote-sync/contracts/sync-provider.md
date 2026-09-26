# Contract: SyncProvider

Provider 负责协议转换，不决定冲突策略。

## Required Operations

- `connect()`：验证配置和凭据，不修改远端内容。
- `listChanges()`：不传游标时返回完整远端快照和新的 opaque 游标。
- `listChanges(cursor, baselineFiles)`：传入上次成功游标和远端 ID/路径基线时，
  返回分页增量与删除标记。
- `download(path, expectedRevision?)`：返回可分块读取的 `SyncContentSource`。
- `upload(path, source, expectedRevision?)`：从可分块读取的数据源条件写入，版本
  不符必须报冲突。
- `delete(path, expectedRevision?)`：条件删除。
- `cancel()`：尽快停止未完成网络操作。

每页返回 `changes`、续页或最终 `cursor` 以及 `hasMore`。同步引擎不得在
`hasMore` 为 `true` 时提交该游标。

对于层级型 Provider，每页还可返回当前远端目录 ID 映射。该映射只用于解析
路径和目录 tombstone，不作为本地内容文件同步。

## Invariants

- 相对路径必须规范化并禁止 `..` 逃逸笔记本根目录。
- 所有写操作必须幂等或携带可安全重试的操作 ID。
- Provider 不得记录凭据、正文或临时下载 URL。
- Provider 名称、目录判定和 native-document 判定由 adapter 明确提供；同步引擎不得把 Google
  MIME 类型或名称写死到跨 Provider 冲突与路径逻辑中。
- 返回的 revision 与 cursor 视为 opaque 字符串。
- 网络失败不得被解释为远端文件不存在。
- 无游标枚举必须包含全部现存文件，不能只返回近期变化。
- Provider 和本地仓储必须关闭内容源；大图片不得要求一次性载入内存。
