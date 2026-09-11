#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#define TAG "AnimeEngine"

extern "C"
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "Native engine loaded"
    );
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_gptvideo2anime_pipeline_MediaCodecVideoEngine_nativeProcessFrame(
        JNIEnv* env,
        jobject,
        jobject bitmap) {

    return bitmap;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_gptvideo2anime_pipeline_MediaCodecVideoEngine_nativeBlendFrames(
        JNIEnv* env,
        jobject,
        jobject original,
        jobject anime,
        jfloat strength) {

    return anime;
}
