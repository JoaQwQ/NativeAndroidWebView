using UnityEngine;
using UnityEditor;
using System.IO;
using System.Xml;
using System.Xml.Linq;
using System.Text;
using System.Linq;

namespace NativeAndroidWebView.Editor
{
    /// <summary>
    /// 一键配置向导：建 WebViewConfig 资产 + 以「合并」方式补齐主 AndroidManifest 的必需项
    /// （INTERNET 权限、usesCleartextTraffic、UnityPlayerActivity 入口、WebViewActivity 注册），
    /// 不覆盖目标工程已有的 manifest 内容，便于导入其他工程后点几下即可用。
    /// 另提供“导出 .unitypackage”按钮，方便把整套插件发给其他工程。
    /// </summary>
    public class WebViewConfigWindow : EditorWindow
    {
        private const string MainManifestPath = "Assets/Plugins/Android/AndroidManifest.xml";

        // 路径不再写死：移动 NativeAndroidWebView 文件夹后，Setup 也能按实际位置找到/创建资产、导出包。
        // 关键：用"本编辑器脚本自身在磁盘上的真实路径"反推包根（MonoScript.FromScriptableObject(this)），
        // 不依赖 FindAssets 索引——FindAssets 在"导入 .unitypackage + 移动文件夹"场景下经常返回空，不可靠。
        private string ResolvePackageFolder()
        {
            var ms = MonoScript.FromScriptableObject(this);
            string scriptPath = AssetDatabase.GetAssetPath(ms); // 如 Assets/X/Y/NativeAndroidWebView/Editor/WebViewConfigWindow.cs
            if (string.IsNullOrEmpty(scriptPath)) return "Assets/NativeAndroidWebView";
            string dir = Path.GetDirectoryName(scriptPath).Replace('\\', '/');
            if (dir.EndsWith("/Editor"))
                dir = dir.Substring(0, dir.Length - "/Editor".Length);
            return string.IsNullOrEmpty(dir) ? "Assets/NativeAndroidWebView" : dir;
        }

        private string ResolveConfigPath()
        {
            return ResolvePackageFolder() + "/WebViewConfig.asset";
        }

        private static readonly XNamespace AndroidNs = "http://schemas.android.com/apk/res/android";
        private static readonly XNamespace ToolsNs = "http://schemas.android.com/tools";

        private bool needCleartext = true;

        [MenuItem("Tools/Native Web View/Setup")]
        private static void Open()
        {
            var w = GetWindow<WebViewConfigWindow>();
            w.titleContent = new GUIContent("Native Web View Setup");
            w.Show();
        }

        private void OnGUI()
        {
            GUILayout.Label("Native Web View 一键配置", EditorStyles.boldLabel);
            EditorGUILayout.HelpBox(
                "本向导以「合并」方式配置主 AndroidManifest（只补必需项，不覆盖你已有的内容）：\n" +
                "1. 创建 WebViewConfig 资产（可改默认 URL）\n" +
                "2. 补 INTERNET 权限 / usesCleartextTraffic（仅当缺失时）\n" +
                "3. 注册 com.sgmn.nativewebview.WebViewActivity（仅当缺失时）\n" +
                "4. 补 UnityPlayerActivity(LAUNCHER) 入口（仅当缺失时，兼容自定义 launcher）",
                MessageType.Info);

            needCleartext = EditorGUILayout.ToggleLeft("需要访问 http（内网明文流量）", needCleartext);

            if (GUILayout.Button("Setup（执行上述配置）", GUILayout.Height(32)))
            {
                CreateConfig();
                PatchMainManifest(needCleartext);
                AssetDatabase.Refresh();
                EditorUtility.DisplayDialog("完成",
                    "配置已写入。\n接下来：场景挂 WebViewLauncher，调用 OpenUrl(\"https://...;\") 即可。", "OK");
            }

            EditorGUILayout.Space();
        }

        private void CreateConfig()
        {
            string configPath = ResolveConfigPath();
            var existing = AssetDatabase.LoadAssetAtPath<WebViewConfig>(configPath);
            if (existing != null) return;
            var cfg = CreateInstance<WebViewConfig>();
            Directory.CreateDirectory(Path.GetDirectoryName(configPath));
            AssetDatabase.CreateAsset(cfg, configPath);
            AssetDatabase.SaveAssets();
            Debug.Log("[NativeWebView] 已创建 " + configPath);
        }

