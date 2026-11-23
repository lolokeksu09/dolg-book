import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';

const root = resolve(import.meta.dirname, '..');
const read = path => readFileSync(resolve(root, path), 'utf8');

test('Android app packages the real offline ledger, not an external web redirect', () => {
  const gradle = read('android/app/build.gradle.kts');
  for (const filename of ['index.html','style.css','app.js','ledger.js']) {
    assert.ok(gradle.includes('"' + filename + '"'), filename + ' not bundled');
  }
  assert.ok(gradle.includes('syncOfflineBook'));
  assert.ok(!gradle.includes('project-source.zip"'));
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(activity.includes('WebViewAssetLoader.AssetsPathHandler'));
  assert.ok(activity.includes('webView.loadUrl(APP_URL)'));
  assert.ok(activity.includes('startActivityForResult(picker, OPEN_JSON)'));
  assert.ok(activity.includes('Intent.ACTION_CREATE_DOCUMENT'));
  assert.ok(activity.includes('setAllowFileAccess(false)'));
  assert.ok(activity.includes('setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW)'));
  assert.ok(activity.includes('openExternal(destination)'));
});

test('The platform export bridge preserves the browser download fallback and native file picker', () => {
  const js=read('dist/app.js');
  assert.ok(js.includes('typeof window.RaschetAndroid?.saveText'));
  assert.ok(js.includes('URL.createObjectURL(new Blob('));
  assert.ok(js.includes('Выберите место сохранения JSON-копии.'));
  assert.ok(js.includes('Выберите место сохранения CSV.'));
});

test('Offline private data is not transported by a default network API', () => {
  const manifest=read('android/app/src/main/AndroidManifest.xml');
  assert.ok(manifest.includes('android:allowBackup="false"'));
  assert.ok(manifest.includes('android:usesCleartextTraffic="false"'));
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(activity.includes('return deny();'));
  assert.ok(activity.includes('APP_HOST.equals(uri.getHost())'));
});
