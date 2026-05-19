# ===========================================
# 额外安全配置（与 proguard-rules.pro 配合使用）
# ===========================================

# 重新打包混淆后的类到统一子包（增加反编译难度）
# 禁止使用空包名 ''：与 Kotlin/Compose 生成代码组合时，ART 曾校验失败（VerifyError：寄存器与签名不匹配）
-repackageclasses 'com.example.navipilot.obf'

# 强制不同原始成员使用不同混淆名（避免类合并后冲突，提高反编译难度）
-useuniqueclassmembernames

# 激进重载：不同方法可混淆为相同名称（仅参数不同），使反编译输出更难阅读
-overloadaggressively

# 混淆时将类名和方法名映射为极短标识符（a/b/c），减小 DEX 体积
# 注：-repackageclasses 已启用因此 -flattenpackagehierarchy 不再需要

# Release 版本移除所有 Android Log 输出
# 注意：Timber 在 release 中不应依赖 android.util.Log
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# 移除所有 System.out / System.err 输出（减少字符串常量池中的敏感信息）
-assumenosideeffects class java.io.PrintStream {
    public void println(%);
    public void println(**);
    public void print(%);
    public void print(**);
}

# 移除 printStackTrace（避免堆栈路径泄露代码结构）
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}
