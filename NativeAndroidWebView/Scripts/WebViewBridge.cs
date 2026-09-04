using UnityEngine;

namespace NativeAndroidWebView
{
    /// <summary>
    /// 原生 Android WebView 桥的静态门面（AOT 侧，NativeAndroidWebView 程序集）。
    ///
    /// 存在的两个原因：
    /// 1. 热更程序集（GameLogic）无法引用 Assembly-CSharp，只能访问带 asmdef 的 NativeAndroidWebView 程序集；
    /// 2. 避免热更侧为了拿 WebViewLauncher（MonoBehaviour）实例去做 FindObjectOfType / 场景挂载依赖。
    ///
    /// 用法（热更侧）：WebViewBridge.OpenUrl(url);
    /// 注意：本类会被热更 DLL 调用，AOT 构建时 GameLogic.dll 尚不存在，
    /// 因此必须在 Assets/NativeAndroidWebView/link.xml 里 preserve，否则 IL2CPP 剥离后运行时报 TypeLoadException。
    /// </summary>
    public static class WebViewBridge
    {
        private const string DEFAULT_ACTIVITY = "com.sgmn.nativewebview.WebViewActivity";
        private const string FALLBACK_URL = "https://example.com";

        /// <summary>
        /// WebView Activity 完整类名，需与 aar/源码内 package 一致。一般不用改。
        /// </summary>
        public static string ActivityFullName = DEFAULT_ACTIVITY;

        /// <summary>
        /// 打开指定网址。Android 真机启动原生 Activity；其他平台/Editor 用系统浏览器兜底。
        /// </summary>
        public static void OpenUrl(string url)
        {
            string target = string.IsNullOrEmpty(url) ? FALLBACK_URL : url;

#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                using (var activityObj = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
                using (var webViewClass = new AndroidJavaClass(ActivityFullName))
                using (var intent = new AndroidJavaObject("android.content.Intent", activityObj, webViewClass))
                {
                    intent.Call<AndroidJavaObject>("putExtra", "url", target);
                    activityObj.Call("startActivity", intent);
                }
            }
            catch (System.Exception ex)
            {
                Debug.LogError($"[WebViewBridge] 启动 WebViewActivity 失败: {ex.Message}\nurl={target}");
            }
#else
            Application.OpenURL(target);
#endif
        }
    }
}
