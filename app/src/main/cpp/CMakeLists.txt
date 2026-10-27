cmake_minimum_required(VERSION 3.22.1)
project(lanenet CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 说明：这里刻意不链接 libVAE.so。
# 它的符号表里有畸形条目（_end / __bss_start 等 local 符号混进了 global 段），
# lld 会直接拒绝链接。改成运行时 dlopen("libVAE.so") + dlsym，见 lanenet_jni.cpp。
#
# 只编出 liblanenet.so 这一个薄壳，MNN 全部来自 jniLibs 里的 libVAE.so。
add_library(lanenet SHARED lanenet_jni.cpp)

target_include_directories(lanenet PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/mnn/include)

target_link_libraries(lanenet PRIVATE
        log)
