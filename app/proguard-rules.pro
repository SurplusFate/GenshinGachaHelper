# 项目专用混淆规则
#
# 说明：keep 规则必须与实际包结构一致，指向不存在包的无效规则会掩盖真实的混淆问题。

# Gson 反射解析依赖注解与泛型签名，必须保留
-keepattributes Signature,InnerClasses,EnclosingMethod

# 数据模型（Gson 反射解析）
-keep class com.genshin.gachahelper.data.model.** { *; }

# 登录链路（DS 签名 / Cookie 解析走反射调用）
-keep class com.genshin.gachahelper.auth.** { *; }

# 签到链路（WorkManager 反射实例化 Worker）
-keep class com.genshin.gachahelper.signin.** { *; }

# 网络层（Retrofit 接口与响应数据类）
-keep class com.genshin.gachahelper.remote.** { *; }
