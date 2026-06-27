cmake_minimum_required(VERSION 3.20.0)

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(ble_gesture_central)

# Stage 1: BLE link verification only — main.cc is plain C.
# When you re-enable TFLM (Stage 2), add src/model_data.cc back here.
target_sources(app PRIVATE
    src/main.cc
    src/model_data.cc
)

target_include_directories(app PRIVATE src)
target_compile_options(app PRIVATE -Wall -Wextra)
