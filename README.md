# KillSuoji · Android 应用

KillSuoji 是一款基于 Android 的工具应用，提供 Root 权限检测及应用内工作状态切换。

## 当前版本
- 版本：**V2.4.9 beta**
- 平台：Android（最低支持 Android 8.0 / API 26）
- 下载页：https://3525808867a-dot.github.io/
- APK：[2.4.8.apk](https://github.com/3525808867a-dot/3525808867a-dot.github.io/releases/download/V2.4.9 beta/2.4.8.apk)

## 功能简介
- **Root 权限检测**：通过执行 `su -c id` 并确认 UID 为 0，判断是否获得 Root 权限。
- **动态导航**：检测到 Root 后显示「启动」导航；未检测到 Root 时隐藏该入口。
- **工作状态切换**：在启动页面通过「执行 / 停止」切换应用内工作状态。
- **状态持久化**：工作状态保存在本地，重新打开应用后仍会保留。
- **首页状态展示**：显示 Root 检测结果及当前应用版本。
- **启动脚本**：首次启动时以 Root 创建 `/data/local/tmp/launch_killapp.sh`；若文件已存在则不覆盖。每次检测到连续五次音量下键时，以 Root 执行该脚本。

## 版本说明 · V2.4.9 beta
- 当前应用版本为 V2.4.9 beta。
- 优化 Root 权限检测及启动入口显示。
- 支持执行 / 停止状态切换，并在重新打开应用后保留状态。
- 更新首页状态文字与状态颜色显示。
- 使用固定 APK 签名证书，为后续版本覆盖安装提供一致签名。\n- 连续按 5 次音量下键时通过 Root 权限直接启动 KillSuoji，无需悬浮窗权限。
- 精简主页与设置页面，移除指定的状态文字及 UI 框架说明。

## 项目说明
本仓库包含 KillSuoji Android 应用源码、专属下载页面及 APK 发布文件。

（当前为beta测试版，如有bug请联系作者）
