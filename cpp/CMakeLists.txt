cmake_minimum_required(VERSION 3.20)
project(stage1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    add_compile_options(-Wall -Wextra)
endif()

find_package(PkgConfig REQUIRED)
pkg_check_modules(DEPENDENCIES REQUIRED IMPORTED_TARGET mongoc2 libutf8proc sqlite3 libcurl zlib)
find_package(nlohmann_json REQUIRED)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)

add_library(stage1
    src/datalake.cpp
    src/files.cpp
    src/gutenberg.cpp
    src/index.cpp
    src/metadata.cpp
    src/tokenizer.cpp
    src/unicode.cpp
    src/utf8.cpp)
target_include_directories(stage1 PUBLIC src)
target_compile_definitions(stage1 PUBLIC STAGE1_REPO_ROOT="${REPO_ROOT}")
target_link_libraries(stage1 PUBLIC PkgConfig::DEPENDENCIES nlohmann_json::nlohmann_json)
if(WIN32)
    target_link_libraries(stage1 PUBLIC psapi)
endif()

add_executable(stage1_benchmarks
    benchmarks/main.cpp
    benchmarks/books.cpp
    benchmarks/harness.cpp
    benchmarks/datalake_benchmarks.cpp
    benchmarks/index_benchmarks.cpp
    benchmarks/metadata_benchmarks.cpp)
target_include_directories(stage1_benchmarks PRIVATE benchmarks)
target_link_libraries(stage1_benchmarks PRIVATE stage1)

add_executable(stage1_tests tests/tests.cpp)
target_link_libraries(stage1_tests PRIVATE stage1)

enable_testing()
add_test(NAME stage1_tests COMMAND stage1_tests)
