#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [ -z "$ndk" ]; then
    echo "Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT to an installed Android NDK." >&2
    exit 1
fi

build_dir="$root/build"
package_dir="$build_dir/package"
dist_dir="$root/dist"

cmake -S "$root" -B "$build_dir" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM="${ANDROID_PLATFORM:-android-24}" \
    -DCMAKE_BUILD_TYPE=Release
cmake --build "$build_dir"

mkdir -p "$package_dir" "$dist_dir"
cp "$root/manifest.json" "$package_dir/manifest.json"
cp "$build_dir/libherocine_native.so" "$package_dir/libherocine_native.so"
(cd "$package_dir" && zip -q -r -FS "$dist_dir/herocine_native.levipack" .)
echo "Created $dist_dir/herocine_native.levipack"
