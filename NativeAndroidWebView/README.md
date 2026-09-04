# NativeAndroidWebView（原生 Android WebView 桥）

把「原生 Android Activity 承载 WebView」做成可复用的 Unity 插件。导入任意工程后，
只需点几下编辑器菜单即可配置使用，**无需 Android SDK、无需手动出 AAR**。

## 包含内容
- `Plugins/Android/com/sgmn/nativewebview/WebViewActivity.java`：原生 Activity 承载 WebView。
  作为**扁平插件**直接放在 `Plugins/Android/` 下，Unity 会自动编译进 `unityLibrary`（包名固定 `com.sgmn.nativewebview`）。
  不依赖独立 `build.gradle` / `compileSdk` 设置，规避 build-tools 授权与 IL2CPP 下 library manifest 合并失败的问题。
- `Scripts/WebViewLauncher.cs`：C# 启动器，调用 `OpenUrl(url)` 即可。
- `Scripts/WebViewConfig.cs`：运行时配置资产（ScriptableObject）。
- `Editor/WebViewConfigWindow.cs`：一键配置向导（仅编辑器）。

## 在其他工程使用（3 步）
1. **导入**：双击导出的 `NativeAndroidWebView.unitypackage`（Assets > Import Package），或把整个
   `NativeAndroidWebView/` 文件夹拷进目标工程 `Assets/`。
2. **配置**：菜单 `Tools / Native Web View / Setup`
   - 自动创建 `WebViewConfig.asset`（可改默认 URL）
   - 自动在主 `Assets/Plugins/Android/AndroidManifest.xml` 注册 `com.sgmn.nativewebview.WebViewActivity`
     （点击弹 WebView 必需）、加 `usesCleartextTraffic`（访问 `http://` 内网必需；纯 `https` 可取消勾选）
3. **调用**：场景挂 `WebViewLauncher` 组件，调用 `OpenUrl("https://你的地址")`。

## 说明
- Activity 名固定 `com.sgmn.nativewebview.WebViewActivity`，**不必等于 App 包名**，跨工程通用。
- 真正加载的地址由 `OpenUrl(url)` 的入参决定；仅当未传 url 时才用 `WebViewConfig.defaultUrl` 兜底。
- 根目录独立的 `WebViewDemo/`（Android Studio 工程）仅作参考，本包不再依赖它。
- 采用扁平插件（不再用 `.androidlib` 库模块）：Activity 声明统一写进主 `AndroidManifest.xml`，确保 IL2CPP 包也能正确注册，
  避免「类编进 APK 但 manifest 没注册 → 点击报 ActivityNotFoundException」的问题。
