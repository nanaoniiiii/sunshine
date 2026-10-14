-keepclassmembers class * {
    native <methods>;
}

-keep class com.alkaidlab.sunshine.engine.** { *; }
-keep class com.alkaidlab.sunshine.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }
-dontwarn **

# Retrofit 2.9 suspend return types must retain generic endpoints in R8 full mode.
# Keeping Signature alone is insufficient when these endpoint classes are removed.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class retrofit2.Response { *; }
-keep interface retrofit2.Call { *; }
-keep interface kotlin.coroutines.Continuation { *; }
