cmake_minimum_required(VERSION 3.14)
project(ehviewer C)
include(ExternalProject)
include(FetchContent)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    string(CONCAT CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math -fvisibility=hidden -fvisibility-inlines-hidden -funroll-loops -flto "
            "-ffile-prefix-map=${ANDROID_TOOLCHAIN_ROOT}= "
            "-ffile-prefix-map=${CMAKE_SOURCE_DIR}= "
            "-ffile-prefix-map=${CMAKE_BINARY_DIR}= "
            "-mllvm -polly "
            "-mllvm -polly-run-dce "
            "-mllvm -polly-run-inliner "
            "-mllvm -polly-isl-arg=--no-schedule-serialize-sccs "
            "-mllvm -polly-ast-use-context "
            "-mllvm -polly-detect-keep-going "
            "-mllvm -polly-position=before-vectorizer "
            "-mllvm -polly-vectorizer=stripmine "
            "-mllvm -polly-detect-profitability-min-per-loop-insts=40 "
            "-mllvm -polly-invariant-load-hoisting")
endif (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")

option(BUILD_TESTING OFF)
option(XZ_DOC OFF)
option(XZ_LZIP_DECODER OFF)
option(XZ_MICROLZMA_DECODER OFF)
option(XZ_MICROLZMA_ENCODER OFF)
option(XZ_TOOL_LZMADEC OFF)
option(XZ_TOOL_LZMAINFO OFF)
option(XZ_TOOL_XZ OFF)
option(XZ_TOOL_XZDEC OFF)
FetchContent_Declare(
        liblzma
        GIT_REPOSITORY https://github.com/tukaani-project/xz.git
        GIT_TAG v5.8.3
        GIT_SHALLOW 1
)

FetchContent_MakeAvailable(liblzma)
include_directories(${liblzma_SOURCE_DIR}/src/liblzma/api)

# Build GNUTLS libnettle
if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(NDK_VERSION "${ANDROID_NDK_MAJOR}.${ANDROID_NDK_MINOR}.${ANDROID_NDK_BUILD}")
    set(TOOLCHAIN "$ANDROID_HOME/ndk/${NDK_VERSION}/toolchains/llvm/prebuilt/linux-x86_64/bin")
    set(WSL wsl)
else ()
    set(TOOLCHAIN "${ANDROID_TOOLCHAIN_ROOT}/bin")
endif ()
set(CONFIGURE_COMMAND ${WSL} ./.bootstrap && ${WSL} ./configure --disable-public-key --disable-dependency-tracking
        "--host=${CMAKE_C_COMPILER_TARGET}"
        "CFLAGS=${CMAKE_C_FLAGS} -Qunused-arguments"
        "AR=${TOOLCHAIN}/llvm-ar"
        "CC=${TOOLCHAIN}/clang --target=${CMAKE_C_COMPILER_TARGET}"
        "RANLIB=${TOOLCHAIN}/llvm-ranlib"
)
ExternalProject_Add(
        nettle
        GIT_REPOSITORY https://gitlab.com/gnutls/nettle.git
        GIT_TAG nettle_4.0_release_20260205
        GIT_SHALLOW 1
        GIT_CONFIG core.autocrlf=input
        CONFIGURE_COMMAND ${CONFIGURE_COMMAND}
        BUILD_COMMAND ${WSL} make -j4 libnettle.a
        INSTALL_COMMAND ""
        BUILD_IN_SOURCE 1
        BUILD_BYPRODUCTS <SOURCE_DIR>/libnettle.a
)

ExternalProject_Get_Property(nettle SOURCE_DIR)
include_directories(${SOURCE_DIR}/..)
add_library(libnettle STATIC IMPORTED)
set_property(TARGET libnettle PROPERTY IMPORTED_LOCATION ${SOURCE_DIR}/libnettle.a)
add_dependencies(libnettle nettle)

# Configure libnettle support for libarchive
set(HAVE_LIBNETTLE 1)
set(HAVE_NETTLE_AES_H 1)
set(HAVE_NETTLE_HMAC_H 1)
set(HAVE_NETTLE_MD5_H 1)
set(HAVE_NETTLE_PBKDF2_H 1)
set(HAVE_NETTLE_RIPEMD160_H 1)
set(HAVE_NETTLE_SHA_H 1)

# Configure lzma support for libarchive
SET(HAVE_LIBLZMA 1)
SET(HAVE_LZMA_H 1)
SET(HAVE_LZMA_STREAM_ENCODER_MT 1)
SET(HAVE_LZMADEC_H 1)
SET(HAVE_LIBLZMADEC 1)

option(ENABLE_OPENSSL OFF)
option(ENABLE_TAR OFF)
option(ENABLE_CPIO OFF)
option(ENABLE_CAT OFF)
option(ENABLE_UNZIP OFF)
option(ENABLE_TEST OFF)

set(LIBARCHIVE_PATCH ${CMAKE_CURRENT_LIST_DIR}/0001-Fix-zip_time-performance.patch)
FetchContent_Declare(
        libarchive
        GIT_REPOSITORY https://github.com/libarchive/libarchive.git
        GIT_TAG v3.8.9
        GIT_SHALLOW 1
        PATCH_COMMAND git apply --check -R ${LIBARCHIVE_PATCH} || git apply ${LIBARCHIVE_PATCH}
)

FetchContent_MakeAvailable(libarchive)

# https://github.com/libarchive/libarchive/commit/b91f32b55f111f2ee487793f331322dea282b02f
target_compile_options(archive_static PRIVATE -Wno-error=unused-variable)

target_link_libraries(archive_static liblzma libnettle)
include_directories(${libarchive_SOURCE_DIR}/libarchive)

FetchContent_Declare(
        libwebp
        GIT_REPOSITORY https://github.com/webmproject/libwebp.git
        GIT_TAG v1.6.0
        GIT_SHALLOW 1
)

option(WEBP_BUILD_ANIM_UTILS OFF)
option(WEBP_BUILD_CWEBP OFF)
option(WEBP_BUILD_DWEBP OFF)
option(WEBP_BUILD_VWEBP OFF)
option(WEBP_BUILD_WEBPINFO OFF)
option(WEBP_BUILD_LIBWEBPMUX OFF)
option(WEBP_BUILD_EXTRAS OFF)
option(WEBP_ENABLE_SWAP_16BIT_CSP ON)

# Disable runtime detection of NEON support as NEON is required since API level 23
# https://source.android.com/docs/compatibility/6.0/android-6.0-cdd#3_3_1_application_binary_interfaces
unset(ANDROID)
FetchContent_MakeAvailable(libwebp)
if (CMAKE_SYSTEM_NAME STREQUAL "Android")
    set(ANDROID 1)
endif ()

set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ../../../../rust-toolchain.toml)
unset(Rust_TOOLCHAIN CACHE)
FetchContent_Declare(
        Corrosion
        GIT_REPOSITORY https://github.com/FooIbar/corrosion.git
        GIT_TAG v0.6.1
        GIT_SHALLOW 1
)

