cmake_minimum_required(VERSION 3.22)

# Always build optimised code: a Debug build of the engine is far too slow to play.
set(GZ_FORCE_RELEASE ON CACHE BOOL "Force a Release build even for Gradle debug builds")
if(GZ_FORCE_RELEASE)
    set(CMAKE_BUILD_TYPE "Release" CACHE STRING "" FORCE)
endif()

project(gzdoom_android C CXX)

# Passed in by app/build.gradle
set(SDL2_SRC_DIR   "" CACHE PATH "SDL2 source tree")
set(OPENAL_SRC_DIR "" CACHE PATH "OpenAL Soft source tree")
set(GZDOOM_SRC_DIR "" CACHE PATH "GZDoom source tree")

foreach(v SDL2_SRC_DIR OPENAL_SRC_DIR GZDOOM_SRC_DIR)
    if(NOT EXISTS "${${v}}/CMakeLists.txt")
        message(FATAL_ERROR "${v} is not set correctly (got '${${v}}')")
    endif()
endforeach()

# Android cannot load versioned shared libraries (libfoo.so.1)
set(CMAKE_PLATFORM_NO_VERSIONED_SONAME ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# SDL2 (shared, loaded by org.libsdl.app.SDLActivity)
# ---------------------------------------------------------------------------
set(SDL_SHARED  ON  CACHE BOOL "" FORCE)
set(SDL_STATIC  OFF CACHE BOOL "" FORCE)
set(SDL_TEST    OFF CACHE BOOL "" FORCE)
set(SDL_TESTS   OFF CACHE BOOL "" FORCE)
set(SDL2_DISABLE_SDL2MAIN  ON CACHE BOOL "" FORCE)
set(SDL2_DISABLE_INSTALL   ON CACHE BOOL "" FORCE)
set(SDL2_DISABLE_UNINSTALL ON CACHE BOOL "" FORCE)
add_subdirectory(${SDL2_SRC_DIR} ${CMAKE_CURRENT_BINARY_DIR}/sdl2)

# GZDoom includes <SDL2/SDL.h>, but SDL's own tree has <SDL.h>: create forwarding headers.
set(SDL2_SHIM_DIR ${CMAKE_CURRENT_BINARY_DIR}/sdl2_shim)
file(GLOB SDL2_PUBLIC_HEADERS "${SDL2_SRC_DIR}/include/*.h")
foreach(h ${SDL2_PUBLIC_HEADERS})
    get_filename_component(hname ${h} NAME)
    file(WRITE ${SDL2_SHIM_DIR}/SDL2/${hname} "#include <${hname}>\n")
endforeach()

# ---------------------------------------------------------------------------
# OpenAL Soft (loaded by the engine with dlopen("libopenal.so"))
# ---------------------------------------------------------------------------
set(LIBTYPE SHARED CACHE STRING "" FORCE)
set(ALSOFT_UTILS          OFF CACHE BOOL "" FORCE)
set(ALSOFT_EXAMPLES       OFF CACHE BOOL "" FORCE)
set(ALSOFT_TESTS          OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL        OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_CONFIG OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_HRTF_DATA OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_AMBDEC_PRESETS OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_UTILS  OFF CACHE BOOL "" FORCE)
set(ALSOFT_NO_CONFIG_UTIL ON  CACHE BOOL "" FORCE)
set(ALSOFT_REQUIRE_OPENSL ON  CACHE BOOL "" FORCE)
set(ALSOFT_BACKEND_WAVE   OFF CACHE BOOL "" FORCE)
add_subdirectory(${OPENAL_SRC_DIR} ${CMAKE_CURRENT_BINARY_DIR}/openal)

# ---------------------------------------------------------------------------
# GZDoom itself (built as libgzdoom.so, entry point is SDL_main)
# ---------------------------------------------------------------------------
set(SDL2_INCLUDE_DIR "${SDL2_SHIM_DIR};${SDL2_SRC_DIR}/include" CACHE STRING "" FORCE)
set(SDL2_LIBRARY SDL2 CACHE STRING "" FORCE)
set(SDL2_LINK_DIR "" CACHE STRING "" FORCE)

set(HAVE_VULKAN OFF CACHE BOOL "" FORCE)      # OpenGL ES 2.0 renderer only
set(NO_GTK      ON  CACHE BOOL "" FORCE)
set(NO_OPENMP   ON  CACHE BOOL "" FORCE)
set(NO_STRIP    ON  CACHE BOOL "" FORCE)
set(DYN_OPENAL  ON  CACHE BOOL "" FORCE)
set(ZWIDGET_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)

# Never probe the host/system for these: always use the copies bundled in the gzdoom tree.
set(FORCE_INTERNAL_BZIP2  ON CACHE BOOL "" FORCE)
set(FORCE_INTERNAL_ZMUSIC ON CACHE BOOL "" FORCE)
set(FORCE_INTERNAL_CPPDAP ON CACHE BOOL "" FORCE)
set(CMAKE_DISABLE_FIND_PACKAGE_BZip2  ON)
set(CMAKE_DISABLE_FIND_PACKAGE_ZMusic ON)
set(CMAKE_DISABLE_FIND_PACKAGE_cppdap ON)
set(CMAKE_DISABLE_FIND_PACKAGE_VPX    ON)

add_subdirectory(${GZDOOM_SRC_DIR} ${CMAKE_CURRENT_BINARY_DIR}/gzdoom)


# Android has no libpthread (it is part of libc): drop "pthread" from the link
# interface of every target that asks for it (e.g. discord-rpc).
foreach(t zdoom discord-rpc cppdap zwidget webp lzma bz2 miniz zmusic)
    if(TARGET ${t})
        foreach(prop LINK_LIBRARIES INTERFACE_LINK_LIBRARIES)
            get_target_property(_libs ${t} ${prop})
            if(_libs)
                list(REMOVE_ITEM _libs pthread)
                set_property(TARGET ${t} PROPERTY ${prop} "${_libs}")
            endif()
        endforeach()
    endif()
endforeach()

set_target_properties(zdoom PROPERTIES OUTPUT_NAME gzdoom)
# Make sure libopenal.so is built and packaged next to libgzdoom.so
target_link_libraries(zdoom OpenAL)
target_link_libraries(zdoom log android)
