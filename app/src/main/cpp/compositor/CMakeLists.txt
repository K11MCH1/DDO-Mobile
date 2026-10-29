set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Builds the static adrenotools library plus the hook libraries it loads at run time from the APK's lib dir.
add_subdirectory(adrenotools)

add_library(wayland-server SHARED IMPORTED)
set_target_properties(wayland-server PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/lib/libwayland-server.so)
add_library(ffi SHARED IMPORTED)
set_target_properties(ffi PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/lib/libffi.so)

add_library(ddocompositor SHARED
        src/compositor.c
        src/wl_dmabuf.c
        src/wl_single_pixel.c
        src/keymap.c
        src/vsync.c
        src/vk_present.c
        src/vk_loader.c
        src/blend_pass.c
        src/jni.c
        src/ddo_log.c
        generated/xdg-shell-protocol.c
        generated/linux-dmabuf-v1-protocol.c
        generated/viewporter-protocol.c
        generated/presentation-time-protocol.c
        generated/pointer-constraints-unstable-v1-protocol.c
        generated/relative-pointer-unstable-v1-protocol.c
        generated/single-pixel-buffer-v1-protocol.c
)
target_include_directories(ddocompositor PRIVATE src generated prebuilt/include)
target_compile_options(ddocompositor PRIVATE -O2 -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers)
target_compile_definitions(ddocompositor PRIVATE DDO_KEYMAP_PATH="${CMAKE_CURRENT_SOURCE_DIR}/keymap.xkb")
set_source_files_properties(src/keymap.c PROPERTIES OBJECT_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/keymap.xkb)
target_link_libraries(ddocompositor wayland-server ffi adrenotools android log dl m)
target_link_options(ddocompositor PRIVATE -Wl,--exclude-libs,ALL)
