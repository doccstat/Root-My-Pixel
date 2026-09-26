cmake_minimum_required(VERSION 3.22.1)
project("pixelroot-native")

add_library(pixel_native SHARED
    native_probe.c
)

target_link_libraries(pixel_native
    android
    log
)
