#include <jni.h>
#include <android/bitmap.h>
#include <cstdint>
#include <algorithm>

namespace NativeYuvUtils {

inline uint8_t clamp(int val) {
    return static_cast<uint8_t>(std::max(0, std::min(255, val)));
}

/**
 * Robust stride-aware YUV_420_888 to RGBA buffer conversion.
 */
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
) {
    for (int y = 0; y < height; ++y) {
        const uint8_t* pY = yPlane + y * yRowStride;
        const uint8_t* pU = uPlane + (y / 2) * uvRowStride;
        const uint8_t* pV = vPlane + (y / 2) * uvRowStride;
        uint8_t* pOut = outRgba + y * width * 4;

        for (int x = 0; x < width; ++x) {
            int uvIdx = (x / 2) * uvPixelStride;
            int Y = pY[x];
            int U = pU[uvIdx] - 128;
            int V = pV[uvIdx] - 128;

            // Integer bit-shift YUV -> RGB conversion formula
            int R = Y + (1370705 * V >> 20);
            int G = Y - ((337094 * U + 698001 * V) >> 20);
            int B = Y + (1732480 * U >> 20);

            pOut[x * 4 + 0] = clamp(R);
            pOut[x * 4 + 1] = clamp(G);
            pOut[x * 4 + 2] = clamp(B);
            pOut[x * 4 + 3] = 255; // Fully opaque Alpha
        }
    }
}

} // namespace NativeYuvUtils

extern "C"
JNIEXPORT void JNICALL
Java_com_gptvideo2anime_util_NativeYuvUtils_convertYuvToBitmapNative(
        JNIEnv *env,
        jobject,
        jobject yBuffer,
        jobject uBuffer,
        jobject vBuffer,
        jint width,
        jint height,
        jint yRowStride,
        jint uvRowStride,
        jint uvPixelStride,
        jobject bitmap) {

    const uint8_t *yPtr = static_cast<const uint8_t *>(env->GetDirectBufferAddress(yBuffer));
    const uint8_t *uPtr = static_cast<const uint8_t *>(env->GetDirectBufferAddress(uBuffer));
    const uint8_t *vPtr = static_cast<const uint8_t *>(env->GetDirectBufferAddress(vBuffer));

    if (!yPtr || !uPtr || !vPtr) {
        return;
    }

    AndroidBitmapInfo info;
    void *pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        return;
    }

    NativeYuvUtils::convertYUV420ToRGBA(
        yPtr,
        uPtr,
        vPtr,
        width,
        height,
        yRowStride,
        uvRowStride,
        uvPixelStride,
        static_cast<uint8_t *>(pixels)
    );

    AndroidBitmap_unlockPixels(env, bitmap);
}
