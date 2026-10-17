import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {createHandler, selectRelease, REPO, ORIGIN} from './update-service.mjs';
const apk = new TextEncoder().encode('verified test package');
const digest = 'sha256:' + createHash('sha256').update(apk).digest('hex');
function release(tag = 'v2026.10.17.3') {
  return {tag_name: tag, draft: false, prerelease: true, published_at: '2026-10-17T00:00:00Z', body: 'notes',
    assets: ['arm64-v8a', 'armeabi-v7a', 'universal'].map(abi => {
      const name = `Sunshine-${tag}-${abi}.apk`;
      return {name, size: apk.length, state: 'uploaded', digest, browser_download_url: `https://github.com/${REPO}/releases/download/${tag}/${name}`};
    })};
}
function harness({remote = [release()], unavailable = false, corrupt = false, bundled = false} = {}) {
  const entries = new Map(), pending = []; let clock = 1_000_000, upstreamCalls = 0;
  const cache = {async match(k) {return entries.get(typeof k === 'string' ? k : k.url)?.clone();},
    async put(k, r) {entries.set(typeof k === 'string' ? k : k.url, r.clone());}};
  const env = {ASSETS: {async fetch(req) {
    if (new URL(req.url).pathname === '/updates/baseline.json') return Response.json(selectRelease([release()]));
    if (req.headers.has('If-Match')) return new Response(null, {status: 412});
    return bundled ? new Response(apk, {headers: {'Content-Type': 'application/vnd.android.package-archive'}}) : new Response('<html>SPA</html>', {headers: {'Content-Type': 'text/html'}});
  }}};
  const handler = createHandler({cacheImpl: () => cache, now: () => clock, fetchImpl: async url => {
    upstreamCalls++;
    if (unavailable) throw Error('offline');
    return url.includes('api.github.com') ? Response.json(remote) : new Response(corrupt ? new Uint8Array([1]) : apk);
  }});
  return {entries, remote, advance() {clock += 360001;}, calls: () => upstreamCalls,
    async request(path, method = 'GET', headers = {}) {const r = await handler.fetch(new Request(ORIGIN + path, {method, headers}), env, {waitUntil: p => pending.push(p)}); await Promise.all(pending); return r;}};
}
test('only complete published releases with exact assets can become latest', () => {
  const invalid = release('v2099.01.01.1'); invalid.assets[0].browser_download_url = 'https://evil.example/file';
  assert.equal(selectRelease([invalid, {...release('v2026.10.18.1'), draft: true}, release()]).release.tag_name, 'v2026.10.17.3');
  assert.throws(() => selectRelease([release('v2026.02.30.1')]));
  const missing = release(); missing.assets.pop(); assert.throws(() => selectRelease([missing]));
});
test('new release is discovered without a deployment and manifest remains on official origin', async () => {
  const h = harness({remote: [release('v2026.10.18.1')]});
  const r = await h.request('/updates/latest.json'), m = await r.json();
  assert.equal(m.release.tag_name, 'v2026.10.18.1'); assert.equal(r.headers.get('cache-control'), 'no-store');
  assert.ok(m.release.assets.every(a => a.browser_download_url.startsWith(ORIGIN + '/downloads/')));
  await h.request('/updates/latest.json'); assert.equal(h.calls(), 1);
});
test('latest alias always resolves through current metadata and cannot cache an old version', async () => {
  const h = harness({remote: [release('v2026.10.18.1')]});
  const r = await h.request('/downloads/latest/universal.apk');
  assert.equal(r.status, 302); assert.equal(r.headers.get('Cache-Control'), 'no-store');
  assert.equal(r.headers.get('Location'), ORIGIN + '/downloads/v2026.10.18.1/Sunshine-v2026.10.18.1-universal.apk');
});
test('published release withdrawal is respected after refresh', async () => {
  const remote = [release('v2026.10.18.1'), release()], h = harness({remote});
  await h.request('/updates/latest.json'); remote[0].draft = true; h.advance();
  assert.equal((await (await h.request('/updates/latest.json')).json()).release.tag_name, 'v2026.10.17.3');
});
test('GitHub outage still serves the bundled manifest and APK', async () => {
  const h = harness({unavailable: true, bundled: true});
  assert.equal((await (await h.request('/updates/latest.json')).json()).release.tag_name, 'v2026.10.17.3');
  const r = await h.request('/downloads/v2026.10.17.3/Sunshine-v2026.10.17.3-arm64-v8a.apk');
  assert.equal(r.status, 200); assert.deepEqual(new Uint8Array(await r.arrayBuffer()), apk);
});
test('uncached new APK is verified then served from cache', async () => {
  const h = harness({remote: [release('v2026.10.18.1')]});
  const path = '/downloads/v2026.10.18.1/Sunshine-v2026.10.18.1-arm64-v8a.apk';
  const r = await h.request(path); assert.equal(r.status, 200); assert.deepEqual(new Uint8Array(await r.arrayBuffer()), apk);
  const count = h.calls(); assert.equal((await h.request(path)).status, 200); assert.equal(h.calls(), count);
});
test('a new release cannot mistake a custom-header SPA response for a bundled APK', async () => {
  const h = harness({remote: [release('v2026.10.18.1')], bundled: true});
  const r = await h.request('/downloads/v2026.10.18.1/Sunshine-v2026.10.18.1-arm64-v8a.apk');
  assert.equal(r.status, 200); assert.equal(h.calls(), 2); // Metadata + verified upstream APK, never SPA fallback.
});
test('corrupt upstream APK is rejected and never cached', async () => {
  const h = harness({corrupt: true}), path = '/downloads/v2026.10.17.3/Sunshine-v2026.10.17.3-arm64-v8a.apk';
  assert.equal((await h.request(path)).status, 503); assert.ok(!h.entries.has(ORIGIN + path));
});
test('bundled APK resumes with 206 even when ASSETS ignores Range', async () => {
  const h = harness({bundled: true}), path = '/downloads/v2026.10.17.3/Sunshine-v2026.10.17.3-arm64-v8a.apk';
  const r = await h.request(path, 'GET', {Range: 'bytes=3-8'});
  assert.equal(r.status, 206); assert.equal(r.headers.get('Content-Range'), `bytes 3-8/${apk.length}`);
  assert.deepEqual(new Uint8Array(await r.arrayBuffer()), apk.slice(3, 9));
});
test('full APK advertises a strong validator and stale If-Range restarts the transfer', async () => {
  const h = harness({bundled: true}), path = '/downloads/v2026.10.17.3/Sunshine-v2026.10.17.3-arm64-v8a.apk';
  const initial = await h.request(path); assert.equal(initial.headers.get('ETag'), `"${digest.slice(7)}"`);
  assert.equal(initial.headers.get('Accept-Ranges'), 'bytes');
  const resumed = await h.request(path, 'GET', {Range: 'bytes=3-', 'If-Range': initial.headers.get('ETag')});
  assert.equal(resumed.status, 206);
  const stale = await h.request(path, 'GET', {Range: 'bytes=3-', 'If-Range': '"different-apk"'});
  assert.equal(stale.status, 200);
});
test('DownloadManager If-Match is checked against public SHA rather than internal ASSETS ETag', async () => {
  const h = harness({bundled: true}), path = '/downloads/v2026.10.17.3/Sunshine-v2026.10.17.3-arm64-v8a.apk';
  const r = await h.request(path, 'GET', {Range: 'bytes=3-', 'If-Match': `"${digest.slice(7)}"`});
  assert.equal(r.status, 206); assert.deepEqual(new Uint8Array(await r.arrayBuffer()), apk.slice(3));
  assert.equal(h.calls(), 1); // Only the release-list query, never a redundant GitHub APK fetch.
});
test('stale If-Match returns 412 and current If-None-Match returns 304', async () => {
  const h = harness({bundled: true}), path = '/downloads/v2026.10.17.3/Sunshine-v2026.10.17.3-arm64-v8a.apk';
  assert.equal((await h.request(path, 'GET', {'If-Match': '"wrong"'})).status, 412);
  assert.equal((await h.request(path, 'GET', {'If-None-Match': `"${digest.slice(7)}"`})).status, 304);
});
test('cached APK supports open-ended and suffix resume requests', async () => {
  const h = harness(), path = '/downloads/v2026.10.17.3/Sunshine-v2026.10.17.3-arm64-v8a.apk';
  await h.request(path);
  for (const [range, expected] of [['bytes=3-', apk.slice(3)], ['bytes=-5', apk.slice(-5)]]) {
    const r = await h.request(path, 'GET', {Range: range}); assert.equal(r.status, 206);
    assert.deepEqual(new Uint8Array(await r.arrayBuffer()), expected);
  }
});
test('invalid ranges return 416 rather than corrupting an interrupted APK', async () => {
  const h = harness({bundled: true}), path = '/downloads/v2026.10.17.3/Sunshine-v2026.10.17.3-arm64-v8a.apk';
  for (const range of ['bytes=99-', 'bytes=10-2', 'bytes=-0', 'bytes=0-1,3-4']) {
    assert.equal((await h.request(path, 'GET', {Range: range})).status, 416);
  }
});
test('old versions go to this GitHub repository; malformed and future files are not SPA pages', async () => {
  const h = harness();
  const old = await h.request('/downloads/v2026.10.16.1/Sunshine-v2026.10.16.1-universal.apk');
  assert.equal(old.status, 302); assert.ok(old.headers.get('location').startsWith(`https://github.com/${REPO}/releases/download/`));
  assert.equal((await h.request('/downloads/v2099.01.01.1/evil.apk')).status, 404);
  assert.equal((await h.request('/updates/missing.json')).status, 404);
  assert.equal((await h.request('/updates/latest.json', 'POST')).status, 405);
  assert.equal((await h.request('/updates/latest.json', 'HEAD')).body, null);
});
