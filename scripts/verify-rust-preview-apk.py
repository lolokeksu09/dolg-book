"""Fail closed if any supported ABI or packaged ELF identity is missing."""
import struct
import sys
import zipfile

MACHINES = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}


def verify(path):
    with zipfile.ZipFile(path) as apk:
        expected = {f"lib/{abi}/libraschet_jni.so" for abi in MACHINES}
        names = apk.namelist()
        actual = [name for name in names if name.startswith("lib/") and name.endswith("/libraschet_jni.so")]
        if set(actual) != expected or len(actual) != len(expected):
            raise ValueError(f"Invalid Rust library set: {actual}")
        for abi, (elf_class, machine) in MACHINES.items():
            name = f"lib/{abi}/libraschet_jni.so"
            with apk.open(name) as stream:
                header = stream.read(20)
            if (len(header) != 20 or header[:4] != b"\x7fELF" or header[4:6] != bytes([elf_class, 1])
                    or struct.unpack_from("<HH", header, 16) != (3, machine)):
                raise ValueError(f"Invalid shared ELF for {abi}")
    print("RUST_PREVIEW_APK_ABIS_OK")


if __name__ == "__main__":
    verify(sys.argv[1])
