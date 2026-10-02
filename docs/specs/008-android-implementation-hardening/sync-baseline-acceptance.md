# D16–D19 / FR-829–FR-832 同步加固验收

日期：2026-10-01 至 2026-10-02。状态：编译、JVM 单元测试和 fresh debug APK 通过；
待实际 SAF、结果持久化迁移、UI 与真实云盘/Mate 验收，非候选。
范围：Android Google Drive / OneDrive 共用引擎、本地下载确认和本机结果/基线元数据；Vault 格式、
路径、删除契约和同步范围不变。当前开发包为 0.6.3（15），未安装；此前 0.6.2（14）
已用原签名覆盖安装，0.6.1（13）测试包仅为历史证据。安装不代表功能验收，未标记候选或发布。

## 固定验收规则

- 云端新增、本地缺失：下载。已验证基线后云端更新、本地缺失：保留并下载。
- 已验证基线后本地删除、云端未更新：云端确切文件进入 Provider 回收站，非永久删除。
- 双边修改或旧基线无法确定一致性：保留内容，必要时产生冲突副本。
- 最终路径集合/内容漂移、未知读取、取消或保存失败：旧基线与移动确认不变，允许重试。
- 旧记录默认未验证；同 ID/非空修订下校验远端 SHA-256、复核修订后才能采纳。

## 证据

| 检查 | 当前结果 |
| --- | --- |
| `git diff --check` | 主代理及独立测试者通过 |
| `./scripts/verify-vault-contract.sh` | 主代理及独立测试者通过 |
| `:app:testDebugUnitTest :app:assembleDebug --no-daemon`（JDK 17 fallback） | 2026-10-02 独立测试者运行成功：43 suites / 340 tests / 0 failures / 0 errors / 0 skipped；36 tasks，19 executed |
| 新增双 Provider、并发漂移、旧基线、无 MD5、修订复核回归 | 同步集成 26 项、下载提交保护 13 项、失败/结果隔离策略 4 项全部通过 |
| 独立测试者复核 | 修复后全套测试及 fresh debug 构建通过；diff/Vault fixture/XML 检查通过 |
| 原设计评审者最终复查 | 确认 FR-829–FR-832 源码方案及自动化构建证据通过；实际 SAF、JSON 迁移、UI/设备验证仍待完成 |
| Mate / 真实 Google 与 Microsoft 账号 | 0.6.2 原签名覆盖安装成功；实际 SAF、UI、JSON 迁移、真实账号/云盘流程未执行 |

## D17–D19 续修（2026-10-02）

- 拒绝参与同步的重复文件/目录名称和路径分隔歧义；初始预检失败不修改两侧。
- 本地下载严格解析唯一父目录，核对流式/暂存/最终内容以及名称、身份和路径。网络传输
  在保存锁外；最终提交/安全恢复与编辑保存互斥。每次重新核对从 Vault 根到目标的祖先身份。
- 未确认提交保留 `.markbook-sync-*.pending/.previous`；启动恢复将其视为 Ignore，不自动
  清理。仅证明目标不存在、暂存仍在原位、原始备份哈希/身份不变才允许恢复。
- 同步结果保留安全原因代码、显式相对路径；旧异常原文不解析。全局活动任务仍单一，
  已结束结果按 Vault 单向标识/Provider 隔离，最多保留 16 个组合，详情选择在刷新/重建中保持。
- 独立测试及原评审已复核单次原始哈希、防父目录外变和副本保留修复，无剩余源码阻断发现。
  新故障/诊断 JVM 用例已运行通过；JSON 持久化兼容仅源码复核，尚无运行验证。
- Diff、Vault fixture 和两套资源 XML 检查通过。此前指定 JBR 不存在、SDK 缺失及 Gradle
  distribution 下载超时均是历史受阻记录，现已由下面的 fresh 验证替代。
- 主机在本轮期间新增 jenv JDK 17；主代理改用 `/Users/jambus/.jenv/versions/17` 与临时
  Gradle 缓存重试构建。沙箱 DNS 失败后已提权重试，Gradle distribution 下载约 80% 时
  发生 `SocketTimeoutException`，exit 1；当时未进入编译。
  新出现的 `.java-version` 属于其他工作，未修改。

## 本次 fresh 验证（2026-10-02）

用户完成安装后确认 JDK 17.0.20.1、Android API 34 和 Build Tools 30.0.3 可用；DevEco
JBR 未安装，因此显式使用 jenv JDK 17。独立测试者从 `android/` 执行以下命令（Gradle
缓存权限受限后获批提权），约 1m47s 完成。主代理独立汇总 XML 并核对 APK 校验值。

