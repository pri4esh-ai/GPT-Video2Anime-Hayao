cmake_minimum_required(VERSION 3.22.1)
project("gptvideo2anime")

# FIXED: Removed NativeYuvUtils.cpp from the source list. 
# All native code is now safely contained in native_bridge.cpp.
add_library(
    gptvideo2anime
    SHARED
    native_bridge.cpp
)

# Locate required Android system NDK libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(graphics-lib jnigraphics)

# Link NDK libraries to target
target_link_libraries(
    gptvideo2anime
    ${log-lib}
    ${android-lib}
    ${graphics-lib}
)
