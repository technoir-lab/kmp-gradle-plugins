cmake_minimum_required(VERSION 3.23)

project(hello LANGUAGES C CXX)

if(ANDROID)
    include(CheckLinkerFlag)
    check_linker_flag(
        CXX
        "LINKER:--cmake-import-invalid-option"
        HELLO_ANDROID_LINKER_ACCEPTS_INVALID_OPTION
    )
    if(HELLO_ANDROID_LINKER_ACCEPTS_INVALID_OPTION)
        message(FATAL_ERROR "Android linker probe accepted an invalid option")
    endif()
    if(CMAKE_IMPORT_VERIFY_COMPILER_FLAGS_AT_LINK)
        include(CheckCXXSourceCompiles)
        set(HELLO_SAVED_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
        string(APPEND CMAKE_CXX_FLAGS " -fsanitize=undefined")
        check_cxx_source_compiles(
            "int main(void) { volatile int zero = 0; return 1 / zero; }"
            HELLO_ANDROID_COMPILER_FLAGS_REACH_LINKER
        )
        set(CMAKE_CXX_FLAGS "${HELLO_SAVED_CXX_FLAGS}")
        unset(HELLO_SAVED_CXX_FLAGS)
        if(NOT HELLO_ANDROID_COMPILER_FLAGS_REACH_LINKER)
            message(FATAL_ERROR "CMAKE_CXX_FLAGS did not reach the Android linker")
        endif()
    endif()
endif()

add_library(hello STATIC
    src/hello_debug.c
    src/hello.cpp
)

target_include_directories(hello
    PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
    PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src"
)

target_sources(hello
    PUBLIC
        FILE_SET HEADERS
        BASE_DIRS include
        FILES
            include/hello.h
            include/nested/world.h
    PRIVATE
        src/hello_impl.h
)

target_compile_definitions(hello PRIVATE
    $<$<CONFIG:Debug>:HELLO_DEBUG>
)

install(TARGETS hello
    ARCHIVE DESTINATION lib COMPONENT hello-static
    FILE_SET HEADERS DESTINATION include COMPONENT hello-static
)

if(ANDROID)
    set(HELLO_CXX_LIBRARIES "-lc++_static -lc++abi")
elseif(APPLE)
    set(HELLO_CXX_LIBRARIES "-lc++")
else()
    set(HELLO_CXX_LIBRARIES "-lstdc++")
endif()
configure_file(hello.pc "${CMAKE_CURRENT_BINARY_DIR}/hello.pc" @ONLY)

install(FILES "${CMAKE_CURRENT_BINARY_DIR}/hello.pc" DESTINATION lib/pkgconfig COMPONENT hello-static)

# Model projects that install a source file beside public headers.
install(FILES src/hello.cpp DESTINATION include COMPONENT hello-static)
