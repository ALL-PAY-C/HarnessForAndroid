cmake_minimum_required(VERSION 3.22)
project(node_starter CXX)

# libnode.so 来自 Node.js v22.23.2（OpenFlun/nodejs-mobile 的 Android 预编译件），按 ABI 放在 jniLibs 下；
# 构建期由 node-runtime/build.gradle.kts 的 sha256 校验拦截错文件/截断，设备端才 dlopen。
# IMPORTED 目标只用于链接，不会被 CMake 重新编译或 strip；打包由 AGP 的 jniLibs 完成。
set(NODE_JNI_DIR   "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(NODE_INCLUDE   "${CMAKE_SOURCE_DIR}/include/node")

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
    IMPORTED_LOCATION "${NODE_JNI_DIR}/libnode.so")

add_library(node_starter SHARED node_starter.cpp)
target_include_directories(node_starter PRIVATE "${NODE_INCLUDE}")
target_link_libraries(node_starter node log)
