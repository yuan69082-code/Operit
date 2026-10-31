cmake_minimum_required(VERSION 3.22.1)

project(quickjsjni)

include("${CMAKE_CURRENT_LIST_DIR}/../../../../cmake/operit_git_source.cmake")

operit_prepare_git_source(
    QUICKJS_UPSTREAM_DIR
    OPERIT_QUICKJS_BINARY_DIR
    quickjs
    "https://github.com/bellard/quickjs.git"
    "e621135dc542e000e31038bedc52944aa0adaf1a"
)

# Android's allocator tags the top byte of 64-bit heap pointers. Upstream
# 47-bit NaN boxing drops that byte, aborting in malloc_usable_size/free.
# Patch an ABI-local copy so all C sources and the JNI bridge share the same
# full-pointer JSValue layout without racing other ABI configurations.
set(QUICKJS_DIR "${CMAKE_CURRENT_BINARY_DIR}/quickjs-android")
file(COPY "${QUICKJS_UPSTREAM_DIR}/" DESTINATION "${QUICKJS_DIR}")
file(READ "${QUICKJS_DIR}/quickjs.h" QUICKJS_HEADER)
set(QUICKJS_BOXING_CONDITION "#elif defined(__x86_64__) || defined(__aarch64__) || defined(__riscv)")
string(FIND "${QUICKJS_HEADER}" "${QUICKJS_BOXING_CONDITION}" QUICKJS_BOXING_OFFSET)
if(QUICKJS_BOXING_OFFSET EQUAL -1)
    message(FATAL_ERROR "QuickJS value layout changed; review Android pointer-tag preservation")
endif()
string(REPLACE "${QUICKJS_BOXING_CONDITION}"
    "#elif !defined(__ANDROID__) && (defined(__x86_64__) || defined(__aarch64__) || defined(__riscv))"
    QUICKJS_HEADER "${QUICKJS_HEADER}")
file(WRITE "${QUICKJS_DIR}/quickjs.h" "${QUICKJS_HEADER}")

file(READ "${QUICKJS_DIR}/VERSION" QUICKJS_VERSION_RAW)
string(STRIP "${QUICKJS_VERSION_RAW}" QUICKJS_VERSION)

set(OPERIT_QUICKJS_GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${OPERIT_QUICKJS_GENERATED_DIR}")
file(WRITE
    "${OPERIT_QUICKJS_GENERATED_DIR}/operit_quickjs.h"
    "#pragma once\n#include \"${QUICKJS_DIR}/quickjs.h\"\n"
)

add_library(
    quickjsjni
    SHARED
    quickjs_jni.cpp
    ${QUICKJS_DIR}/cutils.c
    ${QUICKJS_DIR}/dtoa.c
    ${QUICKJS_DIR}/libregexp.c
    ${QUICKJS_DIR}/libunicode.c
    ${QUICKJS_DIR}/quickjs.c
)

target_compile_definitions(
    quickjsjni
    PRIVATE
    _GNU_SOURCE
    CONFIG_VERSION="${QUICKJS_VERSION}"
    NDEBUG
)

target_compile_options(
    quickjsjni
    PRIVATE
    # Keep QuickJS optimized on latency-sensitive paths even in debug app builds.
    -O3
    -fwrapv
    -funsigned-char
    -Wall
    -Wextra
    -Wno-sign-compare
    -Wno-missing-field-initializers
    -Wno-unused-parameter
    -Wwrite-strings
    -Wchar-subscripts
)

target_include_directories(
    quickjsjni
    PRIVATE
    "${OPERIT_QUICKJS_GENERATED_DIR}"
)

set_target_properties(
    quickjsjni
    PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

target_link_libraries(
    quickjsjni
    android
    log
    m
)

target_link_options(quickjsjni PRIVATE "-Wl,-z,max-page-size=16384")
