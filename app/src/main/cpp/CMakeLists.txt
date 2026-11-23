cmake_minimum_required(VERSION 3.22.1)

project("cpprootoverlay")

add_library(
        nativecore
        SHARED
        native-lib.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
        nativecore
        ${log-lib}
        ${android-lib}
)
