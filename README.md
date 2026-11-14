# WRCG 无线仪表盘

电脑接收 WRCG 遥测，通过本地 WebSocket 转发，原生 Android App 或浏览器显示赛车仪表。

原生 Android Canvas 版本位于 `android-app/`，构建后可生成调试 APK。

```text
WRCG UDP :20777 -> 电脑 EXE :8787 -> Wi-Fi -> Android App
```

项目只处理局域网内的本地遥测，不上传账号、设备地址或驾驶数据。详情见 [隐私说明](PRIVACY.md)。

## 当前已实现

- 提供无需安装 Node.js 的 Windows 单文件桌面程序 `dist/WRCG-Dash-Desktop.exe`
- 桌面程序内嵌仪表网页和 Node.js 运行时，启动后自动打开本机仪表窗口
- 首次启动自动备份并启用 WRCG 遥测；游戏运行时不会修改配置文件
- 桌面程序会把原始遥测样本写入 `%LOCALAPPDATA%\WRCG Dash\captures`
- 无需安装 npm 依赖，直接使用 Node.js 内置模块
- 手机横屏专用仪表：超大挡位、附加车速、RPM 数字和六柱全屏转速显示
- 六根贯穿全屏高度的竖柱均分屏幕，左右各三根，按外侧、中间、内侧顺序向中心汇聚点亮
- 点亮后的竖柱使用完全不透明颜色，红转时六柱同步转为不透明红色
- 挡位文字视觉高度约等于屏幕高度的 80%
- 目标 30 Hz 转发；RPM 不再插值延迟，达到 88% 后触发高频红色爆闪
- 支持全屏和屏幕常亮
- 浏览器断线自动重连，并显示连接与网络延迟
- WRCG UDP `20777` 监听，支持 JSON 格式遥测输入
- 已根据本机 WRCG 实车样本适配 280 字节 UDP 结构，可读取轮速、RPM、挡位、油门、刹车、离合和赛段进度
- 保留同引擎 WRC 96 字节结构作为兼容后备；字段超出合理范围时不会强行采用
- WRCG 实时数据会自动按约 10 包/秒采样到 `.captures/`，便于核对混动与涡轮字段
- 内置动态模拟数据，没有游戏时也能检查界面和手机延迟
- 模拟模式使用 6 挡连续拉转和制动降挡节奏，每挡末段进入红转；60 秒内约出现 18 次红转和 28 次挡位变化
- PWA 安装与离线界面外壳

## 普通用户启动

把以下文件发送给另一台 Windows 10/11 64 位电脑：

- `dist/WRCG-Dash-Desktop.exe`
- `dist/使用说明.txt`
- 手机端 `dist/WRCG-Dash-0.3.0-debug.apk`

先启动一次 WRCG 以生成配置，完全退出游戏，再双击 `WRCG-Dash-Desktop.exe`。程序会自动启用遥测、打开电脑端仪表，并打印手机应输入的连接地址。

程序首次联网时，Windows 防火墙可能弹出提示。请允许它在“专用网络”上通信，否则同一局域网内的手机无法连接。

当前 EXE 没有商业代码签名证书，Windows 可能显示“未知发布者”。这不影响功能，但系统中可能出现 SmartScreen 提示。

## 源码版一键启动

面向已经安装 Node.js 20 或更高版本和 Android Studio 开发环境的用户。双击项目根目录的：

```powershell
.\一键启动-WRCG仪表盘.cmd
```

脚本会使用当前源码启动桌面服务、自动配置 WRCG 遥测并打开仪表。原有的 `start.cmd` 和 `start.ps1` 仍可使用。

也可以从终端运行：

```powershell
npm run start:desktop
```

启动后访问：

- 本机：`http://127.0.0.1:8787`
- 手机：使用启动时打印的局域网地址，例如 `http://192.168.1.100:8787`

手机和电脑需要连接同一个 Wi-Fi。首次启动时，如果 Windows 防火墙弹窗出现，请允许 Node.js 在“专用网络”上通信。

启动日志会标注网卡名称。手机应使用带有 `(WLAN)`、`(Wi-Fi)` 或 `(Ethernet)` 的地址；带有“虚拟网卡”标记的地址通常来自 VPN、Wintun 或代理软件，手机无法直接访问。

如果知道电脑 WLAN 地址后仍无法打开：

