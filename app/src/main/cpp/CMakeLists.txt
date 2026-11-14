cmake_minimum_required(VERSION 3.22.1)
project(rajplayer_mp3 LANGUAGES C CXX)

# The Maven AAR is a Prefab package named "lame". It provides the public
# headers and prebuilt LAME library for Android ABIs.
find_package(lame REQUIRED CONFIG)

set(RAJPLAYER_LAME_TARGET "")
foreach(candidate
        lame::lame
        lame::libmp3lame
        lame::libmp3lame.a)
    if(TARGET ${candidate})
        set(RAJPLAYER_LAME_TARGET ${candidate})
        break()
    endif()
endforeach()

# Be tolerant of Prefab package target naming differences between releases.
if(NOT RAJPLAYER_LAME_TARGET)
    get_property(prefab_targets DIRECTORY PROPERTY IMPORTED_TARGETS)
    foreach(candidate IN LISTS prefab_targets)
        if(candidate MATCHES "^lame::" AND candidate MATCHES "mp3lame")
            set(RAJPLAYER_LAME_TARGET ${candidate})
            break()
        endif()
    endforeach()
endif()

if(NOT RAJPLAYER_LAME_TARGET)
    message(FATAL_ERROR "LAME Prefab package was found, but its libmp3lame target was not exported")
endif()

add_library(rajplayer_mp3 SHARED mp3_jni.cpp)
target_compile_features(rajplayer_mp3 PRIVATE cxx_std_17)
target_link_libraries(rajplayer_mp3 PRIVATE ${RAJPLAYER_LAME_TARGET} log)
