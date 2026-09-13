cmake_minimum_required(VERSION 3.22.1)

project(dictation_whisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build only the CPU backend used by Android. The upstream defaults enable
# optional desktop backends and examples that are not needed by the app.
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_OPENVINO OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(ggml)

add_library(dictation-whisper SHARED
    src/whisper.cpp
    whisper_jni.cpp
)

target_compile_definitions(dictation-whisper PRIVATE GGML_USE_CPU WHISPER_VERSION=\"unknown\")
target_compile_options(dictation-whisper PRIVATE -O3 -ffunction-sections -fdata-sections)
target_link_options(dictation-whisper PRIVATE -Wl,--gc-sections)
target_include_directories(dictation-whisper PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml/include
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml/src
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml/src/ggml-cpu
)

find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(dictation-whisper ${log-lib} ${android-lib} ggml)