        private static void PatchMainManifest(bool needCleartext)
        {
            // 智能合并（而非整体覆盖）：只补“缺失的必需项”，保留目标工程主 manifest 的原有内容
            // （package、已有 activity、权限、application 属性等一律不动），从而保证导入别的工程后
            // 点 Setup 不会冲掉人家已有的 manifest。
            //
            // 关键坑（已踩）：Android 的 AndroidManifest.xml 里【元素本身不带命名空间】，
            // 只有属性（android:name 等）才在 android 命名空间。因此查找/新增“元素”要用“无命名空间”的名字
            // （root.Element("application")），只有“属性”才用 a + "name"。之前误把元素也写成 a + "xxx"，
            // 会生成 <android:application> 这种错误元素名（带 android: 前缀），manifest merger 无法识别 → 打包报错。
            // 另加一段“自愈”：凡落在 android 命名空间下的元素（历史误写产物）一律删除，保证重复点 Setup 也能修复。
            var a = AndroidNs;
            XDocument doc = File.Exists(MainManifestPath) ? SafeLoad(MainManifestPath) : null;

            if (doc == null || doc.Root == null)
            {
                // 主 manifest 不存在或已损坏：用干净模板整体创建（首次 Setup）。
                Directory.CreateDirectory(Path.GetDirectoryName(MainManifestPath));
                File.WriteAllText(MainManifestPath, TemplateManifest());
                AssetDatabase.ImportAsset(MainManifestPath);
                Debug.Log("[NativeWebView] 主 manifest 不存在/损坏，已用模板创建：" + MainManifestPath);
                return;
            }

            var root = doc.Root;

            // 自愈：删除任何落在 android 命名空间下的元素（历史误写产生的 <android:xxx> 污染块）。
            // 正常 AndroidManifest 里没有任何元素属于 android 命名空间（只有属性属于），因此删这些必定安全。
            root.Descendants()
                .Where(e => e.Name.Namespace == a)
                .ToList()
                .ForEach(e => e.Remove());

            // 确保根节点持有 android 命名空间声明（缺少时补上，属性才能用 android: 前缀）。
            if (root.GetNamespaceOfPrefix("android") == null)
                root.Add(new XAttribute(XNamespace.Xmlns + "android", a.NamespaceName));

            // 1) INTERNET 权限（无则补，不删不改已有）
            bool hasInternet = root.Elements("uses-permission")
                .Any(e => (string)e.Attribute(a + "name") == "android.permission.INTERNET");
            if (!hasInternet)
            {
                root.Add(new XElement("uses-permission",
                    new XAttribute(a + "name", "android.permission.INTERNET")));
                Debug.Log("[NativeWebView] 已补 INTERNET 权限");
            }

            // 2) 麦克风 / 相机权限（无则补，不删不改已有）
            //    供 WebView 内网页通过 getUserMedia 采集音视频；不确保则新工程导入 / 重跑
            //    Setup 时 manifest 不会自动带上，导致 WebView 拿不到麦克风 / 相机。
            EnsurePermission(root, a, "android.permission.RECORD_AUDIO", "RECORD_AUDIO");
            EnsurePermission(root, a, "android.permission.MODIFY_AUDIO_SETTINGS", "MODIFY_AUDIO_SETTINGS");
            EnsurePermission(root, a, "android.permission.CAMERA", "CAMERA");

            // 3) application 节点（无则建）
            var app = root.Element("application");
            if (app == null)
            {
                app = new XElement("application");
                root.Add(app);
            }

            // 4) usesCleartextTraffic（按勾选：勾选则确保为 true；不勾选则移除）
            var clearAttr = app.Attribute(a + "usesCleartextTraffic");
            if (needCleartext)
            {
                if (clearAttr == null)
                    app.SetAttributeValue(a + "usesCleartextTraffic", "true");
                // 已存在则保留原值，避免覆盖引发 merger 属性冲突
            }
            else if (clearAttr != null)
            {
                clearAttr.Remove();
            }

            // 5) UnityPlayerActivity（LAUNCHER 入口）：缺失才补，兼容本工程/自定义 launcher
            bool hasUnity = app.Elements("activity")
                .Any(e => (string)e.Attribute(a + "name") == "com.unity3d.player.UnityPlayerActivity");
            if (!hasUnity)
            {
                app.Add(MakeUnityPlayerActivity(a));
                Debug.Log("[NativeWebView] 已补 UnityPlayerActivity(LAUNCHER) 入口");
            }

            // 6) WebViewActivity（本插件提供）：缺失才注册
            bool hasWeb = app.Elements("activity")
                .Any(e => (string)e.Attribute(a + "name") == "com.sgmn.nativewebview.WebViewActivity");
            if (!hasWeb)
            {
                app.Add(MakeWebViewActivity(a));
                Debug.Log("[NativeWebView] 已注册 com.sgmn.nativewebview.WebViewActivity");
            }

            doc.Save(MainManifestPath);
            AssetDatabase.ImportAsset(MainManifestPath);
            Debug.Log("[NativeWebView] 已合并写入主 manifest（保留原有内容）：" + MainManifestPath);
        }

