#!/usr/bin/env bash
set -euo pipefail
adb wait-for-device
adb reverse tcp:18082 tcp:18082
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Synthetic one-use trial code only; no real user, secret or payments.
code="$(python3 - <<'PY'
import os,json,urllib.request
from pathlib import Path
from uuid import uuid4
token=(Path(os.environ["RUNNER_TEMP"])/"ventry-admin-token").read_text().strip()
def post(path,body):
    q=urllib.request.Request("http://127.0.0.1:18081/api/v1"+path,data=json.dumps(body).encode(),headers={"Content-Type":"application/json","Authorization":"Bearer "+token})
    with urllib.request.urlopen(q,timeout=10) as r:return json.load(r)
sid=str(uuid4())
post("/subscriptions",{"id":sid,"customer_id":str(uuid4()),"product":"raschet"})
print(post("/subscriptions/"+sid+"/activations",{"event_id":str(uuid4()),"trial":True})["code"])
PY
)"
adb shell am instrument -w -e class dev.ventry.raschet.LicensedStagingTest -e activation "$code" \
  dev.ventry.raschet.licensedstaging.test/androidx.test.runner.AndroidJUnitRunner | tee "$RUNNER_TEMP/licensed-staging-test.txt"
grep -Eq 'OK \(1 test\)' "$RUNNER_TEMP/licensed-staging-test.txt"
echo RASCHET_REAL_SIGNED_ACTIVATION_LEDGER_SAFETY_PASS
