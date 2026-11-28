#!/usr/bin/env bash
set -euo pipefail

# Deliberately limited to the Linux x86_64 CI toolchain.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ndk="${ANDROID_NDK_HOME:-$sdk/ndk/28.2.13676358}"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
out="$root/android/app/build/generated/rustPreview/jniLibs"
assets="$root/android/app/build/generated/rustPreview/testAssets"
target_dir="$root/rust-core/jni/target/android"
manifest="$root/rust-core/jni/Cargo.toml"
symbols=(Java_dev_ventry_raschet_bridge_NativeCorrection_correctedBalance Java_dev_ventry_raschet_bridge_NativeLedger_compute)
test -x "$toolchain/llvm-readelf"
test -x "$toolchain/llvm-nm"
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*28\.2\.13676358[[:space:]]*$' "$ndk/source.properties"
mkdir -p "$out" "$assets"

for item in \
    'arm64-v8a:aarch64-linux-android:aarch64-linux-android:AArch64' \
    'armeabi-v7a:armv7-linux-androideabi:armv7a-linux-androideabi:ARM' \
    'x86:i686-linux-android:i686-linux-android:Intel 80386' \
    'x86_64:x86_64-linux-android:x86_64-linux-android:Advanced Micro Devices X86-64'
do
    IFS=: read -r abi target clang machine <<< "$item"
    linker="$toolchain/${clang}26-clang"
    test -x "$linker"
    key="CARGO_TARGET_$(printf '%s' "$target" | tr '[:lower:]-' '[:upper:]_')_LINKER"
    rustup target add --toolchain 1.85.1 "$target"
    env "$key=$linker" cargo +1.85.1 build --manifest-path "$manifest" \
        --target-dir "$target_dir" --target "$target" --release --locked
    so="$target_dir/$target/release/libraschet_jni.so"
    test -s "$so"
    actual=$("$toolchain/llvm-readelf" -h "$so" | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p')
    test "$actual" = "$machine" || { echo "Wrong ELF machine: $abi: $actual" >&2; exit 1; }
    for symbol in "${symbols[@]}"; do
        "$toolchain/llvm-nm" -D --defined-only "$so" | awk -v symbol="$symbol" '$NF == symbol { found=1 } END { exit !found }'
    done
    # Check actual ELF LOAD segment alignment, not just NDK version.
    if [[ "$abi" == arm64-v8a || "$abi" == x86_64 ]]; then
        "$toolchain/llvm-readelf" -lW "$so" | python3 -c 'import sys; align=[int(l.split()[-1],16) for l in sys.stdin if l.strip().startswith("LOAD ")]; assert align and min(align)>=16384, align'
    fi
    install -D -m 644 "$so" "$out/$abi/libraschet_jni.so"
done
install -m 644 "$root/tests/contracts/correction.tsv" "$assets/correction.tsv"
printf 'RUST_ANDROID_ABI_BUILD_OK\n'
