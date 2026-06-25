cmake_minimum_required(VERSION 3.22.1)
project(futo_swipe_plugin LANGUAGES CXX)

add_library(futo_swipe_trie_bridge SHARED
    FutoSwipeTrieBridge.cpp
)

target_compile_features(futo_swipe_trie_bridge PRIVATE cxx_std_17)
target_link_libraries(futo_swipe_trie_bridge PRIVATE log)
