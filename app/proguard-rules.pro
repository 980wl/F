# 保持 गो/gomobile JNI 桥接类与 libbox 接口不被 R8 混淆或裁剪
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
-keep interface io.nekohasekai.libbox.** { *; }

# 保持实现 libbox 接口的平台桥接与服务回调方法
-keep class com.fproxy.client.PlatformBridge { *; }
-keep class com.fproxy.client.FVpnService { *; }
-keep class com.fproxy.client.ProbeService { *; }

# 移除正式版中的 Log.d / Log.v 调试日志调用
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
