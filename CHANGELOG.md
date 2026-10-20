# Changelog

## v1.5.0 (versionCode 6)
- 新增 `SystemServerKeepAliveHooks`：仅对 LTW 绕过 CachedAppOptimizer 冻结（freezeApp*/FreezeHandler），`Process.setProcessFrozen` 兜底，自动解冻已冻结的 LTW 进程
- App Standby：LTW bucket 低于 WORKING_SET 时提升为 ACTIVE
- 扫描 Meizu/Flyme 厂商冻结类并记录，签名明确时 hook
- 剪贴板 ALLOW 日志限速（60 s）；UID 缓存改为 ConcurrentHashMap

## v1.4.0
- system_server 状态日志 `/data/local/tmp/ltwunlock-ss.log`（备用 `/data/system/`），日志页新增 system_server 分区（直读或 `su`）
- 包名缺失时按 uid 回退识别 LTW（`getPackagesForUid`，带缓存）
- 覆盖 ClipboardService（含 Flyme 变体及内部类）中更多 focus/background 判定方法；移除不完整的权限放宽

## v1.3.0
- system_server 中 hook `ClipboardService.clipboardAccessAllowed`（A12–A15 重载）及 focus 辅助方法，仅 LTW 可后台读剪贴板 / 接收剪贴板变更回调
- 日志页合并 hook 文件、app 文件与 logcat

## v1.0 – v1.2
- LTW 进程内：`isClipListenerSupported → true`，监听器接入系统 ClipboardManager
- 解锁 Clipboard/Base/Feature CapabilityProvider、Compatibility、通知实验开关等能力标志
- 设备品牌伪装默认关闭；迁移到现代 libxposed API（META-INF/xposed）
