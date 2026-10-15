cmake_minimum_required(VERSION 3.22)
project(wirl_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)

# Always optimize, whatever build type AGP asks for. A debug build of whisper.cpp runs many times slower.
# Only the baseline arm64 NEON instructions are used: every arm64 phone has them. dotprod and i8mm are not
# enabled, because a build that needs them crashes on phones without them.
set(CMAKE_BUILD_TYPE Release)

include(FetchContent)

# whisper.cpp, pinned to one commit so every build is the same. Only the library is built.
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG 0c7be696371069e0baa2515ed7aa08d5a5473aa6
)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# Cross-compiling for Android: no host-specific instructions, no OpenMP runtime.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(whisper)

add_library(wirl_whisper SHARED wirl_whisper.cpp)
target_link_libraries(wirl_whisper PRIVATE whisper log)
