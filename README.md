# Hot Corners for Android

原生 Kotlin 验证 Demo：通过 `AccessibilityService + TYPE_ACCESSIBILITY_OVERLAY` 为屏幕四角配置鼠标触发动作。

## 功能

- 使用四角取景符号的简约自适应启动器图标，支持 Android 13+ 单色图标。
- 设置页采用 Material 3 Expressive 的动态色彩、明暗主题、圆润分组容器、清晰字号层级和轻量触控反馈；Android 12+ 会跟随系统壁纸色彩。
- 四个角可以分别设置为：关闭、返回、桌面、多任务、通知栏、快捷设置、电源菜单、锁屏、截图或分屏。
- 默认设置保持已验证的行为：左上角打开多任务，其余角关闭。
- 鼠标在已启用角落停留约 300 ms 后触发；鼠标离开后重新武装，同一次停留只触发一次。
- 仅处理 `SOURCE_MOUSE` 悬停事件；触屏和触控笔不会执行动作。
- 只为已配置动作的角落创建透明 24 dp 区域。未配置动作的角落不会创建窗口。
- 设备旋转或屏幕配置变化时，服务会重新定位角落窗口。
- Android 9（API 28）及以上提供锁屏和截图；分屏仅在 Android 11（API 30）及以上且系统当前提供该动作时显示。
- 服务不读取当前应用的无障碍窗口树，也不请求手势或触摸探索控制。

启用的透明悬停窗口需要成为鼠标指针的事件目标，因此触摸屏在已启用角落的 24 dp 区域内点按时，点按可能由窗口接收；这些点按不会执行角落动作。

设置保存在应用私有的 `SharedPreferences` 中。已有安装的总开关键保持不变，原左上角多任务行为作为默认设置保留。

## 构建

项目使用 Android Gradle Plugin 9.4.0、Gradle Wrapper 9.6.0、Android SDK Platform 35 和 Build Tools 36.0.0，最低 Android 版本为 API 26。Kotlin 由 AGP 内置支持，不需要额外 Kotlin Gradle 插件或第三方运行时依赖。

用 Android Studio 打开此目录，或在终端确保 JDK 与 Android SDK 已配置后运行：

```sh
./gradlew :app:assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

命令行构建示例（按本机 Android Studio 与 SDK 安装位置调整路径）：

```sh
export JAVA_HOME="/path/to/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="/path/to/Android/SDK"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:assembleDebug
```

## 安装并开启无障碍服务

1. 在 Android Studio 中运行，或将 `app/build/outputs/apk/debug/app-debug.apk` 安装到设备。命令行安装示例：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。
2. 打开 **Hot Corners for Android**，点击 **打开无障碍设置**。
3. 在无障碍设置的 **已安装的应用**（不同系统也可能叫“已下载的应用”）中找到 **Hot Corners for Android** 并开启服务。
4. 返回应用，确认 **启用触发角** 开关已开启。
5. 点按四个角落卡片，为每个角选择动作。默认左上角是 **多任务**，其他角为 **关闭**。
6. 连接鼠标，将指针移到已配置的角落并停留约 300 ms。鼠标离开后可以再次触发。

关闭全部触发角：在应用内关闭 **启用触发角**，或在 Android 无障碍设置中关闭服务。