```bash
JAVA_HOME=/Users/jambus/.jenv/versions/17 \
ANDROID_HOME=/Users/jambus/Library/Android/sdk \
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

- APK：`android/app/build/outputs/apk/debug/app-debug.apk`，7,183,932 bytes；
  生成时间 2026-10-02 06:42:27 +0800，`versionName=0.6.1` / `versionCode=13`。
- SHA-256：`ffe0239698bf0fd10c80fe437c3c05ce820cda09440a07998bc775e87b807a8e`。
- AGP 7.3 对 compileSdk 34 的兼容性提示和既有弃用/未使用变量提示未阻断构建；本次未扩大
  到工具链升级或 targetSdk 迁移。

真机按 `quickstart.md` V14/V15 使用专用测试 Vault，不使用个人笔记注入故障。上述 JVM
模拟不替代实际 SAF Provider、偏好 JSON 持久化/旧数据迁移、旋转/语言切换或实云盘验收。

## 0.6.2 原签名重包与升级（2026-10-02）

用户恢复旧电脑调试密钥，主代理确认公开证书与手机原安装一致；版本递增为 0.6.2（14）。
重建时沿用手机旧 APK 的公开 OneDrive 注册参数；不复制任何账号令牌。

- 主代理显式使用 JDK 17/SDK，增加 `--rerun-tasks` 重跑完整单测和 debug APK 构建，
  BUILD SUCCESSFUL（24s），36 tasks executed；XML 43 suites / 340 tests，0 failures/errors/skips。
- 生成时间 2026-10-02 07:03:25 +0800，7,184,016 bytes；APK SHA-256：
  `f59c40e4d33c2fa791fadac87dc518af04583b335e42411f887445602d16a311`。
- 公共证书 SHA-256：`b3be289894592585b98c0ed9076f98612e7cddb7c7574c6343ce0b400d018e20`，
  SHA-1：`6e32054f822d2f598423c7df2618ed32cc43d8e6`，与手机原 APK 一致。
- `adb -s <设备> install -r <fresh APK>`：Success。设备 Mate 60 ALN-AL10，安装后
  `versionName=0.6.2` / `versionCode=14`；首次安装 2026-09-12 23:56:20 未重置，
  更新 2026-10-02 07:04:30，未卸载或清除应用数据。未启动同步或操作个人 Vault。
- OneDrive 原 BuildConfig 的注册签名哈希为 `ES5czcP6wEUTzdj4h7jNF/vjUtM=`，实际证书
  对应 `bjIFT4ItL1mEI8ffJhjtMsxD2OY=`。两者不同；本次保留原配置，登录仍需核对微软后台
  登记及实测，不将恢复公开参数视作账号授权验证。
- Diff/Vault fixture 检查通过。0.6.1 校验值仅为历史结果；实云盘、SAF、JSON 迁移和 UI
  的验证限制不因本次安装成功而撤销。

## 0.6.3 D20–D24 复审补齐（2026-10-02）

范围对应 FR-833–FR-837，保留历史已验证代码和 OneDrive 用户配置，版本递增为 0.6.3（15）。

- Google 覆盖/回收在打开修改请求或读取上传源前校验一个合法强 ETag；不存在可用值时
  安全拒绝，不构造虚假 ETag 或无条件重试。实际服务是否提供/执行条件仍待实云盘验证。
- 所有写入 Vault 的下载通过独立云端摘要及 EOF 修订/路径检查；缺 MD5 时先独立读取
  MD5/SHA-256 并绑定同一修订，再校验实际流。新的 metadata 摘要出现时也参与比较。
  校验/取消检查在本地提交锁外完成；错误正常 EOF 不会清理原文件/备份。
- 上传/复制重走固定远端根的唯一目录链，比较缓存身份；新目录重新列举验证。检查后
  网络竞态仍可能发生，最终目录身份/路径/内容比较拒绝成功，不宣称完全原子。
- UI 读取全局活动任务 preflight；服务 race 拒绝仅更新请求方 scoped history/独立通知，
  不替换原任务或协调器。确认页观察 Provider 状态，恢复可重试入口。
- 旧 JSON 通过共享锁实际写回脱敏结果。新增测试专用 `org.json` 依赖用于真正序列化/
  偏好代理测试；生产依赖、Vault 格式和 HAP 运行契约不变。
- 专项第一次 109 项中新增 HTTP 替身的 PATCH 测试失败（桌面 JDK 默认 setter 不支持
  PATCH）；修正测试替身后专项通过。原 reviewer 又发现移动重扫覆盖原始目录身份；
  修正为独立证明 Map，并在使用重扫结果前拒绝变更、只记录验证创建的目录，补充双 Provider
  零复制/回收/确认/基线写入回归后，独立测试者重新执行完整命令。

### 最终证据

- 原 reviewer 修复后源码复查通过，独立测试者最终验证：45 suites / 360 tests，0 failures /
  errors / skips；22s，36 tasks executed。报告时间 2026-10-02 08:23:13 +0800。
- 同步集成 34 项、远端安全 6 项、真实 JSON 迁移/拒绝/并发 4 项、确认策略 4 项通过。
  主代理独立汇总 XML、核对 APK 校验值。Diff、Vault fixture 和两套资源 XML 通过。
- fresh debug APK：0.6.3（15），7,194,276 bytes，生成 2026-10-02 08:23:10 +0800；
  SHA-256 `c91d7fc2040a49b042e117b669b20fc70b5a3b5c3ca5f3075a72e82acc658daf`。
- 早先 359 项的中间构建已被替代，不作为最终交付。应用运行依赖未增加 JSON 库，新增
  `org.json` 仅在 JVM 单测类路径用于真正执行持久化逻辑。
- 实际 Google ETag 返回/条件执行、实际 SAF、Android Activity/通知、实云盘/设备仍未验证；
  没有触发 Google/OneDrive 真实同步。本次没有自动安装 APK。

未自动安装本次包或触发个人 Vault/真实云盘同步。真机验收仍按 V14–V16 使用专用测试 Vault。
