cmake_minimum_required(VERSION 3.22)
project(ResistanceLab CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

if(NOT PRELOADER_SDK_ROOT OR NOT EXISTS "${PRELOADER_SDK_ROOT}/include/pl/Mod.hpp")
    message(FATAL_ERROR "Set PRELOADER_SDK_ROOT to a Preloader SDK checkout")
endif()

include(FetchContent)
FetchContent_Declare(
    fmt
    URL https://github.com/fmtlib/fmt/archive/refs/tags/11.2.0.zip
)
FetchContent_MakeAvailable(fmt)

add_library(resistance_lab SHARED src/ResistanceLabMod.cpp)

target_include_directories(resistance_lab PRIVATE
    "${PRELOADER_SDK_ROOT}/include"
)

find_library(log-lib log REQUIRED)
target_link_libraries(resistance_lab PRIVATE fmt::fmt "${log-lib}")

target_link_options(resistance_lab PRIVATE "-Wl,--strip-all")
