#include <cstdint>
#include <algorithm>

namespace NativeYuvUtils {

inline uint8_t clamp(int val) {
    return static_cast<uint8_t>(std::max(0, std::min(255, val)));
}

/**
 * Fast hardware-friendly YUV_420_888 to RGBA buffer conversion.
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
