# Builds librecallwhisper.so (arm64-v8a) from jni.cpp + a whisper.cpp
# source checkout. WHISPER_SRC must point at the checkout (v1.9.5).
# Driven by build.sh; the Gradle build only packages the result.
cmake_minimum_required(VERSION 3.22)
project(recallwhisper CXX C)

if(NOT DEFINED WHISPER_SRC)
    message(FATAL_ERROR "Pass -DWHISPER_SRC=/path/to/whisper.cpp")
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# ggml defaults to OpenMP with the NDK clang, which makes the .so
# depend on libomp.so - a library Android does not ship (dlopen
# failed on device, Nov 1, 2026). ggml's own threadpool is fine.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_SRC} ${CMAKE_BINARY_DIR}/whisper.cpp)

add_library(recallwhisper SHARED jni.cpp)
target_include_directories(recallwhisper PRIVATE ${WHISPER_SRC}/include)
target_link_libraries(recallwhisper PRIVATE whisper log)
# 16 KB page-size alignment (Android 15+ devices): without this the
# loader warns "LOAD segment not aligned" and 16 KB-page devices
# refuse the library outright.
target_link_options(recallwhisper PRIVATE -Wl,-z,max-page-size=16384)
