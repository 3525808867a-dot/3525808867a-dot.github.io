# KillSuoji · Android 应用

KillSuoji 是一款基于 Android 的工具应用，采用 KernelSU 风格的 Miuix UI，提供 Root 权限检测、应用内工作状态切换及外观设置。

## 当前版本
- 版本：**V2.2.0 Beta**
- 平台：Android（最低支持 Android 8.0 / API 26）
- UI：Miuix · KernelSU 风格
- 下载页：https://3525808867a-dot.github.io/
- APK：[2.2.0.apk](https://github.com/3525808867a-dot/3525808867a-dot.github.io/releases/download/v2.2.0/2.2.0.apk)

## 功能简介
- **Root 权限检测**：通过执行 `su -c id` 并确认 UID 为 0，判断是否获得 Root 权限。
- **动态导航**：检测到 Root 后显示「启动」导航；未检测到 Root 时隐藏该入口。
- **工作状态切换**：在启动页面通过「执行 / 停止」切换应用内工作状态。
- **状态持久化**：工作状态保存在本地，重新打开应用后仍会保留。
- **首页状态展示**：根据 Root 检测及工作状态显示对应提示、标题和状态颜色。
- **外观设置**：支持切换 Miuix 深色模式。
- **安全说明**：工作状态仅为应用内显示状态，不会执行系统修改或强制结束其他应用、系统进程。

## 版本说明 · V2.2.0 Beta
- 当前应用版本为 V2.2.0 Beta。
- 优化 Root 权限检测及启动入口显示。
- 支持执行 / 停止状态切换，并在重新打开应用后保留状态。
- 更新首页状态文字与状态颜色显示。
- 使用 Miuix 风格界面，提供深色模式设置。

## 项目说明
本仓库包含 KillSuoji Android 应用源码、专属下载页面及 APK 发布文件。


（当前为beta测试版，如有bug请联系作者）
