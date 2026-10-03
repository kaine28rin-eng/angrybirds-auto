# Add project-specific ProGuard rules here.
# This file was created for the Angry Birds Auto app.
#
# Keep OpenCV native library references
-keep class org.opencv.** { *; }
-keep class org.opencv.android.Utils { *; }
-keep class org.opencv.core.** { *; }
-keep class org.opencv.imgproc.** { *; }
-keep class org.opencv.imgcodecs.** { *; }

# Keep data classes used for JSON serialization
-keepclassmembers class com.stevestudy.angrybirdsauto.data.** {
    public *;
}

# Keep enum values
-keepclassmembers enum com.stevestudy.angrybirdsauto.** {
    public *;
}
