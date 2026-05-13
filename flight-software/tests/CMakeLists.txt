cmake_minimum_required(VERSION 3.15)

project(flight_software_tests C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_executable(run_control_tests
    test_control_math.c
    ../main/flight_control_math.c
)

target_include_directories(run_control_tests
    PRIVATE
        ../main
)

target_link_libraries(run_control_tests
    m
)

