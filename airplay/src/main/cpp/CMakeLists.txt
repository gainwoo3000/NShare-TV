cmake_minimum_required(VERSION 3.22.1)
project(nsfairplay C)

file(GLOB PLAYFAIR_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/playfair/*.c)

add_library(nsfairplay SHARED fairplay_jni.c ${PLAYFAIR_SOURCES})
target_include_directories(nsfairplay PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(nsfairplay PRIVATE -O2 -w)
