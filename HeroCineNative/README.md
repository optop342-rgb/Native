# HeroCine Native

An independent native HeroCine-style path editor for LeviLauncher. Its UI is
Dear ImGui using the OpenGL ES 3 backend, with touch input from the documented
Preloader API. The ReplayCraft addon manifest credits DarkBlock Gaming and
Pete9xi; this project does not copy its JavaScript or assets.

## What works

- A Dear ImGui editor for a two-point camera path, preset label, yaw, FOV, and duration.
- Smoothstep position/FOV interpolation and shortest-path angle interpolation.
- Touch input routed through Preloader's documented touch callback.
- Preview playback, timeline scrubber, and config-backed save/load.
- A launcher floating button to open the editor.
- Lifecycle registration and `.levipack` packaging metadata.

## Important limitation

The editor renders immediately before the game's `eglSwapBuffers` call using
the public Preloader function-hook API. It does not patch guessed
`libminecraftpe.so` offsets. The path preview animates inside the editor, but
does not move the in-game camera: the workspace SDK does not document a camera
control API. Camera playback still needs a supported camera API. The first
eligible EGL surface/context is used; test this on the target launcher and
device before relying on it.

Preloader's documented touch API has no unregister function. The mod disables
its input callback when inactive and refuses dynamic unload after registering
it, avoiding a dangling callback into unloaded code.

## Build

Build and package on an Android NDK environment with CMake, Ninja, `zip`, and
network access for the pinned Preloader and Dear ImGui source dependencies:

```sh
ANDROID_NDK_HOME=/path/to/ndk sh build.sh
```

The script creates `dist/herocine_native.levipack`. Set `ANDROID_PLATFORM` to
the minimum Android API supported by the target device if needed.

## Build from GitHub Actions

After the workflow and project files are committed and pushed, open the
repository's **Actions** tab, choose **build-herocine-native**, then select
**Run workflow**. Download `herocine-native-levipack` from the completed run's
artifacts. Changes under `HeroCineNative/` also trigger a build on push.

The platform-independent path interpolation can be checked on a host:

```sh
clang++ -std=c++20 -Wall -Wextra -Werror tests/path_math_test.cpp \
  -o /tmp/herocine-path-test
/tmp/herocine-path-test
```
