``` GPT-Video2Anime/ ├── app/ │ ├── src/main/ │ │ ├── java/com/gptvideo2anime/ │ │ │ ├── MainActivity.kt │ │ │ ├── pipeline/ │ │ │ ├── tracking/ │ │ │ ├── inference/ │ │ │ └── render/ │ │ ├── AndroidManifest.xml │ │ └── res/ ├── models/ │ ├── README.md │ └── .gitkeep ├── .github/workflows/ │ └── android-apk.yml ├── build.gradle.kts ├── settings.gradle.kts ├── gradle.properties └── README.md ```
README (first commit)
The README defines the mission:
Offline Android anime video generation.
30–60 FPS long-video support.
MediaCodec hardware decoding/encoding.
MediaPipe multi-person tracking.
ONNX Runtime offline inference.
Character consistency memory.
Occlusion handling.
GitHub Actions APK builds.
