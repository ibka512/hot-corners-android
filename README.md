<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_foreground.png" alt="Hot Corners for Android 应用图标" width="160" />
</p>

# Hot Corners for Android

Hot Corners for Android 是一款以 Kotlin 编写的原生 Android 验证应用，用于在屏幕四角配置鼠标悬停动作。应用通过无障碍服务创建透明的角落区域；鼠标指针进入已启用区域并停留约 300 毫秒后，应用执行对应的系统操作。

## 功能

- 分别配置左上、右上、左下和右下角。
- 为角落选择关闭、返回、桌面、多任务、通知栏、快捷设置、电源菜单、锁屏、截图或分屏等系统操作。具体可用操作取决于 Android 版本、设备和系统当前提供的能力。
- 默认启用左上角多任务操作；其余角落默认关闭。
- 每次进入角落最多触发一次；鼠标离开后重新进入，才会再次触发。
- 仅处理鼠标悬停事件，不响应触屏和触控笔输入。
- 屏幕方向或配置变化后，服务会重新定位角落区域。
- 设置页采用 Material 3 Expressive 的视觉风格，包括动态色彩、明暗主题、分组容器和清晰的文字层级。Android 12 及以上版本会使用系统壁纸色彩。
- 启动器图标采用自适应图标格式；Android 13 及以上版本支持系统主题图标所需的单色图层。

## 系统要求

- 最低支持 Android 8.0（API 26）。
- 锁屏和截图操作要求 Android 9（API 28）或更高版本。
- 分屏操作要求 Android 11（API 30）或更高版本，并且系统需向无障碍服务提供该操作。

## 安装与启用

1. 从 [GitHub Releases](https://github.com/ibka512/hot-corners-android/releases) 下载 APK，或按下方说明自行构建。
2. 安装并打开应用。
3. 在设置页点按“管理无障碍服务”，进入系统无障碍设置。
4. 在“已安装的应用”或系统对应列表中找到 **Hot Corners for Android**，然后开启服务。
5. 返回应用，确认“触发角总开关”处于开启状态。
6. 点按四个角落卡片，为各角选择需要执行的操作。
7. 连接鼠标，将指针移至已配置的角落并停留约 300 毫秒。鼠标离开该角后，可以再次触发。

关闭应用内的总开关会暂停全部角落操作；也可以在系统无障碍设置中关闭本服务。

## 无障碍权限与交互说明

本应用需要用户在 Android 系统设置中手动启用无障碍服务，才能在其他应用上接收鼠标悬停事件并执行系统操作。应用不会读取当前应用的无障碍窗口树，也不请求手势控制或触摸探索权限。角落配置保存在应用私有的本地 `SharedPreferences` 中。

为接收鼠标悬停，已启用的角落会创建一个透明的 24 dp 区域。因此，触摸屏在该区域内的点按可能由悬停窗口接收；触屏点按不会执行角落动作。关闭或未配置动作的角落不会创建悬停窗口。

## 构建

项目使用 Android Gradle Plugin 9.4.0、Gradle Wrapper 9.6.0、Android SDK Platform 35 和 Build Tools 36.0.0。Kotlin 由 Android Gradle Plugin 提供；项目未引入第三方运行时依赖。

使用 Android Studio 打开项目，或在已配置 JDK 与 Android SDK 的环境中运行：

```sh
./gradlew :app:assembleDebug
```

构建完成后，APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的设备：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

如需在命令行配置本机工具路径，可使用以下示例并替换为实际安装位置：

```sh
export JAVA_HOME="/path/to/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="/path/to/Android/SDK"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:assembleDebug
```

## 发布包说明

GitHub Releases 提供可安装 APK。当前验证版由 `debug` 构建变体生成，适用于侧载安装与功能验证；该文件不是用于 Google Play 发布的正式签名包。
