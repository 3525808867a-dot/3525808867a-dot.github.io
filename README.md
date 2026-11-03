# K70 至尊版性能监控（KernelSU WebUI）

全中文网页式只读性能面板。模块入口位于 module/webroot/index.html。

GitHub Actions 会在推送到 main 或手动运行时，生成 K70Turbo_WebUI_中文控制台_V3.0.zip 并作为构建产物上传。

仅执行 getprop、uname、cat、grep 等只读查询，不修改 CPU/GPU 频率、电压、温控或调度参数。
