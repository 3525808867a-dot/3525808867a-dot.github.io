# KillSuoji · Android 应用

KillSuoji 是一款基于 Android 的 Root 工具应用。

## 当前版本
- 版本：**V2.5.7**
- 平台：Android（最低支持 Android 8.0 / API 26）
- APK：[下载 2.5.7.apk](https://github.com/3525808867a-dot/3525808867a-dot.github.io/releases/download/v2.5.7/2.5.7.apk)
- killsuoji必要模块：[killsuoji-overlay-1.0.0-beta.zip](https://github.com/3525808867a-dot/3525808867a-dot.github.io/releases/download/v2.5.7/killsuoji-overlay-1.0.0-beta.zip)

## 功能简介
- Root 权限检测及工作状态切换。
- 工作状态开启后显示固定的 killzuoji 悬浮窗。
- 控制台可管理应用白名单；勾选应用不会被结束，默认保护 KillSuoji 和 SukiSU。
- 点击悬浮窗后，先结束非白名单用户应用，再由 Root 执行原启动脚本。
- killsuoji必要模块在后台服务被回收后尝试重新拉起悬浮窗服务。
- 模块文件安装在 /data/adb/modules/killsuoji_overlay，不修改 /system、/vendor 等系统分区。

## 模块信息
- 模块版本：1.0.0 beta
- 作者：酷安ddddd
- 模块采用 SukiSU 模块管理器格式；安装后需在 Root 管理器中启用模块并授予 KillSuoji Root 权限。

## 注意
Android 系统强制停止应用、关闭悬浮窗权限或限制后台启动时，模块无法保证绕过系统限制。
