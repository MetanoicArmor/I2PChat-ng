find_package(ftxui CONFIG QUIET)
if(NOT TARGET ftxui::component)
  # Homebrew installs a CMake config under lib/cmake/ftxui.
  find_package(ftxui QUIET)
endif()
if(NOT TARGET ftxui::component)
  include(FetchContent)
  FetchContent_Declare(ftxui
    URL https://github.com/ArthurSonzogni/FTXUI/archive/refs/tags/v6.1.9.tar.gz
  )
  set(FTXUI_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
  set(FTXUI_BUILD_DOCS OFF CACHE BOOL "" FORCE)
  set(FTXUI_BUILD_TESTS OFF CACHE BOOL "" FORCE)
  set(FTXUI_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
  FetchContent_MakeAvailable(ftxui)
endif()
if(NOT TARGET ftxui::component)
  message(FATAL_ERROR "FTXUI not found. Install it (e.g. brew install ftxui) "
                      "or enable the vcpkg `tui` feature.")
endif()

add_executable(i2pchat-tui
  main.cpp
  app.cpp
  options.cpp
)

target_include_directories(i2pchat-tui PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(i2pchat-tui PRIVATE
  I2PCHAT_VERSION="${PROJECT_VERSION}"
)
target_link_libraries(i2pchat-tui PRIVATE
  i2pchat::core
  i2pchat_project_options
  ftxui::screen
  ftxui::dom
  ftxui::component
)

install(TARGETS i2pchat-tui RUNTIME DESTINATION bin)
