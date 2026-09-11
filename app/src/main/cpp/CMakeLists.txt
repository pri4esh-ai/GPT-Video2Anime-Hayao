cmake_minimum_required(VERSION 3.22.1)
project("gptvideo2anime")

# Compile native bridge and YUV utilities into shared library
add_library(
    native_bridge
    SHARED
    native_bridge.cpp
    NativeYuvUtils.cpp
)

# Locate required Android system NDK libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(graphics-lib jnigraphics)

# Link NDK libraries to target
target_link_libraries(
    native_bridge
    ${log-lib}
    ${android-lib}
    ${graphics-lib}
)
