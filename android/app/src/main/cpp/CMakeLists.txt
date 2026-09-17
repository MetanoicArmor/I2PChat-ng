cmake_minimum_required(VERSION 3.22)

get_filename_component(I2PCHAT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(I2PCHAT_CPP "${I2PCHAT_ROOT}/cpp")

file(READ "${I2PCHAT_ROOT}/VERSION" I2PCHAT_VERSION_RAW)
string(STRIP "${I2PCHAT_VERSION_RAW}" I2PCHAT_VERSION)
project(i2pchat_android VERSION ${I2PCHAT_VERSION} LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

# --- Third-party deps (vcpkg/prefix if present, otherwise FetchContent) ------

if(NOT TARGET nlohmann_json::nlohmann_json)
  find_package(nlohmann_json 3.10 QUIET)
endif()
if(NOT TARGET nlohmann_json::nlohmann_json)
  FetchContent_Declare(nlohmann_json
    URL https://github.com/nlohmann/json/releases/download/v3.11.3/json.tar.xz
  )
  FetchContent_MakeAvailable(nlohmann_json)
endif()

if(NOT TARGET Boost::headers)
  find_package(Boost 1.86 QUIET)
endif()
if(NOT TARGET Boost::headers)
  set(BOOST_INCLUDE_LIBRARIES asio)
  set(BOOST_ENABLE_CMAKE ON)
  FetchContent_Declare(Boost
    URL https://github.com/boostorg/boost/releases/download/boost-1.86.0/boost-1.86.0-cmake.tar.xz
  )
  FetchContent_MakeAvailable(Boost)
  if(NOT TARGET Boost::headers AND TARGET Boost::asio)
    add_library(Boost::headers ALIAS Boost::asio)
  endif()
endif()

if(NOT TARGET i2pchat::sodium)
  find_package(unofficial-sodium CONFIG QUIET)
  if(TARGET unofficial-sodium::sodium)
    add_library(i2pchat::sodium ALIAS unofficial-sodium::sodium)
  endif()
endif()
if(NOT TARGET i2pchat::sodium)
  # Official tarball — robinlinden/libsodium-cmake has no 1.0.20-RELEASE tag,
  # and its git submodule clone fails under the Android SDK CMake 3.22.
  FetchContent_Declare(libsodium
    URL https://github.com/jedisct1/libsodium/releases/download/1.0.20-RELEASE/libsodium-1.0.20.tar.gz
    URL_HASH SHA256=ebb65ef6ca439333c2bb41a0c1990587288da07f6c7fd07cb3a18cc18d30ce19
  )
  FetchContent_GetProperties(libsodium)
  if(NOT libsodium_POPULATED)
    FetchContent_Populate(libsodium)
  endif()
  if(NOT TARGET sodium)
    file(GLOB_RECURSE I2PCHAT_SODIUM_SOURCES CONFIGURE_DEPENDS
      "${libsodium_SOURCE_DIR}/src/libsodium/*.c"
    )
    add_library(sodium STATIC ${I2PCHAT_SODIUM_SOURCES})
    set_target_properties(sodium PROPERTIES
      C_STANDARD 99
      POSITION_INDEPENDENT_CODE ON
    )
    set(VERSION 1.0.20)
    set(SODIUM_LIBRARY_VERSION_MAJOR 26)
    set(SODIUM_LIBRARY_VERSION_MINOR 2)
    set(SODIUM_LIBRARY_MINIMAL_DEF "")
    # Must sit next to export.h: version.c includes "version.h", and
    # sodium/version.h includes "export.h" from the same directory.
    configure_file(
      "${libsodium_SOURCE_DIR}/src/libsodium/include/sodium/version.h.in"
      "${libsodium_SOURCE_DIR}/src/libsodium/include/sodium/version.h"
      @ONLY
    )
    target_include_directories(sodium
      PUBLIC
        "${libsodium_SOURCE_DIR}/src/libsodium/include"
      PRIVATE
        "${libsodium_SOURCE_DIR}/src/libsodium/include/sodium"
    )
    target_compile_definitions(sodium PUBLIC SODIUM_STATIC PRIVATE CONFIGURED)
  endif()
  add_library(i2pchat::sodium ALIAS sodium)
endif()

add_library(i2pchat_project_options INTERFACE)
target_compile_options(i2pchat_project_options INTERFACE
  $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic>
)

set(I2PCHAT_ANDROID ON CACHE BOOL "Build core for Android NDK" FORCE)

# Only the UI-free core — desktop apps require CMake 3.24 and host Qt/FTXUI.
add_subdirectory("${I2PCHAT_CPP}/core" "${CMAKE_BINARY_DIR}/i2pchat_core")

add_library(i2pchat_jni SHARED
  native_engine.cpp
)

target_include_directories(i2pchat_jni PRIVATE
  "${I2PCHAT_CPP}/core/src"
)

target_link_libraries(i2pchat_jni PRIVATE
  i2pchat::core
  android
  log
)

target_link_options(i2pchat_jni PRIVATE
  "-Wl,-z,max-page-size=16384"
  "-Wl,-z,common-page-size=16384"
)
target_compile_definitions(i2pchat_jni PRIVATE BOOST_ASIO_NO_DEPRECATED)
