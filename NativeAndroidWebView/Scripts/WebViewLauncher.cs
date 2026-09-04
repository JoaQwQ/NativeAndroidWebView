using UnityEngine;
using NativeAndroidWebView;

/// <summary>
/// 从 Unity 启动原生 WebView Activity（原生 Android WebView 桥）。
/// 前置：WebViewDemo 安卓源码插件已随包放入 Assets/Plugins/Android/，由 Unity 构建时自动编译。
/// Activity 全名与兜底 URL 来自 WebViewConfig（ScriptableObject），不写死在代码里。
/// </summary>
public class WebViewLauncher : MonoBehaviour
{
    [Tooltip("可选：挂 WebViewConfig 资产，运行时读取 Activity 名与兜底 URL；不挂则用下方默认值")]
    public WebViewConfig config;

    // 固定命名空间（与 aar/源码内 package 一致），作为不挂 config 时的兜底默认值
    private const string DEFAULT_ACTIVITY = "com.sgmn.nativewebview.WebViewActivity";
    private const string FALLBACK_URL = "https://example.com";

    /// <summary>
    /// 打开指定网址。Editor 下用系统浏览器兜底。
    /// </summary>
    public void OpenUrl(string url)
    {
        string activity = config != null ? config.activityFullName : DEFAULT_ACTIVITY;
        string target = string.IsNullOrEmpty(url)
            ? (config != null ? config.defaultUrl : FALLBACK_URL)
            : url;

#if UNITY_ANDROID && !UNITY_EDITOR
        AndroidJavaClass unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
        AndroidJavaObject activityObj = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
        AndroidJavaClass webViewClass = new AndroidJavaClass(activity);
        AndroidJavaObject intent = new AndroidJavaObject("android.content.Intent", activityObj, webViewClass);
        intent.Call<AndroidJavaObject>("putExtra", "url", target);
        activityObj.Call("startActivity", intent);
#else
        Application.OpenURL(target);
#endif
    }
}
