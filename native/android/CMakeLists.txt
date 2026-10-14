cmake_minimum_required(VERSION 3.22)
project(sunshine_android LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BOOST_INCLUDE_LIBRARIES asio atomic chrono filesystem system thread)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BOOST_ENABLE_CMAKE ON)
add_subdirectory(${BOOST_SOURCE_DIR} boost-build)
file(GLOB BOOST_HEADERS "${BOOST_SOURCE_DIR}/libs/*/include" "${BOOST_SOURCE_DIR}/libs/*/*/include")
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
add_subdirectory(${OPUS_SOURCE_DIR} opus-build)
add_subdirectory(${SUNSHINE_SOURCE_DIR}/third-party/moonlight-common-c/enet enet-build)
file(GLOB OPUS_HEADERS "${OPUS_SOURCE_DIR}/include/*.h")
file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/include/opus")
file(COPY ${OPUS_HEADERS} DESTINATION "${CMAKE_BINARY_DIR}/include/opus")
add_library(sunshine_core STATIC
    ${SUNSHINE_SOURCE_DIR}/src/config.cpp
    ${SUNSHINE_SOURCE_DIR}/src/network.cpp
    ${SUNSHINE_SOURCE_DIR}/src/crypto.cpp
    ${SUNSHINE_SOURCE_DIR}/src/stream.cpp
    ${SUNSHINE_SOURCE_DIR}/src/rtsp.cpp
    ${SUNSHINE_SOURCE_DIR}/src/nvhttp.cpp
    ${SUNSHINE_SOURCE_DIR}/src/input.cpp
    ${SUNSHINE_SOURCE_DIR}/src/audio.cpp
    ${SUNSHINE_SOURCE_DIR}/src/globals.cpp
    ${SUNSHINE_SOURCE_DIR}/src/file_handler.cpp
    ${SUNSHINE_SOURCE_DIR}/src/stat_trackers.cpp
    ${SUNSHINE_SOURCE_DIR}/third-party/moonlight-common-c/src/RtspParser.c
    ${SUNSHINE_SOURCE_DIR}/third-party/nanors/rs.c)
target_include_directories(sunshine_core PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${SUNSHINE_SOURCE_DIR} ${SUNSHINE_SOURCE_DIR}/src
    ${SUNSHINE_SOURCE_DIR}/third-party
    ${SUNSHINE_SOURCE_DIR}/third-party/moonlight-common-c/enet/include
    ${SUNSHINE_SOURCE_DIR}/third-party/nanors/deps/obl
    ${SUNSHINE_SOURCE_DIR}/third-party/nanors
    ${SUNSHINE_SOURCE_DIR}/third-party/nv-codec-headers/include
    ${CMAKE_BINARY_DIR}/include
    ${SSL_PREFIX}/include
    ${BOOST_HEADERS}
    ${OPUS_SOURCE_DIR}/include)
target_compile_definitions(sunshine_core PUBLIC SUNSHINE_ASSETS_DIR="/data/local/tmp" SUNSHINE_TRAY=0)
target_link_libraries(sunshine_core PUBLIC enet opus Boost::asio Boost::filesystem Boost::thread ${SSL_PREFIX}/lib/libssl.so ${SSL_PREFIX}/lib/libcrypto.so android log mediandk)
add_library(sunshine SHARED host.cpp platform.cpp codec.cpp)
target_link_libraries(sunshine PRIVATE sunshine_core)
target_link_options(sunshine PRIVATE -Wl,--no-undefined)
if(SUNSHINE_ANDROID_TESTS)
    foreach(test native_audio video_policy network_policy fresh_video_queue encoder_selection audio_packetizer)
        add_executable(${test}_test ../tests/${test}_test.cpp)
        target_include_directories(${test}_test PRIVATE ..)
        target_compile_options(${test}_test PRIVATE -UNDEBUG)
    endforeach()
endif()
