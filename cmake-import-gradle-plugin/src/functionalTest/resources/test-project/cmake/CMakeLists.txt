cmake_minimum_required(VERSION 3.23)

project(hello LANGUAGES C)

if(ANDROID)
    enable_language(CXX)
    include(CheckLinkerFlag)
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/hello.map" "{ global: hello; local: *; };")
    check_linker_flag(
        C
        "LINKER:--version-script=${CMAKE_CURRENT_BINARY_DIR}/hello.map"
        HELLO_ANDROID_LINKER_ACCEPTS_VERSION_SCRIPT
    )
    check_linker_flag(
        C
        "LINKER:--cmake-import-invalid-option"
        HELLO_ANDROID_LINKER_ACCEPTS_INVALID_OPTION
    )
    if(NOT HELLO_ANDROID_LINKER_ACCEPTS_VERSION_SCRIPT)
        message(FATAL_ERROR "Android linker rejected a supported version script")
    endif()
    if(HELLO_ANDROID_LINKER_ACCEPTS_INVALID_OPTION)
        message(FATAL_ERROR "Android linker probe accepted an invalid option")
    endif()
    if(CMAKE_IMPORT_VERIFY_COMPILER_FLAGS_AT_LINK)
        include(CheckCSourceCompiles)
        check_c_source_compiles(
            "int main(void) { volatile int zero = 0; return 1 / zero; }"
            HELLO_ANDROID_COMPILER_FLAGS_REACH_LINKER
        )
        if(NOT HELLO_ANDROID_COMPILER_FLAGS_REACH_LINKER)
            message(FATAL_ERROR "CMAKE_C_FLAGS did not reach the Android linker")
        endif()
    endif()
endif()

add_library(hello STATIC
    src/hello.c
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

install(FILES hello.pc DESTINATION lib/pkgconfig COMPONENT hello-static)

# Model projects that install a source file beside public headers.
install(FILES src/hello.c DESTINATION include COMPONENT hello-static)
