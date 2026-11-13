# GZDoom-AN

GZDoom ported to Android. Package: `com.az.doom`.

No ads, no accounts, no network code added. It runs the engine and nothing else.

## What this is

- GZDoom (current master) built as `libgzdoom.so` and started through SDL2's `SDLActivity`.
- OpenGL ES 2.0 renderer only. Vulkan and the VM JIT are disabled.
- Sound through OpenAL Soft (OpenSL ES backend).
- A small Java launcher: pick a game, pick mods, add arguments, press Play.
- Touch controls with a built-in layout editor.
- Gamepads, keyboards and mice work through SDL.

## What this is not

- It does not ship any game data. You bring your own `doom2.wad`, `doom.wad`, Freedoom, etc.
- It does not play VP8/VP9 video. libvpx is replaced by a stub. Mods that depend on it will not show their cutscenes.
- It does not use FluidSynth. The bundled one needs glib, which Android does not have. MIDI goes through the other built-in synths.
- It is not an official GZDoom release and has no connection to the GZDoom team.

## Requirements

| Item | Version |
|------|---------|
| Android | 7.0+ (API 24), arm64 |
| NDK | 29.0.14033849 |
| CMake | 3.31.6 |
| Gradle | 8.9 (AGP 8.7.3) |
| JDK | 17 |

Only `arm64-v8a` is built by default. Add other ABIs in `app/build.gradle` (`abiFilters`) at your own risk; they are untested.

## Build

1. Clone the repository.
2. Install the NDK and CMake versions above.
3. Run `gradle assembleDebug` (or build from Android Studio / AndroidIDE).

On the first build Gradle downloads SDL2 2.30.11 and OpenAL Soft 1.23.1 into `app/.deps/`. That needs internet. For an offline build, unpack them there yourself:

```
app/.deps/SDL2-2.30.11/
app/.deps/openal-soft-1.23.1/
```

If a download is interrupted, delete the folder in `app/.deps/` and build again. A half-unpacked archive is detected and removed automatically.

The first native build takes a long time. Later builds only recompile what changed. Do not delete `app/.cxx/` unless you have to.

`gradle-wrapper.jar` is not in the repository. Use your own Gradle 8.9 or let the IDE fetch it from `gradle-wrapper.properties`.

## Use

1. Open the app, press **Add WADs / mods** and select your files. They are copied to `Android/data/com.az.doom/files/wads/`.
2. **GAMES**: pick one base game (IWAD). The cover is read from the WAD itself.
3. **MODS**: tick the add-ons you want. They are loaded in alphabetical order.
4. **Args**: any GZDoom command line, for example `+map map05 -skill 3`.
5. Press **Play**.

## Controls

In game: stick on the left, fire / use / jump / crouch on the right, weapon previous and next, map, escape, keyboard, gear.

Hold **Fire** and drag the same finger to turn the view. Dragging anywhere else also turns the view.

In menus: arrows, enter, escape. Everything else is a normal tap.

The gear opens the layout editor: move, resize, delete and restore buttons. **THROW OFF** restores the default layout. The layout is stored on the device and survives restarts.

The Android back button acts as Escape.

## Known problems

- Never built or tested on anything except arm64 phones.
- A crash in the engine kills the `:game` process and returns you to the launcher.
- The accelerometer is disabled as a joystick on purpose. Tilting the phone does nothing.
- Large mods that need a lot of memory can be killed by Android.

## Repository layout

```
app/                    Android app: Java launcher, touch controls, Gradle config
app/src/main/cpp/       CMake wrapper (SDL2 + OpenAL Soft + GZDoom)
gzdoom/                 GZDoom source with the Android changes
gzdoom/libraries/vpx_stub/   libvpx replacement
```

## License

GZDoom is licensed under the GNU GPL v3. This project is distributed under the same terms. See `LICENSE`.

Bundled third-party libraries keep their own licenses (see `gzdoom/libraries/` and the files inside the GZDoom tree). SDL2 (zlib) and OpenAL Soft (LGPL) are downloaded at build time and are not stored here.

Doom, Heretic, Hexen, Strife and other game data belong to their owners. None of it is included.
