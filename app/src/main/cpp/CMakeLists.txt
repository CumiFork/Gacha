cmake_minimum_required(VERSION 3.22.1)

project("cpprootoverlay")

add_library(
        nativecore
        SHARED
        native-lib.cpp
)

add_executable(espline esp_line.cpp)
set_target_properties(espline PROPERTIES
        OUTPUT_NAME "libespline"
        PREFIX ""
        SUFFIX ".so"
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
        nativecore
        ${log-lib}
        ${android-lib}
)

target_link_libraries(
        espline
        ${log-lib}
)
