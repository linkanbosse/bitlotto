cmake_minimum_required(VERSION 3.22.1)
project(bitlotto C)

add_library(bitminer SHARED native_hasher.c)

target_compile_options(bitminer PRIVATE -O3 -fno-strict-aliasing -Wall)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Enables the ARMv8 SHA2 instructions; they are only executed after a runtime HWCAP check.
    target_compile_options(bitminer PRIVATE -march=armv8-a+crypto)
endif()

# 16 KB page-size compatibility (Android 15+ devices).
target_link_options(bitminer PRIVATE "-Wl,-z,max-page-size=16384")
