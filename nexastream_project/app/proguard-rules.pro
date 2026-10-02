# NexaStream ProGuard & R8 Keep Rules

# --------------------------------------------------------------------------
# Google Cast Framework
# --------------------------------------------------------------------------
-keep class com.nexastream.app.cast.CastOptionsProvider { *; }
-keep class * extends com.google.android.gms.cast.framework.OptionsProvider { *; }

# --------------------------------------------------------------------------
# Room Database
# --------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class com.nexastream.app.database.** { *; }
-keep class * extends androidx.room.Entity { *; }

# --------------------------------------------------------------------------
# App Models, DTOs & Serialization
# --------------------------------------------------------------------------
-keep class com.nexastream.app.models.** { *; }
-keepclassmembers class com.nexastream.app.models.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations

# Gson Keep Rules
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# --------------------------------------------------------------------------
# Rhino JS Scraper Engine & Jsoup Reflection
# --------------------------------------------------------------------------
-keep class org.mozilla.rhino.** { *; }
-keep interface org.mozilla.rhino.** { *; }
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-keep class org.jsoup.** { *; }

# --------------------------------------------------------------------------
# Glide
# --------------------------------------------------------------------------
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
-keepclassmembers class * implements com.bumptech.glide.module.GLideModule {
    public <init>(...);
}

# --------------------------------------------------------------------------
# ExoPlayer / Media3
# --------------------------------------------------------------------------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --------------------------------------------------------------------------
# Retrofit & OkHttp
# --------------------------------------------------------------------------
-keepattributes Signature, ElementType, ElementType.*
-keepclassmembers enum * { *; }
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# --------------------------------------------------------------------------
# Android Leanback & TV Components
# --------------------------------------------------------------------------
-keep class androidx.leanback.** { *; }
