# Rules for the release build (R8). Most come with the libraries; these cover the app's own seams.

# score.js calls the GrullaHost bridge by method name (also covered by the default rules; kept explicit).
-keepclassmembers class com.grulla.music.score.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Stored JSON (library, practice log) is read with kotlinx.serialization: keep the generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.grulla.music.** {
    *** Companion;
    *** serializer(...);
}
