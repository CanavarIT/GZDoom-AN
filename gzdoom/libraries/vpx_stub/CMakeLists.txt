cmake_minimum_required( VERSION 3.16 )
# Stub of libvpx used only for the Android port (no real VP8/VP9 decoding).
add_library( vpxstub STATIC vpx_stub.c )
target_include_directories( vpxstub PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include )
set_target_properties( vpxstub PROPERTIES POSITION_INDEPENDENT_CODE ON )
