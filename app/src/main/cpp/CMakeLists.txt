cmake_minimum_required(VERSION 3.22)

project(anime_engine)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    anime_engine
    SHARED
    native_bridge.cpp
    NativeYuvUtils.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    anime_engine
    ${log-lib}
    ${jnigraphics-lib}
)
