cmake_minimum_required(VERSION 3.24)

file(READ "${CMAKE_CURRENT_SOURCE_DIR}/../VERSION" I2PCHAT_VERSION_RAW)
string(STRIP "${I2PCHAT_VERSION_RAW}" I2PCHAT_VERSION)

project(i2pchat_cpp VERSION ${I2PCHAT_VERSION} LANGUAGES CXX)

# Prefer Boost's own package config over the removed FindBoost module.
if(POLICY CMP0167)
  cmake_policy(SET CMP0167 NEW)
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "Build type" FORCE)
endif()

option(I2PCHAT_BUILD_TESTS "Build the C++ test suite" ON)
option(I2PCHAT_BUILD_TUI "Build the FTXUI terminal client" ON)
option(I2PCHAT_BUILD_GUI "Build the Qt 6 desktop client" OFF)
option(I2PCHAT_ANDROID "Build the core library for Android NDK" OFF)
option(I2PCHAT_WARNINGS_AS_ERRORS "Treat compiler warnings as errors" OFF)

if(I2PCHAT_ANDROID)
  set(I2PCHAT_BUILD_TESTS OFF)
  set(I2PCHAT_BUILD_TUI OFF)
  set(I2PCHAT_BUILD_GUI OFF)
endif()

# Homebrew prefixes are not on the default search path for find_package.
if(APPLE)
  execute_process(COMMAND brew --prefix OUTPUT_VARIABLE I2PCHAT_BREW_PREFIX
                  OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
  if(I2PCHAT_BREW_PREFIX)
    list(APPEND CMAKE_PREFIX_PATH "${I2PCHAT_BREW_PREFIX}")
    foreach(pkg libsodium boost nlohmann-json catch2 ftxui qt)
      if(EXISTS "${I2PCHAT_BREW_PREFIX}/opt/${pkg}")
        list(APPEND CMAKE_PREFIX_PATH "${I2PCHAT_BREW_PREFIX}/opt/${pkg}")
      endif()
    endforeach()
  endif()
endif()

add_library(i2pchat_project_options INTERFACE)
target_compile_options(i2pchat_project_options INTERFACE
  $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall;-Wextra;-Wpedantic;-Wshadow;-Wconversion;-Wsign-conversion>
  $<$<CXX_COMPILER_ID:MSVC>:/W4;/permissive->
)
if(I2PCHAT_WARNINGS_AS_ERRORS)
  target_compile_options(i2pchat_project_options INTERFACE
    $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Werror>
    $<$<CXX_COMPILER_ID:MSVC>:/WX>
  )
endif()

# --- Dependencies ------------------------------------------------------------

find_package(PkgConfig QUIET)

# libsodium ships a CMake config only in some distributions; fall back to
# pkg-config and then to a manual search so the build works on stock Homebrew,
# vcpkg and Debian alike. The Android NDK build may already have created the
# alias from FetchContent before this file is included.
if(NOT TARGET i2pchat::sodium)
find_package(unofficial-sodium CONFIG QUIET)
if(TARGET unofficial-sodium::sodium)
  add_library(i2pchat::sodium ALIAS unofficial-sodium::sodium)
else()
  if(PkgConfig_FOUND)
    pkg_check_modules(SODIUM QUIET IMPORTED_TARGET libsodium)
  endif()
  if(TARGET PkgConfig::SODIUM)
    add_library(i2pchat::sodium ALIAS PkgConfig::SODIUM)
  else()
    find_path(SODIUM_INCLUDE_DIR sodium.h REQUIRED)
    find_library(SODIUM_LIBRARY NAMES sodium libsodium REQUIRED)
    add_library(i2pchat_sodium UNKNOWN IMPORTED)
    set_target_properties(i2pchat_sodium PROPERTIES
      IMPORTED_LOCATION "${SODIUM_LIBRARY}"
      INTERFACE_INCLUDE_DIRECTORIES "${SODIUM_INCLUDE_DIR}")
    add_library(i2pchat::sodium ALIAS i2pchat_sodium)
  endif()
endif()
endif()

# nlohmann_json ships a CMake config on Debian/Homebrew/vcpkg; fall back to
# FetchContent so a stock Arch/CachyOS box can configure without extra packages.
include(FetchContent)
if(NOT TARGET nlohmann_json::nlohmann_json)
  find_package(nlohmann_json 3.10 QUIET)
endif()
if(NOT TARGET nlohmann_json::nlohmann_json)
  FetchContent_Declare(nlohmann_json
    URL https://github.com/nlohmann/json/releases/download/v3.11.3/json.tar.xz
  )
  FetchContent_MakeAvailable(nlohmann_json)
endif()
# asio::cancel_after arrived in Boost 1.86. Ubuntu 24.04 ships 1.83, so fall
# back to the same FetchContent path Android already uses when the system
# package is too old.
if(NOT TARGET Boost::headers)
  find_package(Boost 1.86 QUIET)
endif()
if(NOT TARGET Boost::headers)
  message(STATUS "Boost ≥1.86 not found; fetching boost-1.86.0 (asio::cancel_after)")
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
if(NOT TARGET Boost::headers)
  message(FATAL_ERROR "Boost ≥1.86 is required (asio::cancel_after)")
endif()

# Third-party headers do not survive our warning set, and their noise would bury
# warnings about our own code. Boost usually lives in a default search path, so
# marking its include directory SYSTEM has no effect; Clang can classify by
# header prefix instead, which does work.
target_compile_options(i2pchat_project_options INTERFACE
  $<$<CXX_COMPILER_ID:Clang,AppleClang>:
    --system-header-prefix=boost/;--system-header-prefix=nlohmann/>
)

add_subdirectory(core)
if(NOT I2PCHAT_ANDROID)
  add_subdirectory(apps/blindbox-daemon)
endif()

if(I2PCHAT_BUILD_TESTS)
  enable_testing()
  add_subdirectory(tests)
  # The interop harness is only useful alongside the tests that drive it.
  add_subdirectory(tools)
endif()

# The clients arrive in later phases. Until their directories exist, silently
# skip them rather than failing a configure that is perfectly able to build the
# core and its tests.
foreach(app tui gui)
  string(TOUPPER "${app}" app_upper)
  if(I2PCHAT_BUILD_${app_upper})
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/apps/${app}/CMakeLists.txt")
      add_subdirectory(apps/${app})
    else()
      message(STATUS "apps/${app} is not present yet; skipping")
      set(I2PCHAT_BUILD_${app_upper} OFF)
    endif()
  endif()
endforeach()

message(STATUS "I2PChat C++ ${I2PCHAT_VERSION}: tests=${I2PCHAT_BUILD_TESTS} "
               "tui=${I2PCHAT_BUILD_TUI} gui=${I2PCHAT_BUILD_GUI}")
