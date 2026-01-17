# DeskControl

DeskControl 让手机变成外接显示器应用的触控板与键盘。它面向 Android 11+，
通过无障碍服务渲染光标并注入输入。

[English](README.md)

## 亮点

- 将任意已安装应用启动到有线外接显示器。
- 用手机触控板控制外接应用（移动、点击、拖拽）。
- 外接显示器光标覆盖层，支持自动隐藏与调节。
- 外接显示器断开时能干净结束会话。

## 运行要求

- Android 11+（minSdk 30）。
- 有线 Type-C 外接显示器。
- 开启无障碍服务（光标与输入注入必需）。

## 快速开始

1. 连接有线外接显示器。
2. 打开 DeskControl，并按提示启用无障碍服务。
3. 选择一个应用启动到外接显示器。
4. 打开触控板页面进行控制。

## 触控板用法

- 移动：在触控板区域单指滑动。
- 点击：在触控板区域单击。
- 滑动与拖拽：双击后滑动（震动提示）。
- 自动调暗：在触控板区域内停留 10 秒后降低亮度，点触控板区域外或离开本页会恢复。
- 返回：触控板区域激活时会把返回键转发给外接应用。
- 退出：点左上角返回箭头，或先点触控板区域外再按返回键。

## 构建

```bash
./gradlew assembleDebug
```

安装 APK：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 设置项

- 光标大小、不透明度、颜色、自动隐藏时间。
- 触控板灵敏度、加速度、抖动、平滑、滚动步长。
- 使用触控板时保持屏幕常亮。
- 触控板 10 秒后自动调暗（仅窗口亮度）。

## 项目结构

- `DisplaySessionManager`：外接显示器检测与选择。
- `AppLauncher`：应用启动与失败原因处理。
- `TouchpadActivity`：触控板界面与输入逻辑。
- `ControlAccessibilityService`：光标覆盖与手势/文本注入。
- `CursorOverlayView`：光标渲染与动画。
- `DiagnosticsActivity`：状态与近期失败信息。

## 权限与说明

- 使用无障碍服务进行手势注入和 `ACTION_SET_TEXT`。
- 光标覆盖层为 `TYPE_ACCESSIBILITY_OVERLAY` 且不可触摸。
- 覆盖层通过 `createWindowContext` 挂载到外接显示器。

## 限制

- 仅支持 Android 11+。
- 需要设备支持副屏启动活动。
- 某些应用不支持在副屏启动。

## 许可证

见 `LICENSE`。
