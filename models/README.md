# Anime Model

Put the ONNX anime model here only when its license,
source and SHA-256 checksum have been verified.

Expected runtime filename:

anime.onnx

The Android application is designed so that model
processing can remain offline after the model is installed.

The actual model adapter will be connected to the
MediaCodec -> tracking -> ONNX -> rendering pipeline
in the next implementation stage.
