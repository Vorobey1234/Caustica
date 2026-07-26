cmake_minimum_required(VERSION 3.24)
project(caustica_nrd LANGUAGES CXX)

if(NOT DEFINED NRD_SDK_DIR OR NOT DEFINED NRI_INCLUDE_DIR)
    message(FATAL_ERROR "NRD_SDK_DIR and NRI_INCLUDE_DIR are required")
endif()

add_library(caustica_nrd SHARED caustica_nrd.cpp)
target_compile_features(caustica_nrd PRIVATE cxx_std_17)
target_compile_definitions(caustica_nrd PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
target_include_directories(caustica_nrd PRIVATE
    "${NRD_SDK_DIR}/Include"
    "${NRD_SDK_DIR}/Integration"
    "${NRI_INCLUDE_DIR}"
)
target_link_libraries(caustica_nrd PRIVATE
    "${NRD_SDK_DIR}/_Bin/Release/NRD.lib"
    "${NRD_SDK_DIR}/_Bin/Release/NRI.lib"
)

