# Mate 60 设备连接与 APK 安装排障

本文记录在 macOS 上连接 HarmonyOS 4 Mate 60、检查调试通道并安装 Android APK 的常用步骤。

## 前置检查

1. 在手机上开启开发者模式和 USB 调试。
2. 使用支持数据传输的 USB 线连接电脑，并将 USB 用途切换为“传输文件”。
3. 保持手机解锁；出现 USB 调试或调试密钥授权提示时，允许当前电脑连接。

## ADB 未发现设备

如果 `adb devices -l` 没有列出设备，先重启 ADB 服务：

```bash
adb kill-server
adb start-server
adb devices -l
```

正常情况下，最后一条命令会显示设备序列号，状态为 `device`。随后在仓库根目录执行：

```bash
./scripts/install-apk.sh
```

安装脚本会优先使用 ADB，并安装
`android/app/build/outputs/apk/debug/app-debug.apk`。

### 常见状态

- `device`：连接和授权正常，可以安装 APK。
- `unauthorized`：解锁手机并确认 USB 调试密钥；如果没有弹窗，撤销已有 USB 调试授权，关闭再开启 USB 调试，然后重新插拔数据线。
- 设备列表为空：确认 USB 模式为“传输文件”，重新插拔数据线，并再次执行上述三条命令。
- `offline`：重新插拔设备；仍未恢复时，重启 ADB 服务并重新确认手机授权。

## HDC 输出异常

执行 `hdc list targets` 时：

- 显示 `connect-key`：手机已经被发现，但尚未允许当前电脑的调试密钥。解锁手机并确认授权后重试。
- 显示 `[Empty]`：HDC 当前没有发现可用的设备调试通道。对于 HarmonyOS 4 Android APK，优先按上一节使用 ADB 连接和安装。

如果 macOS 能识别手机，但 ADB 和 HDC 均无设备，请依次检查 USB 调试开关、USB 传输模式、手机端授权提示、数据线和 USB 接口。
