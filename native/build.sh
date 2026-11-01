#!/bin/bash
# Build librecallwhisper.so for arm64-v8a on bigpc and drop it into
# the app's jniLibs. Run on bigpc (needs the NDK + a whisper.cpp
# checkout). Versions: whisper.cpp v1.9.5, NDK r27d.
set -e
NDK=${NDK:-$HOME/android-sdk/ndk-r27d}
WHISPER_SRC=${WHISPER_SRC:-$HOME/Projects/whisper.cpp}
PROJ=${PROJ:-$HOME/Projects/recallvault}
BUILD=$HOME/Scratch/recallwhisper-build

cmake -S "$PROJ/native" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DWHISPER_SRC="$WHISPER_SRC"
cmake --build "$BUILD" -j8

mkdir -p "$PROJ/jniLibs/arm64-v8a"
cp "$BUILD/librecallwhisper.so" "$PROJ/jniLibs/arm64-v8a/"
ls -la "$PROJ/jniLibs/arm64-v8a/"
