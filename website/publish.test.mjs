import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createPublisher} from './release-publish.mjs';
import {ABIS, ORIGIN, selectRelease} from './update-service.mjs';
import {apk, digest, environment, release, signed} from './test-support.mjs';

async function upload(publisher, env, tag = 'v2026.10.18.1') {
  for (const abi of ABIS) assert.equal((await publisher.fetch(signed(`/updates/publish/${tag}/${abi}`, 'PUT', apk), env)).status, 200);
}
async function commit(publisher, env, tag) {return publisher.fetch(signed('/updates/publish', 'POST', selectRelease([release(tag)])), env);}

test('unsigned, expired and path-tampered publication requests cannot write storage', async () => {
  const publisher = createPublisher(), env = environment(), path = '/updates/publish/v2026.10.18.1/arm64-v8a';
  assert.equal((await publisher.fetch(new Request(ORIGIN + path, {method: 'PUT', body: apk}), env)).status, 401);
  assert.equal((await publisher.fetch(signed(path, 'PUT', apk, {stamp: Math.floor(Date.now() / 1000) - 301}), env)).status, 401);
  const request = signed(path, 'PUT', apk);
  assert.equal((await publisher.fetch(new Request(ORIGIN + path.replace('arm64-v8a', 'universal'), request), env)).status, 401);
  assert.equal(env.RELEASES.entries.size, 0);
});
test('an authenticated body still needs the signed SHA-256 to match actual bytes', async () => {
  const env = environment(), publisher = createPublisher();
  const r = await publisher.fetch(signed('/updates/publish/v2026.10.18.1/arm64-v8a', 'PUT', new Uint8Array([1]), {digestOverride: digest.slice(7)}), env);
  assert.equal(r.status, 400); assert.equal(env.RELEASES.entries.size, 0);
});
test('a partial upload never advertises the new release', async () => {
  const env = environment(), publisher = createPublisher();
  await publisher.fetch(signed('/updates/publish/v2026.10.18.1/arm64-v8a', 'PUT', apk), env);
  assert.equal((await commit(publisher, env, 'v2026.10.18.1')).status, 409);
  assert.equal(await env.RELEASES.get('latest'), null);
});
test('all three copies are verified before the published manifest becomes latest', async () => {
  const env = environment(), publisher = createPublisher(); await upload(publisher, env);
  const r = await commit(publisher, env, 'v2026.10.18.1');
  assert.equal(r.status, 200); assert.equal((await r.json()).latest, 'v2026.10.18.1');
  assert.equal((await env.RELEASES.get('latest', 'json')).release.assets.length, 3);
});
test('foreign or draft manifests cannot replace an existing published release', async () => {
  const env = environment(), publisher = createPublisher(); await upload(publisher, env);
  const valid = selectRelease([release('v2026.10.18.1')]);
  assert.equal((await publisher.fetch(signed('/updates/publish', 'POST', {...valid, repository: 'attacker/repo'}), env)).status, 400);
  valid.release.draft = true;
  assert.equal((await publisher.fetch(signed('/updates/publish', 'POST', valid), env)).status, 400);
  assert.equal(await env.RELEASES.get('latest'), null);
});
test('retry is idempotent and an older release cannot downgrade the current version', async () => {
  const env = environment(), publisher = createPublisher(); await upload(publisher, env, 'v2026.10.19.1');
  await commit(publisher, env, 'v2026.10.19.1');
  await upload(publisher, env, 'v2026.10.18.1'); await upload(publisher, env, 'v2026.10.18.1');
  assert.equal((await (await commit(publisher, env, 'v2026.10.18.1')).json()).latest, 'v2026.10.19.1');
});
test('withdrawal returns to the preceding published copy without querying GitHub', async () => {
  const env = environment(), publisher = createPublisher();
  for (const tag of ['v2026.10.18.1', 'v2026.10.19.1']) {await upload(publisher, env, tag); await commit(publisher, env, tag);}
  const r = await publisher.fetch(signed('/updates/publish/retract', 'POST', {tag: 'v2026.10.19.1'}), env);
  assert.equal(r.status, 200); assert.equal((await r.json()).latest, 'v2026.10.18.1');
});
test('only three site copies remain after repeated releases', async () => {
  const env = environment(), publisher = createPublisher();
  for (const day of [18, 19, 20, 21, 22]) {const tag = `v2026.10.${day}.1`; await upload(publisher, env, tag); await commit(publisher, env, tag);}
  assert.equal((await env.RELEASES.list({prefix: 'manifest:'})).keys.length, 3);
  assert.equal((await env.RELEASES.list({prefix: 'apk:'})).keys.length, 9);
});
