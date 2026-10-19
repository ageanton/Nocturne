cmake_minimum_required(VERSION 3.22.1)
project("nocturne")

include(FetchContent)

# Fetch TinySoundFont header for authentic .sf2 multi-sample synthesis
FetchContent_Declare(
    tsf
    GIT_REPOSITORY https://github.com/schellingb/TinySoundFont.git
    GIT_TAG master
)
FetchContent_MakeAvailable(tsf)

find_package(oboe REQUIRED CONFIG)

add_library(nocturne SHARED native-lib.cpp)

target_include_directories(nocturne PRIVATE ${tsf_SOURCE_DIR})

target_link_libraries(
    nocturne
    oboe::oboe
    android
    log
)
