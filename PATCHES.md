# Изменения в исходниках GZDoom (gzdoom/)

CMake
- `CMakeLists.txt`: Android-ветки: без импорта хост-утилит, без tools/wadsrc*, JIT выключен, без PIE/fts,
  libvpx заменён заглушкой `libraries/vpx_stub`.
- `src/CMakeLists.txt`: SDL2 берётся из обёртки, `zdoom` собирается как SHARED (`libgzdoom.so`),
  вместо lemon/re2c используются готовые файлы из `src/android_gen/`, без crashcatcher, +android_support.cpp.
- `libraries/ZWidget/CMakeLists.txt`: Android без X11/Wayland/SDL-бэкенда.

C++
- `i_main.cpp`: `-androidhome <dir>` -> `HOME` и `progdir` (= `<dir>/gzdoom/`), без crashcatcher.
- `i_system.cpp`: окно ошибок через SDL, вывод в logcat, без лаунчера/HoldingQueryKey.
- `sdlglvideo.cpp`: всегда контекст GLES 2.0, окно на весь экран.
- `hardware.cpp`: включены SDL_TEXTINPUT события (экранная клавиатура).
- `v_video.cpp`: бэкенд всегда 2 (GLES).
- `oalsound.cpp`: `libopenal.so` для Android.
- `d_main.cpp`: без создания ZWidget backend.
- `android_support.cpp` (новый): JNI `nativeUiState()` для сенсорного оверлея.
