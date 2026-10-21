import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHandler, selectRelease, REPO, ORIGIN, fileKey} from './update-service.mjs';
import {apk, digest, environment, release, saved} from './test-support.mjs';
function harness({published = null} = {}) {
  const entries = new Map(), pending = [], env = environment();
  if (published) saved(env, published);
  const cache = {async match(k) {return entries.get(typeof k === 'string' ? k : k.url)?.clone();},
    async put(k, r) {entries.set(typeof k === 'string' ? k : k.url, r.clone());}};
  const handler = createHandler({cacheImpl: () => cache});
  return {entries, env, async request(path, method = 'GET', headers = {}) {
    const r = await handler.fetch(new Request(ORIGIN + path, {method, headers}), env, {waitUntil: p => pending.push(p)});
    await Promise.all(pending); return r;
  }};
}
const basePath = '/downloads/v2026.10.17.3/Sunshine-v2026.10.17.3-arm64-v8a.apk';

test('only complete public releases with exact repository assets are accepted', () => {
  const bad = release('v2099.01.01.1'); bad.assets[0].browser_download_url = 'https://evil.example/file';
  assert.equal(selectRelease([bad, {...release('v2026.10.18.1'), draft: true}, release()]).release.tag_name, 'v2026.10.17.3');
  assert.throws(() => selectRelease([release('v2026.02.30.1')]));
  const missing = release(); missing.assets.pop(); assert.throws(() => selectRelease([missing]));
});
test('visitor metadata, alias and package requests never fetch GitHub', async () => {
  const h = harness(), original = globalThis.fetch; let calls = 0;
  globalThis.fetch = async () => {calls++; throw Error('No visitor-triggered network fetch is allowed');};
  try {
    assert.equal((await h.request('/updates/latest.json')).status, 200);
    assert.equal((await h.request('/downloads/latest/arm64-v8a.apk')).status, 302);
    assert.deepEqual(new Uint8Array(await (await h.request(basePath)).arrayBuffer()), apk);
    assert.equal(calls, 0);
  } finally {globalThis.fetch = original;}
});
test('an uploaded manifest updates metadata and the uncacheable latest alias', async () => {
  const h = harness({published: 'v2026.10.18.1'});
  const metadata = await h.request('/updates/latest.json');
  assert.equal((await metadata.json()).release.tag_name, 'v2026.10.18.1');
  assert.equal(metadata.headers.get('X-Sunshine-Update-Mode'), 'push');
  const alias = await h.request('/downloads/latest/universal.apk');
  assert.equal(alias.headers.get('Cache-Control'), 'no-store');
  assert.equal(alias.headers.get('Location'), ORIGIN + '/downloads/v2026.10.18.1/Sunshine-v2026.10.18.1-universal.apk');
});
test('an uploaded copy is served and cached without a GitHub fallback', async () => {
  const h = harness({published: 'v2026.10.18.1'}), path = '/downloads/v2026.10.18.1/Sunshine-v2026.10.18.1-arm64-v8a.apk';
  const initial = await h.request(path);
  assert.equal(initial.headers.get('X-Sunshine-Download-Source'), 'published-copy');
  assert.deepEqual(new Uint8Array(await initial.arrayBuffer()), apk);
  assert.equal((await h.request(path)).headers.get('X-Sunshine-Download-Source'), 'cache');
});
test('a missing uploaded file fails rather than downloading from GitHub in the visitor request', async () => {
  const h = harness({published: 'v2026.10.18.1'});
  await h.env.RELEASES.delete(fileKey('v2026.10.18.1', 'Sunshine-v2026.10.18.1-arm64-v8a.apk'));
  assert.equal((await h.request('/downloads/v2026.10.18.1/Sunshine-v2026.10.18.1-arm64-v8a.apk')).status, 503);
});
test('a withdrawn last release is not re-advertised from the static baseline', async () => {
  const h = harness(); await h.env.RELEASES.put('latest', JSON.stringify({schema_version: 1, repository: REPO, release: null}));
  assert.equal((await h.request('/updates/latest.json')).status, 503);
});
test('baseline resumes with a public SHA validator even if ASSETS ignores Range', async () => {
  const h = harness();
  const r = await h.request(basePath, 'GET', {Range: 'bytes=3-8', 'If-Match': `"${digest.slice(7)}"`});
  assert.equal(r.status, 206); assert.equal(r.headers.get('Content-Range'), `bytes 3-8/${apk.length}`);
  assert.deepEqual(new Uint8Array(await r.arrayBuffer()), apk.slice(3, 9));
});
test('stale If-Range restarts while stale If-Match rejects a mismatched partial APK', async () => {
  const h = harness();
  assert.equal((await h.request(basePath, 'GET', {Range: 'bytes=3-', 'If-Range': '"wrong"'})).status, 200);
  assert.equal((await h.request(basePath, 'GET', {'If-Match': '"wrong"'})).status, 412);
  assert.equal((await h.request(basePath, 'GET', {'If-None-Match': `"${digest.slice(7)}"`})).status, 304);
});
test('cached and static packages support open-ended and suffix resume ranges', async () => {
  for (const published of [null, 'v2026.10.18.1']) {
    const h = harness({published}), path = published ? `/downloads/${published}/Sunshine-${published}-arm64-v8a.apk` : basePath;
    await h.request(path);
    for (const [range, expected] of [['bytes=3-', apk.slice(3)], ['bytes=-5', apk.slice(-5)]]) {
      const r = await h.request(path, 'GET', {Range: range});
      assert.equal(r.status, 206); assert.deepEqual(new Uint8Array(await r.arrayBuffer()), expected);
    }
  }
});
test('invalid ranges return 416 and HEAD never returns a body', async () => {
  const h = harness();
  for (const range of ['bytes=99-', 'bytes=10-2', 'bytes=-0', 'bytes=0-1,3-4']) assert.equal((await h.request(basePath, 'GET', {Range: range})).status, 416);
  const head = await h.request(basePath, 'HEAD'); assert.equal(head.status, 200); assert.equal(head.body, null);
});
test('old paths redirect only to this repository and unknown files are not SPA pages', async () => {
  const h = harness();
  const old = await h.request('/downloads/v2026.10.16.1/Sunshine-v2026.10.16.1-universal.apk');
  assert.equal(old.status, 302); assert.ok(old.headers.get('Location').startsWith(`https://github.com/${REPO}/releases/download/`));
  assert.equal((await h.request('/downloads/v2099.01.01.1/evil.apk')).status, 404);
  assert.equal((await h.request('/updates/missing.json')).status, 404);
  assert.equal((await h.request('/updates/latest.json', 'POST')).status, 405);
});
