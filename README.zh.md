# DeskControl

> **语言:** 简体中文 · [Русский](README.md) · [English](README.en.md)

DeskControl 让手机变成外接显示器应用的触控板与体感鼠标。它支持 Android 11+，并以 Android 16（API 36）为目标版本，通过无障碍服务渲染光标并注入输入。

## 主要特性

- 将任意已安装应用启动到有线外接显示器。
- 用手机触控板控制外接应用（移动、点击、拖拽、滚动）。
- 支持体感鼠标（陀螺仪控制），具备快速校准与触觉反馈。
- 外接显示器光标覆盖层，支持自动隐藏与调节。
- 手机屏幕黑屏防误触功能（Blackout）。
- 支持将返回键重定向到外接屏幕。
- 支持隐藏触控板界面（全黑屏幕，仅供熟练用户使用）。
- 外接显示器断开时能干净结束会话。

## 运行要求

- Android 11+（minSdk 30）。
- 目标版本为 Android 16（API 36）。
- 有线 Type-C 外接显示器（支持 DisplayPort Alt Mode）。
- 开启无障碍服务（光标与输入注入必需）。

## 快速上手

1. 将有线外接显示器连接到手机。
2. 打开应用，查看无障碍说明并开启 DeskControl 无障碍服务。
3. 选择要启动到外接显示器的应用。
4. 打开触控板或体感鼠标即可控制外接应用。

## 触控板操作

- 移动：在触控板区域单指滑动。
- 点击：在触控板区域轻触一次。
- 拖拽：长按后滑动（震动提示触发）。
- 滚动：双指上下滑动。
- 自动变暗：在触控板区域停留 10 秒后屏幕自动变暗。触摸区域外或退出时恢复亮度。
- 返回：触控板区域激活时，返回按键/手势将转发至外接应用。
- 光标居中：长按音量减键。
- 手机黑屏：长按音量加键（外接显示器正常显示）。
- 退出：点击左上角返回箭头，或点击触控板外区域后再按系统返回。

## 项目构建

同一套源代码支持维护两个分发版本（flavor）：

- `play`: 包名 `com.suspace.deskcontrol`，集成 Google Play Billing、支持者图标，无 Shizuku。
- `direct`: 包名 `com.deskcontrol`，支持可选的 Shizuku 自动授权，无 Play 计费与额外图标资源。

```bash
./gradlew assemblePlayDebug
./gradlew assembleDirectDebug
```

安装 APK：

```bash
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
adb install -r app/build/outputs/apk/direct/debug/app-direct-debug.apk
```

## 设置项

- 光标大小、不透明度、颜色与自动隐藏延迟。
- 触控板灵敏度、加速度、防抖动、平滑度与滚动步长。
- 隐藏触控板界面（全黑屏模式）。
- 体感鼠标设置：水平/垂直范围、平滑度、采样间隔与触觉反馈。
- 控制界面保持屏幕常亮。
- 10 秒后触控板自动变暗。
- 界面语言选择（跟随系统、English、简体中文、Русский）与主题（跟随系统、深色、浅色）。

## 项目结构

- `DisplaySessionManager`: 外接显示器状态跟踪与选择。
- `AppLauncher`: 启动路由与失败诊断。
- `TouchpadActivity`: 触控板界面与输入交互逻辑。
- `RayMouseActivity`: 手机陀螺仪体感鼠标控制界面。
- `ControlAccessibilityService`: 光标悬浮层、手势注入、外接窗口焦点与返回键重定向。
- `CursorOverlayView`: 光标渲染与动画。
- `DiagnosticsActivity`: 运行状态与最近失败日志。

## 权限与说明

- 使用 `AccessibilityService` 进行手势注入、外接窗口焦点管理、返回键重定向及按键校准。
- 光标悬浮层使用 `TYPE_ACCESSIBILITY_OVERLAY` 且不拦截触摸事件。
- 悬浮层通过 `createWindowContext` 绑定至外接显示器。
- 详见 `docs/google-play-release.md` 了解 Play Console 声明检查清单。

## 限制

- 仅支持 Android 11+。
- 需要设备固件支持副屏 Activity 启动。
- 部分应用可能限制在副屏上启动。

## 许可证与版权说明

本项目基于原项目 [DeskControl](https://github.com/exiarepairii/deskcontrol) 开发。

- **原项目版权：** Copyright (C) 2024–2026 [exiarepairii](https://github.com/exiarepairii/deskcontrol)
- **修改与新增功能：** Copyright (C) 2026 [byMr712](https://github.com/byMr712/deskcontrol)（添加完整俄语本地化支持、触控板界面全黑隐藏模式、构建脚本与多语言文档适配）。

本项目遵循 **GNU General Public License v3.0 (GPLv3)** 开源协议。完整协议内容请参阅 [`LICENSE`](LICENSE) 文件。

> 本程序是在希望其有用的前提下发布的，但没有任何担保；甚至没有适销性或特定用途适用性的暗示担保。详情请参阅 GNU 通用公共许可证。
