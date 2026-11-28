#!/usr/bin/env bash
set -euo pipefail
adb wait-for-device
adb reverse tcp:18444 tcp:18444
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Synthetic one-use trial code only; no real user, secret or payments.
code="$(python3 - <<'PY'
import os,json,urllib.request,ssl
from pathlib import Path
from uuid import uuid4
state=Path(os.environ["RUNNER_TEMP"])/"raschet-private-state"
token=(state/"secrets/admin-token").read_text().strip()
ctx=ssl.create_default_context(cafile=str(state/"tls/ca.pem"))
def post(path,body):
    q=urllib.request.Request("https://127.0.0.1:18443/api/v1"+path,data=json.dumps(body).encode(),headers={"Content-Type":"application/json","Authorization":"Bearer "+token})
    with urllib.request.urlopen(q,context=ctx,timeout=10) as r:return json.load(r)
token=post("/session",{})["session_token"]
sid=str(uuid4())
post("/subscriptions",{"id":sid,"customer_id":str(uuid4()),"product":"raschet"})
print(post("/subscriptions/"+sid+"/activations",{"event_id":str(uuid4()),"trial":True})["code"])
post("/session/logout",{})
PY
)"
adb shell am instrument -w -e class dev.ventry.raschet.LicensedStagingTest -e activation "$code" \
  dev.ventry.raschet.licensedstaging.test/androidx.test.runner.AndroidJUnitRunner | tee "$RUNNER_TEMP/licensed-staging-test.txt"
grep -Eq 'OK \(1 test\)' "$RUNNER_TEMP/licensed-staging-test.txt"
echo RASCHET_REAL_SIGNED_ACTIVATION_LEDGER_SAFETY_PASS

# Exercise restoration using a real Android Bundle on the same emulator.
adb shell am instrument -w -e class dev.ventry.raschet.presentation.BookFiltersRestoreTest \
  dev.ventry.raschet.test/androidx.test.runner.AndroidJUnitRunner | tee "$RUNNER_TEMP/raschet-filter-restore-test.txt"
grep -Eq 'OK \(2 tests\)' "$RUNNER_TEMP/raschet-filter-restore-test.txt"
echo RASCHET_FILTER_RESTORE_PASS
