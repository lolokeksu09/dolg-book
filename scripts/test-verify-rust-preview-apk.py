"""Synthetic ZIP/ELF-header cases for the native APK packaging gate."""
import importlib.util
import struct
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("verify", Path(__file__).with_name("verify-rust-preview-apk.py"))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


def elf_header(elf_class, machine, kind=3):
    return b"\x7fELF" + bytes([elf_class, 1]) + bytes(10) + struct.pack("<HH", kind, machine)


class PreviewVerifierTest(unittest.TestCase):
    def test_synthetic_apk_cases(self):
        rows = {f"lib/{abi}/libraschet_jni.so": elf_header(c, m)
                for abi, (c, m) in verify.MACHINES.items()}
        first = next(iter(rows))
        cases = [
            ("valid", list(rows.items()), True),
            ("missing", list(rows.items())[1:], False),
            ("duplicate", list(rows.items()) + [(first, rows[first])], False),
            ("wrong architecture", [(k, elf_header(1, 3) if k == first else v) for k, v in rows.items()], False),
            ("truncated header", [(k, b"short" if k == first else v) for k, v in rows.items()], False),
            ("not shared ELF", [(k, elf_header(2, 183, 2) if k == first else v) for k, v in rows.items()], False),
        ]
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "synthetic.apk"
            for label, entries, valid in cases:
                with self.subTest(label=label), warnings.catch_warnings():
                    warnings.filterwarnings("ignore", message="Duplicate name")
                    with zipfile.ZipFile(path, "w") as archive:
                        for name, value in entries:
                            archive.writestr(name, value)
                    if valid:
                        verify.verify(path)
                    else:
                        with self.assertRaises(ValueError):
                            verify.verify(path)


if __name__ == "__main__":
    unittest.main()
