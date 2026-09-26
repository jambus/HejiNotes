# Android OneDrive OAuth Setup

禾记 Android 使用 Microsoft Authentication Library (MSAL) 的公共客户端流程，不使用或打包
客户端密钥。OneDrive 内容通过 Microsoft Graph 的委托权限访问。

## Entra 应用注册

1. 在 Microsoft Entra 管理中心创建应用注册，支持“任何组织目录中的账户和个人 Microsoft 账户”。
2. 添加 Android 平台，包名填写 `com.jambus.heji`，签名哈希分别登记实际 debug/release 证书。
3. 启用公共客户端流；添加 Microsoft Graph 委托权限 `Files.ReadWrite`。MSAL 会自动处理
   `openid`、`profile` 与 `offline_access`，不要配置应用程序权限或客户端密钥。
4. 记录应用（客户端）ID，以及 Android 平台显示的 Base64 签名哈希。发布签名与 debug 签名
   必须分别登记，不能复用或把签名私钥提交到仓库。

## 本机构建配置

在用户级 `~/.gradle/gradle.properties` 或 CI 密钥配置中提供：

```properties
HEJI_ONEDRIVE_CLIENT_ID=00000000-0000-0000-0000-000000000000
HEJI_ONEDRIVE_SIGNATURE_HASH=Base64SignatureHashFromEntra
```

可通过同名环境变量或 Gradle 属性提供。`HEJI_ONEDRIVE_SIGNATURE_HASH` 支持直接粘贴 Entra 平台配置显示的原始 Base64 签名散列，也支持粘贴已 URL 编码的字符串；构建逻辑会自动将其规范化为 AndroidManifest 匹配所需的原始散列，并在 MSAL 内部正确进行 URL 编码。客户端密钥、访问令牌与刷新令牌不得加入 Gradle 属性。没有配置时 APK 仍可构建，但设置页会明确显示 OneDrive 尚未配置，且不会启动授权或网络访问。

MSAL 的回调 URI 为 `msauth://com.jambus.heji/<URL-encoded-signature-hash>`。若登录回调失败，
先核对 APK 实际签名与 Entra Android 平台配置，再检查包名和两项 Gradle 属性。

## 验收边界

- 个人 Microsoft 账户和一个组织账户各完成一次授权、目录选择、同步与静默刷新。
- 撤销授权或切换账号后保留原 Vault 绑定并显示“需要重新登录”，不会静默改绑。
- 检查普通偏好、Vault、通知和日志，不得出现访问令牌、刷新令牌或 Graph 预授权下载 URL。
- OneDrive 真机验收完成前，开发构建不得标记为候选发布。
