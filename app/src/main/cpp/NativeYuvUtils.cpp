#include <cstdint>

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
    ) {
        // Cast to uint32_t pointer for safe ARGB_8888 pixel writing
        uint32_t* outPixels = reinterpret_cast<uint32_t*>(outRgba);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Y plane index
                int yIndex = y * yRowStride + x;
                int Y = yPlane[yIndex];

                // U and V planes (subsampling is usually 2x2, so we divide coordinates by 2)
                int uvIndex = (y / 2) * uvRowStride + (x / 2) * uvPixelStride;
                int U = uPlane[uvIndex];
                int V = vPlane[uvIndex];

                // Standard YUV to RGB conversion formula (BT.601)
                int C = Y - 16;
                int D = U - 128;
                int E = V - 128;

                int R = (298 * C + 409 * E + 128) >> 8;
                int G = (298 * C - 100 * D - 208 * E + 128) >> 8;
                int B = (298 * C + 516 * D + 128) >> 8;

                // Clamp values to 0-255
                R = R < 0 ? 0 : (R > 255 ? 255 : R);
                G = G < 0 ? 0 : (G > 255 ? 255 : G);
                B = B < 0 ? 0 : (B > 255 ? 255 : B);

                // Write ARGB_8888 pixel (Alpha = 255)
                outPixels[y * width + x] = (255u << 24) | (static_cast<uint32_t>(R) << 16) | (static_cast<uint32_t>(G) << 8) | static_cast<uint32_t>(B);
            }
        }
    }
}
