# PACENOTE WRCG Android App

原生 Android Canvas 仪表，通过 WebSocket 接收电脑端 Node 服务转发的 WRCG 遥测。

## 安装

调试 APK：

```text
app\build\outputs\apk\debug\app-debug.apk
```

可以直接复制到手机安装；Android 需要允许当前文件管理器或浏览器“安装未知应用”。调试 APK 已使用 Android Debug 证书签名。

支持 Android 8.0（API 26）及以上。

连接 USB 并授权 ADB 后，也可以直接双击 `install-apk.cmd` 安装。

## 首次使用

1. 在电脑端运行桌面程序或源码版一键启动脚本，确保服务监听 `8787`。
2. 手机与电脑连接同一 Wi-Fi 或手机热点。
3. 启动 `PACENOTE WRCG`，在地址对话框输入电脑地址，例如：

```text
192.168.1.100
```

也可以输入完整地址：

```text
ws://192.168.1.100:8787/ws
```

应用会自动补上 `ws://`、`8787` 和 `/ws`。

连接后随时**长按屏幕**可以修改电脑地址。

## 仪表

- 六根全屏竖柱显示转速
- 挡位文字视觉高度约为屏幕高度的 80%
- 右下角显示车速
- 最大转速 88% 以上触发红色全屏爆闪
- 自动重连、横屏、沉浸式全屏、屏幕常亮
- 双指上滑打开设置；主页仅保留主题、转数与效果、连接设置、延迟检查入口和软件信息
- 各设置项使用独立子页面，支持 Android 14+ 跟手预测返回动画
- 连接设置包含电脑地址和重连按钮，延迟检查会采样 10 次并显示最小、平均、最大延迟
- 爆闪设置支持关闭全屏爆闪，并可分别设置 5 挡、6 挡不爆闪

## 构建

使用 Android Studio 打开 `android-app`，或在本目录执行：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

也可以直接双击 `build-apk.cmd`。

APK 输出：

```text
app\build\outputs\apk\debug\app-debug.apk
```
