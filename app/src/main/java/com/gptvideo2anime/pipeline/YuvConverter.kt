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

    jbyte *yuv = env->GetByteArrayElements(yuvArray, nullptr);

    AndroidBitmapInfo info;
    void *pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {

        env->ReleaseByteArrayElements(yuvArray, yuv, JNI_ABORT);
        return;
    }

    const uint8_t *yPlane =
            reinterpret_cast<const uint8_t *>(yuv);

    const uint8_t *uvPlane =
            yPlane + width * height;

    uint32_t *dst =
            static_cast<uint32_t *>(pixels);

    alignas(16) uint8_t rOut[8];
    alignas(16) uint8_t gOut[8];
    alignas(16) uint8_t bOut[8];

    for (int y = 0; y < height; y++) {

        const int yRow = y * width;
        const int uvRow = (y >> 1) * width;

        int x = 0;

        for (; x <= width - 8; x += 8) {

            uint8x8_t y8 =
                    vld1_u8(yPlane + yRow + x);

            uint8x8_t uv8 =
                    vld1_u8(uvPlane + uvRow + x);

            uint8x8x2_t uv =
                    vuzp_u8(uv8, uv8);

            uint8x8_t u =
                    vzip_u8(uv.val[0], uv.val[0]).val[0];

            uint8x8_t v =
                    vzip_u8(uv.val[1], uv.val[1]).val[0];

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

            uint8x8_t r =
                    vqmovun_s16(R);

            uint8x8_t g =
                    vqmovun_s16(G);

            uint8x8_t b =
                    vqmovun_s16(B);

            vst1_u8(rOut, r);
            vst1_u8(gOut, g);
            vst1_u8(bOut, b);

            for (int i = 0; i < 8; i++) {

                dst[yRow + x + i] =
                        0xFF000000 |
                        (static_cast<uint32_t>(rOut[i]) << 16) |
                        (static_cast<uint32_t>(gOut[i]) << 8) |
                        static_cast<uint32_t>(bOut[i]);
            }
        }

        for (; x < width; x++) {

            int Yv =
                    yPlane[yRow + x] & 0xFF;

            int uvIndex =
                    uvRow + (x & ~1);

            int Uv =
                    uvPlane[uvIndex] & 0xFF;

            int Vv =
                    uvPlane[uvIndex + 1] & 0xFF;

            int C = Yv - 16;
            int D = Uv - 128;
            int E = Vv - 128;

            int r =
                    (298 * C + 409 * E + 128) >> 8;

            int g =
                    (298 * C - 100 * D - 208 * E + 128) >> 8;

            int b =
                    (298 * C + 516 * D + 128) >> 8;

            r = r < 0 ? 0 : (r > 255 ? 255 : r);
            g = g < 0 ? 0 : (g > 255 ? 255 : g);
            b = b < 0 ? 0 : (b > 255 ? 255 : b);

            dst[yRow + x] =
                    0xFF000000 |
                    (static_cast<uint32_t>(r) << 16) |
                    (static_cast<uint32_t>(g) << 8) |
                    static_cast<uint32_t>(b);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    env->ReleaseByteArrayElements(yuvArray, yuv, JNI_ABORT);
}
