cmake_minimum_required(VERSION 3.0)

# 获取git hash

message(STATUS "CMAKE_CURRENT_SOURCE_DIR= ${CMAKE_CURRENT_SOURCE_DIR}")

# Try to get git hash from main repo if submodules don't exist
execute_process(
  COMMAND git log -1 --format=%H   
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} 
  OUTPUT_VARIABLE COMMIT_HASH
  ERROR_QUIET
  RESULT_VARIABLE GIT_RESULT
)

if(GIT_RESULT EQUAL 0)
    string(REPLACE "\n" ";" COMMIT_HASH "${COMMIT_HASH}")
    list(GET COMMIT_HASH 0 COMMIT_HASH)
    string(REPLACE "\n" "" COMMIT_HASH "${COMMIT_HASH}")
    string(REPLACE "\t" "" COMMIT_HASH "${COMMIT_HASH}")
    string(REPLACE "\r" "" COMMIT_HASH "${COMMIT_HASH}")
    string(SUBSTRING ${COMMIT_HASH} 0 7 COMMIT_HASH)
else()
    set(COMMIT_HASH "unknown")
endif()
message(STATUS "git hash= ${COMMIT_HASH}")

# 获取分支名称
execute_process(
  COMMAND git branch --show-current
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} 
  OUTPUT_VARIABLE CURRENT_BRANCH
  ERROR_QUIET
  RESULT_VARIABLE BRANCH_RESULT
)

if(BRANCH_RESULT EQUAL 0)
    string(REPLACE "\n" "" CURRENT_BRANCH "${CURRENT_BRANCH}")
    string(REPLACE "\t" "" CURRENT_BRANCH "${CURRENT_BRANCH}")
    string(REPLACE "\r" "" CURRENT_BRANCH "${CURRENT_BRANCH}")
else()
    set(CURRENT_BRANCH "unknown")
endif()
message(STATUS "git current branch = ${CURRENT_BRANCH}")

# 获取生成时间
string(TIMESTAMP COMPILE_TIME "%y%m%d")
string(REPLACE "\n" "" COMPILE_TIME "${COMPILE_TIME}")
string(REPLACE "\t" "" COMPILE_TIME "${COMPILE_TIME}")
string(REPLACE "\r" "" COMPILE_TIME "${COMPILE_TIME}")
string(REPLACE "\"" "" COMPILE_TIME "${COMPILE_TIME}")

# 生成版本信息
set(GIT_VERSION "${CURRENT_BRANCH}_${COMMIT_HASH}_${COMPILE_TIME}")
message(STATUS "version info = ${GIT_VERSION}")

# 确保非空并去除空格
if(GIT_VERSION STREQUAL "")
    set(GIT_VERSION "unknown_unknown_${COMPILE_TIME}")
endif()
# Simple string replacement instead of regex
string(REPLACE " " "" GIT_VERSION "${GIT_VERSION}")

# 保存变量到文件
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/version.h.in ${CMAKE_CURRENT_SOURCE_DIR}/version.h @ONLY)

project(clash-bridge C)


set(CMAKE_POSITION_INDEPENDENT_CODE on)
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")

set(GO_OUTPUT_BASE ${GO_OUTPUT}/${FLAVOR_NAME})

if ("${CMAKE_BUILD_TYPE}" STREQUAL "Debug")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Debug")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "Release")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "RelWithDebInfo")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
else ()
    message(FATAL_ERROR "Unknown build type ${CMAKE_BUILD_TYPE}")
endif ()

include_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")
include_directories("${GO_SOURCE}")

link_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")

add_library(bridge SHARED main.c jni_helper.c bridge_helper.c)
target_link_libraries(bridge log clash)
target_link_options(bridge PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
