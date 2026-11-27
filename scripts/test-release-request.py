#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("resolve-release-request.py")
spec = importlib.util.spec_from_file_location("resolver", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ReleaseRequestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        gradle = self.root / "android/app/build.gradle.kts"
        gradle.parent.mkdir(parents=True)
        gradle.write_text('versionCode = 35\nversionName = "0.30.0-beta.1"\n')
        self.request = {"tag":"raschet-v0.30.0-beta.1-rc.1","versionName":"0.30.0-beta.1","versionCode":35,"notes":"QA"}
        self.write()

    def write(self):
        (self.root / "android/release-request.json").write_text(json.dumps(self.request))

    def test_explicit_main_release(self):
        self.assertEqual(module.resolve(self.root,"refs/heads/main"), (self.request["tag"],35))

    def test_tag_build(self):
        self.assertEqual(module.resolve(self.root,"refs/tags/raschet-v0.30.0-beta.1-rc.2"), ("raschet-v0.30.0-beta.1-rc.2",35))

    def test_other_branches_rejected(self):
        with self.assertRaises(ValueError):
            module.resolve(self.root,"refs/heads/feature")

    def test_mismatch_and_injection_rejected(self):
        for key,value in [("tag","raschet-v0.30.0-beta.1-rc.0"),("tag","raschet-v0.30.0-beta.1-rc.1\nbad=true"),("versionCode",34),("versionName","0.29.0-beta.1"),("notes","")]:
            with self.subTest(key=key,value=value):
                original=self.request[key]
                self.request[key]=value
                self.write()
                with self.assertRaises(ValueError):
                    module.resolve(self.root,"refs/heads/main")
                self.request[key]=original
                self.write()

if __name__ == "__main__":
    unittest.main()
