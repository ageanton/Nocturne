cmake_minimum_required(VERSION 3.22.1)
project("nocturne")

add_library(nocturne SHARED native-lib.cpp)

target_link_libraries(
    nocturne
    aaudio
    android
    log
)
