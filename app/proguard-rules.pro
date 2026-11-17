# 模块入口必须保留：LSPosed 通过反射实例化，混淆掉名字就等于没装
-keep class com.dafeiyu.paiguard.GuardEntry { *; }

# Xposed 会在宿主里调用这些 hook 回调
-keepclassmembers class * extends de.robv.android.xposed.XC_MethodHook {
    protected void beforeHookedMethod(de.robv.android.xposed.XC_MethodHook$MethodHookParam);
    protected void afterHookedMethod(de.robv.android.xposed.XC_MethodHook$MethodHookParam);
}

# 纯逻辑层保留符号，方便线上日志定位
-keep class com.dafeiyu.paiguard.core.LatexScanner { *; }
-keep class com.dafeiyu.paiguard.core.LatexSanitizer { *; }
