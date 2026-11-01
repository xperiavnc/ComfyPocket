# Gson reads several API payloads reflectively. Keep model fields and their
# serialized names while allowing unused implementation code to be removed.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations,AnnotationDefault,Signature
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Room generates implementation classes and schema metadata at compile time.
-keep class com.comfyui.mobile.data.** { *; }

# Retrofit/OkHttp resolve service interfaces and converter metadata reflectively.
-keep interface com.comfyui.mobile.network.** { *; }
