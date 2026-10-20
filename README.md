# LtwUnlock

> [!CAUTION]
> **🤖 本项目完全由 AI 编写，代码未经任何人工审查。**
> 代码实现、构建和文档全部由 AI 助手完成；作者仅在自己的设备上做了功能测试。模块会注入 system_server，请在理解风险后自行决定是否使用。
>
> **🤖 This project was written entirely by AI. The code has NOT been reviewed by a human.** Implementation, build and docs were all done by an AI assistant; the author only tested it on their own device. It hooks system_server, so use it at your own risk.

让微软「连接到 Windows」（Link to Windows，`com.microsoft.appmanager`）在非官方支持的 Android ROM 上正常工作的 LSPosed 模块（现代 libxposed API 101）。目前仅在魅族 Flyme 上测试。

> ⚠️ 非官方项目，与 Microsoft 无关，也未获其认可或背书。详见文末免责声明。

## 功能

1. **LTW 应用内**：强制启用原生（organic）剪贴板监听器，解锁剪贴板等能力标志位。
2. **system_server · 剪贴板**：仅让 LTW 通过 `ClipboardService` 的后台焦点检查（其他应用不受影响），实现后台剪贴板同步。
3. **system_server · 防冻结**：阻止 cached-app freezer 冻结 LTW（仅 LTW），修复后台断连和延迟；并把 LTW 的 App Standby bucket 提升到 ACTIVE。

## 要求

- Android 10+（minSdk 29），已 Root
- 支持**现代 API（libxposed API 101+）**的 LSPosed
- 自行从官方渠道安装「连接到 Windows」
- LSPosed 作用域勾选：**系统框架** + **连接到 Windows**
- 启用后**重启手机**（system_server 钩子必须重启才生效）

## 安装

1. 从 [Releases](../../releases) 下载 APK 并安装
2. LSPosed Manager → 模块 → 启用 LtwUnlock，勾选上述作用域
3. 重启；在 LTW / Phone Link 中开启剪贴板同步

## 验证

- 打开桌面 **LTW Unlock** → 日志页 → Refresh，查看 `system_server` 分区中的 `hooked ...`、`ALLOW ...`、`[KeepAlive] ...` 记录（文件：`/data/local/tmp/ltwunlock-ss.log`）。
- 检查冻结状态（LTW 在后台几分钟后应为 `0`）：

```bash
UID=$(adb shell dumpsys package com.microsoft.appmanager | grep -m1 userId= | sed 's/.*userId=\([0-9]*\).*/\1/')
for PID in $(adb shell pidof com.microsoft.appmanager com.microsoft.appmanager:pnsvc); do
  adb shell su -c "cat /sys/fs/cgroup/apps/uid_${UID}/pid_${PID}/cgroup.freeze"
done
adb shell am get-standby-bucket com.microsoft.appmanager   # 期望 10
```

（部分 ROM 路径为 `/sys/fs/cgroup/uid_X/pid_Y/cgroup.freeze`。）

## 限制

- **不支持**热点共享、通话/手机应用、屏幕镜像等功能
- 仅在 Flyme 上测试
- 厂商自有冻结机制（如 MIUI Millet、ColorOS Hans 等）可能未覆盖；厂商「后台管理/省电」仍建议手动设为允许后台运行

## 构建

需要 JDK 17、Android SDK（build-tools 34.0.0、platform android-34）。

```bash
export ANDROID_HOME=/path/to/android-sdk
export KS_FILE=/path/to/your.jks KS_PASS=... KS_ALIAS=...   # 或写入未跟踪的 signing.local.sh
./build.sh      # 输出到 dist/
```

`lib/` 中包含 [libxposed](https://github.com/libxposed) 的 `libxposed-api.jar` 与 `libxposed-annotation.jar`（仅编译期使用，Apache License 2.0）。`stubs/` 为编译期 androidx 注解桩。

## 免责声明

- 本项目为**非官方**模块，与 Microsoft 无任何关联，未获 Microsoft 认可或背书。「Link to Windows」「Phone Link」等为 Microsoft 的商标。
- 本仓库**不包含任何 Microsoft 代码或 APK**，用户须自行安装官方应用。
- 使用风险自负；修改系统行为可能导致不稳定。使用 Microsoft 服务时仍可能受 Microsoft 账户/服务条款约束。

## 许可证

GPL-3.0，见 [LICENSE](LICENSE)。

---

## English

**LtwUnlock** is an LSPosed module (modern libxposed API 101) that makes Microsoft *Link to Windows* (`com.microsoft.appmanager`) work on unsupported ROMs. Tested only on Meizu Flyme.

- **Features:** forces LTW's organic clipboard listener and unlocks capability flags; in system_server lets *only* LTW pass ClipboardService's background-focus check; stops the cached-app freezer from freezing *only* LTW (fixes background disconnects/latency).
- **Requirements:** root + LSPosed with modern API support; scope **System Framework** + **Link to Windows**; reboot after enabling.
- **Verify:** module log page; `cgroup.freeze` under `/sys/fs/cgroup/apps/uid_X/pid_Y` should stay `0`.
- **Limitations:** hotspot, phone/calls and screen mirroring not supported; vendor freezers (MIUI Millet, ColorOS Hans…) may not be covered.
- **Disclaimer:** unofficial; not affiliated with or endorsed by Microsoft. No Microsoft code included — install the official app yourself. Use at your own risk; Microsoft account/service terms may apply.
- **License:** GPL-3.0.
