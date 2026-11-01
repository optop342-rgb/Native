cmake_minimum_required(VERSION 3.22)
project(HeroCineNative LANGUAGES CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "HeroCineNative must be built with the Android NDK.")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(FetchContent)
FetchContent_Declare(
    preloader_android
    GIT_REPOSITORY https://github.com/LiteLDev/preloader-android.git
    GIT_TAG 0.2.2
)
FetchContent_MakeAvailable(preloader_android)

FetchContent_Declare(
    imgui
    GIT_REPOSITORY https://github.com/ocornut/imgui.git
    GIT_TAG v1.91.9b
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(imgui)
if(NOT imgui_POPULATED)
    FetchContent_Populate(imgui)
endif()

add_library(herocine_imgui STATIC
    ${imgui_SOURCE_DIR}/imgui.cpp
    ${imgui_SOURCE_DIR}/imgui_draw.cpp
    ${imgui_SOURCE_DIR}/imgui_tables.cpp
    ${imgui_SOURCE_DIR}/imgui_widgets.cpp
    ${imgui_SOURCE_DIR}/backends/imgui_impl_opengl3.cpp
)
target_include_directories(herocine_imgui PUBLIC
    ${imgui_SOURCE_DIR}
    ${imgui_SOURCE_DIR}/backends
)
target_compile_definitions(herocine_imgui PRIVATE IMGUI_IMPL_OPENGL_ES3)

find_library(EGL_LIBRARY EGL REQUIRED)
find_library(GLES_LIBRARY GLESv3 REQUIRED)

add_library(herocine_native SHARED
    src/HeroCineMod.cpp
)
target_link_libraries(herocine_native PRIVATE
    preloader
    herocine_imgui
    ${EGL_LIBRARY}
    ${GLES_LIBRARY}
    ${CMAKE_DL_LIBS}
)
target_compile_features(herocine_native PRIVATE cxx_std_20)
set_target_properties(herocine_imgui PROPERTIES POSITION_INDEPENDENT_CODE ON)

set_target_properties(herocine_native PROPERTIES
    OUTPUT_NAME "herocine_native"
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