1. 确认手机没有连接另一个 Wi-Fi，并暂时关闭手机 VPN。
2. 在电脑上确认 `http://电脑WLAN地址:8787/healthz` 返回 `{"ok":true}`。
3. 检查无线网络是否开启了“访客隔离”或“AP 隔离”；这两种模式会禁止同一 Wi-Fi 内的设备互访。
4. 如果网络无法解除隔离，可以让手机开启热点，电脑连接该热点后使用新显示的 WLAN 地址。

没有游戏数据时，仪表会运行模拟模式。

## 构建 Windows EXE

构建需要 Windows 和 Node.js 20 或更高版本，运行 EXE 的电脑不需要 Node.js。

```powershell
npm install
npm run build:desktop
```

构建脚本会：

1. 把 `public/` 页面资源嵌入 JavaScript 包。
2. 使用 Node.js SEA 生成独立运行时。
3. 注入桌面程序并自动执行健康检查。
4. 输出 `dist/WRCG-Dash-Desktop.exe`、`dist/使用说明.txt` 和 `dist/desktop-build.json`。

`desktop-build.json` 记录构建版本、文件大小和 SHA-256。当前构建产物是 x64 程序。

## 启用 WRCG 遥测

桌面 EXE 和源码版一键启动脚本会自动完成下面的配置。只有需要手动修复配置时，才需要执行本节命令。

先退出 WRCG，然后在项目目录运行：

```powershell
.\tools\configure-wrcg.cmd -Enable
```

脚本会备份原配置，然后设置：

```text
WRC.Telemetry.EnableTelemetry = true
WRC.Telemetry.TelemetryAdress = "127.0.0.1"
WRC.Telemetry.TelemetryPort = 20777
WRC.Telemetry.TelemetryRate = 30
```

查看状态：

```powershell
.\tools\configure-wrcg.cmd -Status
```

关闭遥测：

```powershell
.\tools\configure-wrcg.cmd -Disable
```

配置修改后启动 WRCG 并进入一个赛段。电脑端收到数据后，页面右上角状态会变化：

- `实时数据`：数据格式已识别，仪表直接更新
- `原始采集`：WRCG 数据已经到达，但该版本二进制字段尚未映射，服务正在录制样本

## 关于 WRCG 二进制适配层

KT Racing 的游戏支持原生 UDP 输出，但没有官方稳定的二进制字段文档。当前原型已根据本机 WRCG 实际赛段数据确认 280 字节包中的主要车辆字段，并加入数值范围校验。其他版本如果包布局不同，解析器会拒绝该包并进入原始采集模式，不会用错误字段冒充真实数据。

当前 UI 已移除电机、涡轮、油门和刹车显示。后台仍保留这些数据的解析与原始包录制能力，便于以后确认 WRCG 是否提供可用的混动或涡轮通道；它们不会占用当前仪表屏幕。

WRCG 数据接入后，`.captures/` 下会出现类似文件：

```text
wrcg-2026-11-11T13-33-55-019Z.packets.jsonl
```

每行包含时间、来源地址、包长度和原始数据。下一步可根据真实赛段样本完成字段映射，然后把解析结果接入现有的 `normalizeTelemetry()` 数据协议，手机端不需要重写。

## 开发与测试

```powershell
npm test
```

测试覆盖遥测归一化、模拟数据、HTTP 页面、UDP 接收和 WebSocket 握手。

## 延迟诊断

连接诊断中新增以下指标：

- `网络往返`：手机发送 Ping 到收到电脑响应的完整时间。
- `遥测帧年龄`：借助双向时钟估计，当前画面数据从电脑发送到手机显示的估计年龄。
- `页面渲染`：浏览器从收到数据到完成页面更新的耗时。
- `发送积压`：电脑 WebSocket 发送缓冲区峰值和因积压丢弃的旧遥测帧数量。

如果网络往返持续高于 100 ms，而发送积压接近 0，瓶颈在 Wi-Fi 或手机网络处理；如果发送积压持续增长，说明电脑到手机链路无法及时清空 TCP 队列。

主要文件：

- `src/server.js`：HTTP、UDP 和 WebSocket 转发服务
- `src/desktop-entry.js`：桌面 EXE 启动、浏览器窗口和连接提示
- `src/wrcg-settings.js`：WRCG 配置备份与自动启用
- `src/protocol.js`：统一遥测格式与解析入口
- `src/demo.js`：模拟遥测
- `src/capture.js`：未知 WRCG 数据包采样
- `public/`：手机仪表界面
- `tools/build-desktop.js`：Windows 单文件 EXE 构建
- `tools/configure-wrcg.ps1`：WRCG 遥测配置助手
