# PACENOTE WRCG Android App

原生 Android 仪表端，竖屏主页 + 横屏指针式记速器，通过 WebSocket 接收电脑端转发的 WRCG 遥测。

完整说明见仓库根目录 [README](../README.md)。

## 快速开始

```powershell
# 打包（release，使用项目根目录 signing.properties 中的签名）
.\build-apk.cmd

# 安装到已连接设备
.\install-apk.cmd
```

产物：`app\build\outputs\apk\release\app-release.apk`（`build-apk.cmd` 会同时复制到 `..\dist\`）。

## 结构

| 文件 | 作用 |
|---|---|
| `MainActivity.java` | 竖屏主页、局域网地址输入、设置面板、横竖屏切换、返回键处理 |
| `DashboardView.java` | 指针式速度表绘制（刻度、色带、指针、挡位、数字读数） |
| `TelemetryClient.java` | 自实现的 WebSocket 客户端，解析遥测帧并回调 |
| `GlassDrawable.java` | 毛玻璃背景与按压态 |

## 构建环境

- JDK 17+
- Android SDK Platform 36、Build-Tools 36.0.0
- Gradle 9.9.1（wrapper 自动下载）
- compileSdk 36 / minSdk 26 / targetSdk 36
