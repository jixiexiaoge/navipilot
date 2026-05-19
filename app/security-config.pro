# ===========================================
# 额外安全配置（与 proguard-rules.pro 配合使用）
# ===========================================

# 重新打包混淆后的类到统一子包（增加反编译难度）
# 禁止使用空包名 ''：与 Kotlin/Compose 生成代码组合时，ART 曾校验失败（VerifyError：寄存器与签名不匹配）
-repackageclasses 'com.example.navipilot.obf'

# Release 版本移除所有 Android Log 输出
# 注意：Timber 在 release 中不应依赖 android.util.Log
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
