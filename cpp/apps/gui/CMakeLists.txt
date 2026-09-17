set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_AUTOUIC OFF)

# Qt ≤6.8.3 FindWrapOpenGL falls back to "-framework AGL", removed from
# Xcode 26 / macOS Tahoe SDKs (QTBUG-137687). Pre-define the wrap target so
# FindWrapOpenGL early-returns without the AGL fallback.
if(APPLE AND NOT TARGET WrapOpenGL::WrapOpenGL)
  find_package(OpenGL REQUIRED)
  add_library(WrapOpenGL::WrapOpenGL INTERFACE IMPORTED)
  target_link_libraries(WrapOpenGL::WrapOpenGL INTERFACE OpenGL::GL)
endif()

find_package(Qt6 REQUIRED COMPONENTS Widgets Network)

# Scrub any baked-in AGL from Qt imported targets (prebuilt ≤6.8.3).
if(APPLE)
  foreach(_i2pchat_qt_tgt Qt6::Gui Qt6::OpenGL Qt6::Widgets WrapOpenGL::WrapOpenGL)
    if(NOT TARGET ${_i2pchat_qt_tgt})
      continue()
    endif()
    get_target_property(_i2pchat_qt_libs ${_i2pchat_qt_tgt} INTERFACE_LINK_LIBRARIES)
    if(_i2pchat_qt_libs)
      list(FILTER _i2pchat_qt_libs EXCLUDE REGEX "(^|.*-)framework AGL$|^AGL$|.*AGL\\.framework.*")
      set_property(TARGET ${_i2pchat_qt_tgt} PROPERTY INTERFACE_LINK_LIBRARIES "${_i2pchat_qt_libs}")
    endif()
  endforeach()
endif()

qt_add_executable(i2pchat-gui
  main.cpp
  chat_window.cpp
  profile_select_dialog.cpp
  profile_combo.cpp
  rounded_scrollbar.cpp
  actions_popup.cpp
  chat_model.cpp
  chat_item_delegate.cpp
  contact_list_model.cpp
  contact_item_delegate.cpp
  tofu_dialog.cpp
  group_topology_map.cpp
  router_settings_dialog.cpp
  dialog_theme.cpp
  emoji_picker.cpp
  resources.qrc
  ${CMAKE_CURRENT_SOURCE_DIR}/../tui/options.cpp
)
if(WIN32)
  # Avoid a spare console window for the GUI itself (i2pd is started separately).
  set_target_properties(i2pchat-gui PROPERTIES WIN32_EXECUTABLE TRUE)
endif()


target_include_directories(i2pchat-gui PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}
  ${CMAKE_CURRENT_SOURCE_DIR}/../tui
)
target_compile_definitions(i2pchat-gui PRIVATE
  I2PCHAT_VERSION="${PROJECT_VERSION}"
)
target_link_libraries(i2pchat-gui PRIVATE
  i2pchat::core
  i2pchat_project_options
  Qt6::Widgets
  Qt6::Network
)

if(APPLE)
  # Homebrew Qt uses @rpath. Point the build tree at Qt's lib dir so the
  # binary runs before bundling; after install, look next to the .app.
  get_filename_component(_i2pchat_qt_lib "${Qt6_DIR}/../.." ABSOLUTE)
  set_target_properties(i2pchat-gui PROPERTIES
    BUILD_RPATH "${_i2pchat_qt_lib}"
    INSTALL_RPATH "@executable_path/../Frameworks"
    BUILD_WITH_INSTALL_RPATH OFF
  )
endif()

install(TARGETS i2pchat-gui RUNTIME DESTINATION bin)
