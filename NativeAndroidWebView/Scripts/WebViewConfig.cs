using UnityEngine;

namespace NativeAndroidWebView
{
    /// <summary>
    /// WebView 桥的可配置项（运行时也可用，因为 WebViewLauncher 会读取它）。
    /// 由编辑器向导（Tools / Native Web View / Setup）创建，
    /// 也可在 Project 里右键 Create > Native Web View > Config 手动建。
    /// </summary>
    [CreateAssetMenu(fileName = "WebViewConfig", menuName = "Native Web View/Config", order = 0)]
    public class WebViewConfig : ScriptableObject
    {
        [Tooltip("WebView Activity 完整类名，需与 aar/源码内 package 一致。已固定，一般不用改")]
        public string activityFullName = "com.sgmn.nativewebview.WebViewActivity";

        [Tooltip("未传入 url 时的兜底地址（仅开发调试用，真正使用请由 OpenUrl 传入）")]
        public string defaultUrl = "https://example.com";
    }
}