        private static XDocument SafeLoad(string path)
        {
            try { return XDocument.Load(path); }
            catch (XmlException) { return null; }
        }

        // 确保 manifest 含有指定权限；缺失才补，绝不删除已有项（与整体“合并不覆盖”策略一致）。
        private static void EnsurePermission(XElement root, XNamespace a, string name, string label)
        {
            bool has = root.Elements("uses-permission")
                .Any(e => (string)e.Attribute(a + "name") == name);
            if (!has)
            {
                root.Add(new XElement("uses-permission",
                    new XAttribute(a + "name", name)));
                Debug.Log("[NativeWebView] 已补 " + label + " 权限");
            }
        }

        private static XElement MakeWebViewActivity(XNamespace a)
        {
            return new XElement("activity",
                new XAttribute(a + "name", "com.sgmn.nativewebview.WebViewActivity"),
                new XAttribute(a + "exported", "true"),
                new XAttribute(a + "hardwareAccelerated", "true"),
                new XAttribute(a + "theme", "@android:style/Theme.NoTitleBar.Fullscreen"));
        }

        private static XElement MakeUnityPlayerActivity(XNamespace a)
        {
            return new XElement("activity",
                new XAttribute(a + "name", "com.unity3d.player.UnityPlayerActivity"),
                new XAttribute(a + "theme", "@style/UnityThemeSelector"),
                new XAttribute(a + "exported", "true"),
                new XAttribute(a + "screenOrientation", "fullSensor"),
                new XElement("intent-filter",
                    new XElement("action", new XAttribute(a + "name", "android.intent.action.MAIN")),
                    new XElement("category", new XAttribute(a + "name", "android.intent.category.LAUNCHER"))),
                new XElement("meta-data",
                    new XAttribute(a + "name", "unityplayer.UnityActivity"),
                    new XAttribute(a + "value", "true")));
        }

        private static string TemplateManifest()
        {
            return
@"<?xml version=""1.0"" encoding=""utf-8""?>
<manifest xmlns:android=""http://schemas.android.com/apk/res/android"">
  <uses-permission android:name=""android.permission.INTERNET"" />
  <uses-permission android:name=""android.permission.RECORD_AUDIO"" />
  <uses-permission android:name=""android.permission.MODIFY_AUDIO_SETTINGS"" />
  <uses-permission android:name=""android.permission.CAMERA"" />
  <application android:usesCleartextTraffic=""true"">
    <activity
        android:name=""com.unity3d.player.UnityPlayerActivity""
        android:theme=""@style/UnityThemeSelector""
        android:exported=""true""
        android:screenOrientation=""fullSensor"">
      <intent-filter>
        <action android:name=""android.intent.action.MAIN"" />
        <category android:name=""android.intent.category.LAUNCHER"" />
      </intent-filter>
      <meta-data
          android:name=""unityplayer.UnityActivity""
          android:value=""true"" />
    </activity>
    <activity
        android:name=""com.sgmn.nativewebview.WebViewActivity""
        android:exported=""true""
        android:hardwareAccelerated=""true""
        android:theme=""@android:style/Theme.NoTitleBar.Fullscreen"" />
  </application>
</manifest>";
        }
    }
}
