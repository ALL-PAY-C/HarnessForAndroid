# ==========================================================================
# R8 / 资源收缩的 keep 规则（T9.28）
# ==========================================================================
# 这套构建的稳定性依赖「名字在运行期原样可见」，所以这里不是常规意义上
# 的「防混淆漏网」，而是把三处动态查找面显式锁死：
#
# 1. 外部插件（APK / dex）按字符串名装载，且它们的类实现了 plugin-api 的
#    接口。宿主一旦改名，对方 dex 里的符号就解析不到（NoClassDefFoundError
#    / ClassCastException），且只在装机后装插件时才暴露。
# 2. 插件宿主自己也是被 plugin-html / 第三方插件直接引用的门面。
# 3. Rhino 解释执行 JS，靠反射访问自己的类；字段/方法名一改就崩。
#
# 资源侧（shrinkResources）：所有字符串都在代码里以 R.string 常量引用，
# 没有 getIdentifier 动态查找，所以资源收缩是安全的；JS / 模板 / 图标走的是
# Java resources（src/main/resources）与 assets，不受 Android 资源收缩影响。

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,AnnotationDefault

# ---- 1. 插件契约：外部 dex 按名字解析，绝不能改名 -------------------------
-keep class dev.dsh.mobile.pluginapi.** { *; }
-keep class dev.dsh.mobile.pluginhost.** { *; }

# 插件入口按 Class.forName(entryClass, true, loader) 装载后强转
-keepclassmembers class * implements dev.dsh.mobile.pluginapi.DshPlugin {
    public <init>();
}

# ---- 2. 宿主里按字符名反射的成员 ------------------------------------------
# HtmlPluginSelfCheck 用 getDeclaredField("BRIDGE") 自检 WebView 桥
-keepclassmembers class dev.dsh.mobile.plugin.html.HtmlDocument { <fields>; }
# WebView 的 @JavascriptInterface 默认规则已覆盖，这里补一层确保不被 merge 掉
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- 3. AIDL / Binder：跨进程按接口名绑定 ----------------------------------
-keep class dev.dsh.mobile.app.node.** { *; }
-keep interface dev.dsh.mobile.app.node.** { *; }
-keep class * extends android.os.Binder { *; }
-keep class **$Stub { *; }

# ---- 4. Rhino：解释器靠反射访问自身类型 ------------------------------------
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# ---- 5. kotlinx.serialization ----------------------------------------------
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.dsh.mobile.**$$serializer { *; }
-keepclassmembers class dev.dsh.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class dev.dsh.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- 6. Room ---------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---- 7. 第三方可选依赖的告警（不打包，只是编译期可见） ---------------------
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

# ---- 8. Kotlin ------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
