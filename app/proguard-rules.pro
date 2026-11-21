# Retain GeckoView API and WebExtension entry points that may be reached through JNI or reflection.
-keepattributes *Annotation*,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keep class org.mozilla.geckoview.** { *; }
-keep interface org.mozilla.geckoview.** { *; }

# Honor explicit AndroidX keep annotations on app-side reflective entry points.
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
