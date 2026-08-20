cmake_minimum_required(VERSION 3.23)

project(hello LANGUAGES C)

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
        FILES include/hello.h
    PRIVATE
        src/hello_impl.h
)

target_compile_definitions(hello PRIVATE
    $<$<CONFIG:Debug>:HELLO_DEBUG>
)

install(TARGETS hello
    ARCHIVE DESTINATION lib COMPONENT third-party-devel
    FILE_SET HEADERS DESTINATION include COMPONENT third-party-devel
)

# Model projects that install a source file beside public headers.
install(FILES src/hello.c DESTINATION include COMPONENT third-party-devel)
