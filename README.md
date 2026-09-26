# Hot Corners for Android

原生 Kotlin 验证 Demo：通过 `AccessibilityService + TYPE_ACCESSIBILITY_OVERLAY` 在屏幕四角创建透明的 24 dp 鼠标悬停区域。当前只有左上角配置了动作：鼠标进入后停留约 300 ms，执行 Android「最近任务」。

## 行为

- 仅处理 `SOURCE_MOUSE` 的悬停事件；触屏和触控笔不会触发最近任务。
- 鼠标离开左上角区域时取消等待并重新武装。
- 动作执行前立即锁定，鼠标仍留在区域内时不会重复触发。
- 右上、左下、右下目前是透明占位区域。
- 设备旋转或屏幕配置变化时，服务会按屏幕重建四角窗口。
- 服务不读取当前应用的无障碍窗口树，也不请求手势或触摸探索控制。

透明悬停窗口必须成为鼠标指针的事件目标，因此服务开启时，触摸屏在四角 24 dp 区域内的点按可能被窗口接收；这些点按不会执行最近任务动作。

## 构建

项目使用 Android Gradle Plugin 9.4.0、Gradle Wrapper 9.6.0、Android SDK Platform 35 和 Build Tools 36.0.0，最低 Android 版本为 API 26。Kotlin 由 AGP 内置支持，不需要额外 Kotlin Gradle 插件。

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
3. 在无障碍设置的 **已安装的应用**（不同系统也可能叫“已下载的应用”）中选择 **Hot Corners for Android** 并开启服务。
4. 返回应用，保持 **启用左上角触发角** 开启。
5. 连接鼠标，将指针移到屏幕左上角并停留约 300 ms，即可打开最近任务；把鼠标移出左上角后可以再次触发。

关闭功能：在应用内关闭开关，或在 Android 无障碍设置中关闭该服务。
