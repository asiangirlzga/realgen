cmake_minimum_required(VERSION 3.22)
project(sdjni C CXX)

# The engine (stable-diffusion.cpp, pinned) is cloned into ./sd by the CI workflow.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# ARMv8.2 + dot product + fp16: Cortex-A76 and newer (e.g. Dimensity 6300).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/sd ${CMAKE_CURRENT_BINARY_DIR}/sd)

add_library(sdjni SHARED sdjni.cpp)
target_link_libraries(sdjni PRIVATE stable-diffusion log)