FetchContent_MakeAvailable(Corrosion)
corrosion_import_crate(
        MANIFEST_PATH ../rust/Cargo.toml
        LOCKED
        FEATURES $<IF:$<VERSION_GREATER_EQUAL:${ANDROID_NATIVE_API_LEVEL},26>,android-26,android>
        FLAGS $<$<NOT:$<CONFIG:Debug>>:-Z build-std -Z build-std-features=>
)
# Corrosion hands cargo `CC_<rust target> = CMAKE_C_COMPILER` (Corrosion.cmake: "CC_${stripped_target_triple}
# =${CMAKE_C_COMPILER}"), and for Android that is the NDK's bare `clang`: CMake carries the API level in a
# separate `--target=<triple><level>` flag, not in the compiler path. The `cc` crate then compiles zstd-sys's
# C with `--target=<rust target>` and no level, and NDK 30's cdefs.h refuses an unversioned triple
# ("Unversioned target triples are not supported!") -- that guard wants __ANDROID_MIN_SDK_VERSION__, which
# clang's driver only defines when the triple carries the level.
#
# Setting CC_<triple> here does not help: Corrosion appends its own assignment to the same key after ours in
# the `cmake -E env` list, so the bare clang still wins. CFLAGS_<triple> is ours alone -- Corrosion sets no
# CFLAGS -- and the crate appends it after its own --target, so the macro gets defined either way.
get_filename_component(RUST_NDK_BIN ${CMAKE_C_COMPILER} DIRECTORY)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TRIPLE aarch64-linux-android)
    set(RUST_CC_WRAPPER aarch64-linux-android${ANDROID_NATIVE_API_LEVEL}-clang)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TRIPLE armv7-linux-androideabi)
    set(RUST_CC_WRAPPER armv7a-linux-androideabi${ANDROID_NATIVE_API_LEVEL}-clang)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TRIPLE x86_64-linux-android)
    set(RUST_CC_WRAPPER x86_64-linux-android${ANDROID_NATIVE_API_LEVEL}-clang)
endif()
if(RUST_TRIPLE)
    set(RUST_CC ${RUST_NDK_BIN}/${RUST_CC_WRAPPER})
    string(REPLACE "-" "_" RUST_TRIPLE_U ${RUST_TRIPLE})
    corrosion_set_env_vars(ehviewer_rust
            CC_${RUST_TRIPLE}=${RUST_CC}
            CC_${RUST_TRIPLE_U}=${RUST_CC}
            CFLAGS_${RUST_TRIPLE}=-D__ANDROID_MIN_SDK_VERSION__=${ANDROID_NATIVE_API_LEVEL}
            CFLAGS_${RUST_TRIPLE_U}=-D__ANDROID_MIN_SDK_VERSION__=${ANDROID_NATIVE_API_LEVEL}
    )
endif()
corrosion_add_target_rustflags(ehviewer_rust $<$<NOT:$<CONFIG:Debug>>:-Z unstable-options -C panic=immediate-abort -Z location-detail=none -Z fmt-debug=none>)
corrosion_link_libraries(ehviewer_rust android jnigraphics log webp webpdemux)

# Build and link our app's native lib
add_library(${PROJECT_NAME} SHARED archive.c gifutils.c hash.c natsort/strnatcmp.c zip_central_dir.c)
target_link_libraries(
        ${PROJECT_NAME} archive_static ehviewer_rust z
        -Wl,-u,JNI_OnLoad -Wl,--undefined-glob,Java_*
        -Wl,--version-script,${CMAKE_SOURCE_DIR}/libehviewer.map.txt
)
