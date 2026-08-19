cmake_minimum_required(VERSION 3.22)
project(llama_android CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Исходники llama.cpp подключаются подмодулем: копировать их в репозиторий
# значило бы тащить двести мегабайт чужого кода и терять связь с апстримом.
set(LLAMA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_ROOT}/CMakeLists.txt)
    message(FATAL_ERROR "Нет исходников llama.cpp: выполните git submodule update --init")
endif()

add_subdirectory(${LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

add_library(llama-android SHARED llama_bridge.cpp)

target_link_libraries(llama-android
    llama

    android
    log
)
