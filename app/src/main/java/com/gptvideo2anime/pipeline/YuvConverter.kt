#include <jni.h>
#include <android/bitmap.h>
#include <arm_neon.h>
#include <cstdint>

extern "C"
JNIEXPORT void JNICALL
Java_com_gptvideo2anime_util_NativeYuvUtils_nv12ToBitmapNative(
        JNIEnv *env,
        jobject,
        jbyteArray yuvArray,
        jint width,
        jint height,
        jobject bitmap) {

    jbyte* yuv = env->GetByteArrayElements(yuvArray, nullptr);

    AndroidBitmapInfo info;
    void* pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {

        env->ReleaseByteArrayElements(yuvArray, yuv, JNI_ABORT);
        return;
    }

    const uint8_t* yPlane = reinterpret_cast<uint8_t*>(yuv);
    const uint8_t* uvPlane = yPlane + width * height;
    uint32_t* dst = static_cast<uint32_t*>(pixels);

    for (int y = 0; y < height; y++) {

        int yRow = y * width;
        int uvRow = (y >> 1) * width;

        for (int x = 0; x < width; x += 8) {

            uint8x8_t y8 = vld1_u8(yPlane + yRow + x);

            uint8x8_t uv8 =
                vld1_u8(uvPlane + uvRow + (x & ~1));

            uint8x8_t u =
                vzip_u8(uv8, uv8).val[0];

            uint8x8_t v =
                vzip_u8(uv8, uv8).val[1];

            int16x8_t Y =
                vreinterpretq_s16_u16(
                    vsubl_u8(y8, vdup_n_u8(16)));

            int16x8_t U =
                vreinterpretq_s16_u16(
                    vsubl_u8(u, vdup_n_u8(128)));

            int16x8_t V =
                vreinterpretq_s16_u16(
                    vsubl_u8(v, vdup_n_u8(128)));

            Y = vshrq_n_s16(vmulq_n_s16(Y, 298), 8);

            int16x8_t R =
                vaddq_s16(
                    Y,
                    vshrq_n_s16(vmulq_n_s16(V, 409), 8));

            int16x8_t G =
                vsubq_s16(
                    vsubq_s16(
                        Y,
                        vshrq_n_s16(vmulq_n_s16(U, 100), 8)),
                    vshrq_n_s16(vmulq_n_s16(V, 208), 8));

            int16x8_t B =
                vaddq_s16(
                    Y,
                    vshrq_n_s16(vmulq_n_s16(U, 516), 8));

            uint8x8_t r = vqmovun_s16(R);
            uint8x8_t g = vqmovun_s16(G);
            uint8x8_t b = vqmovun_s16(B);

            for (int i = 0; i < 8; i++) {

                dst[yRow + x + i] =
                        0xFF000000 |
                        (vget_lane_u8(r, i) << 16) |
                        (vget_lane_u8(g, i) << 8) |
                        vget_lane_u8(b, i);
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    env->ReleaseByteArrayElements(yuvArray, yuv, JNI_ABORT);
}
