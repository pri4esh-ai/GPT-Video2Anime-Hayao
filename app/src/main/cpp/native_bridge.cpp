#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <cstdint>

#define LOG_TAG "NativeBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace NativeYuvUtils {
    void convertYUV420ToRGBA(
        const uint8_t* yPlane,
        const uint8_t* uPlane,
        const uint8_t* vPlane,
        int width,
        int height,
        int yRowStride,
        int uvRowStride,
        int uvPixelStride,
        uint8_t* outRgba
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_gptvideo2anime_pipeline_YuvConverter_convertYuvToRgbaNative(
    JNIEnv* env,
    jobject /* this */,
    jobject yBuffer,
    jobject uBuffer,
    jobject vBuffer,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint uvPixelStride,
    jobject outBitmap
) {
    if (!yBuffer || !uBuffer || !vBuffer || !outBitmap) {
        LOGE("Null byte buffer or output bitmap passed to native converter.");
        return;
    }

    auto* yData = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* uData = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* vData = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffer));

    if (!yData || !uData || !vData) {
        LOGE("Failed to resolve direct memory addresses for YUV planes.");
        return;
    }

    void* bitmapPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &bitmapPixels) < 0 || !bitmapPixels) {
        LOGE("Failed to lock target AndroidBitmap memory pixels.");
        return;
    }

    NativeYuvUtils::convertYUV420ToRGBA(
        yData, uData, vData,
        width, height,
        yRowStride, uvRowStride, uvPixelStride,
        static_cast<uint8_t*>(bitmapPixels)
    );

    AndroidBitmap_unlockPixels(env, outBitmap);
}
