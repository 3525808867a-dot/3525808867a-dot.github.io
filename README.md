# KillSuoji · Android 应用

KillSuoji 是一款基于 Android 的 Root 工具应用。

## 当前版本
- 版本：**V3.1.0 正式版**
- 平台：Android（最低支持 Android 8.0 / API 26）
- APK：[V3.1.0 正式版 APK 直接下载（3.1.0.apk）](https://raw.githubusercontent.com/3525808867a-dot/3525808867a-dot.github.io/release/v3.1.0-formal/downloads/3.1.0.apk)
- killsuoji必要模块：[killsuoji-overlay-1.0.0-beta.zip](https://github.com/3525808867a-dot/3525808867a-dot.github.io/releases/download/v2.6.3/killsuoji-overlay-1.0.0-beta.zip)

## V3.1.0 正式版更新说明
- 恢复 Root SurfaceControl 悬浮窗文字显示，并提示实际成功结束的非白名单第三方进程数量。
- 设置页新增“工具 / 一键修复”，读取日志并尝试自动修复。
- 3 秒内连续点击普通悬浮窗 5 次进入安全模式；安全模式悬浮窗不可拖动、不可点击。
- 安全模式持续清理非白名单第三方进程，跳过所有系统进程和无法确认类别的进程。
- 进入安全模式时尝试强制停止 Shizuku 服务，支持取消并显示持续时间。
- V3.1.0 起统一使用新的稳定签名，后续版本可覆盖安装；从 V3.0.0 beta 首次升级需先卸载旧版。

## 功能简介
- Root 权限检测及工作状态切换。
- 工作状态开启后由独立 UID 0 Root 进程创建 killzuoji 系统窗口，不依赖普通悬浮窗授权。
- 控制台可管理应用白名单；勾选应用不会被结束，默认保护 KillSuoji 和 SukiSU。
- 点击 Root 窗口后，先结束非白名单用户应用，再由 Root 执行原启动脚本。
- killsuoji必要模块负责守护 Root 窗口进程，并在进程退出后尝试重新启动。
- 模块文件安装在 /data/adb/modules/killsuoji_overlay，不修改 /system、/vendor 等系统分区。

## 模块信息
- 模块版本：1.0.0 beta
- 作者：酷安ddddd
- 模块采用 SukiSU 模块管理器格式；安装后需在 Root 管理器中启用模块并授予 KillSuoji Root 权限。

## 注意
Root 窗口层级仍受 Android/厂商系统窗口权限策略约束；系统强制停止应用或限制后台启动时，模块无法保证绕过系统限制。
