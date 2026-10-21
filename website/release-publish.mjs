import {ABIS, MAX_APK, ORIGIN, REPO, bytes, fileKey, manifestKey, receiptKey, validManifest, version} from './update-service.mjs';

const encode = value => new TextEncoder().encode(value);
const hex = value => [...new Uint8Array(value)].map(n => n.toString(16).padStart(2, '0')).join('');
const hash = async value => hex(await crypto.subtle.digest('SHA-256', value));
const reply = (body, status = 200) => Response.json(body, {status, headers: {'Cache-Control': 'no-store'}});
const fail = (status, message) => Object.assign(Error(message), {status});

export function createPublisher({now = Date.now} = {}) {
  async function authenticate(request, env) {
    const stamp = request.headers.get('X-Sunshine-Timestamp'), digest = request.headers.get('X-Sunshine-Content-SHA256');
    const signature = request.headers.get('X-Sunshine-Signature');
    if (!env.RELEASE_SYNC_SECRET || !/^\d{10}$/.test(stamp || '') || Math.abs(now() / 1000 - Number(stamp)) > 300 ||
        !/^[a-f0-9]{64}$/.test(digest || '') || !/^[a-f0-9]{64}$/.test(signature || '')) throw fail(401, 'Invalid publication signature');
    const url = new URL(request.url);
    if (url.search) throw fail(400, 'Publication query strings are not accepted');
    const key = await crypto.subtle.importKey('raw', encode(env.RELEASE_SYNC_SECRET), {name: 'HMAC', hash: 'SHA-256'}, false, ['verify']);
    const signed = `${request.method}\n${url.pathname}\n${stamp}\n${digest}`;
    const supplied = Uint8Array.from(signature.match(/../g), value => parseInt(value, 16));
    if (!await crypto.subtle.verify('HMAC', key, supplied, encode(signed))) throw fail(401, 'Invalid publication signature');
    return digest;
  }
  async function baseline(env) {
    return validManifest(await (await env.ASSETS.fetch(new Request(`${ORIGIN}/updates/baseline.json`))).json());
  }
  async function known(env) {
    const values = []; let cursor;
    do {
      const page = await env.RELEASES.list({prefix: 'manifest:', limit: 100, ...(cursor ? {cursor} : {})});
      for (const key of page.keys) {
        const raw = await env.RELEASES.get(key.name, 'json');
        if (raw) values.push(validManifest(raw));
      }
      cursor = page.list_complete ? null : page.cursor;
    } while (cursor);
    return values;
  }
  async function removeCopy(env, manifest) {
    const tag = manifest.release.tag_name;
    for (const abi of ABIS) {
      await env.RELEASES.delete(fileKey(tag, `Sunshine-${tag}-${abi}.apk`));
      await env.RELEASES.delete(receiptKey(tag, abi));
    }
    await env.RELEASES.delete(manifestKey(tag));
  }
  async function publishIndex(env, candidates) {
    const unique = new Map(candidates.filter(m => m?.release).map(m => [m.release.tag_name, m]));
    const usable = [];
    for (const [tag, manifest] of unique) if (!await env.RELEASES.get(`revoked:${tag}`)) usable.push(manifest);
    usable.sort((a, b) => version(b.release.tag_name) - version(a.release.tag_name));
    const latest = usable[0] || {schema_version: 1, repository: REPO, release: null};
    await env.RELEASES.put('latest', JSON.stringify(latest));
    // Only site copies are pruned. The original GitHub release assets remain.
    for (const manifest of usable.slice(3)) await removeCopy(env, manifest);
    return latest.release?.tag_name || null;
  }
  return {async fetch(request, env) {
    try {
      if (!env.RELEASES) throw fail(503, 'Publication storage is not configured');
      const digest = await authenticate(request, env), path = new URL(request.url).pathname;
      const upload = /^\/updates\/publish\/(v\d{4}\.\d{2}\.\d{2}\.\d{1,6})\/(arm64-v8a|armeabi-v7a|universal)$/.exec(path);
      if (request.method === 'PUT' && upload && version(upload[1]) !== null) {
        const [_, tag, abi] = upload;
        const apk = await bytes(new Response(request.body, {headers: request.headers}), MAX_APK);
        if (!apk.length || await hash(apk) !== digest) throw fail(400, 'Package digest mismatch');
        const receipt = {size: apk.length, digest: `sha256:${digest}`};
        const previous = await env.RELEASES.get(receiptKey(tag, abi), 'json');
        if (previous && (previous.digest !== receipt.digest || previous.size !== receipt.size)) throw fail(409, 'Use a new version tag for changed packages');
        if (!previous) {
          await env.RELEASES.put(fileKey(tag, `Sunshine-${tag}-${abi}.apk`), apk, {metadata: receipt});
          await env.RELEASES.put(receiptKey(tag, abi), JSON.stringify(receipt));
        }
        return reply({uploaded: true, tag, abi, ...receipt});
      }
      if (request.method !== 'POST' || !['/updates/publish', '/updates/publish/retract'].includes(path)) throw fail(404, 'Unknown publication endpoint');
      const raw = await bytes(new Response(request.body, {headers: request.headers}), 128 * 1024);
      if (await hash(raw) !== digest) throw fail(400, 'Manifest digest mismatch');
      let body;
      try { body = JSON.parse(new TextDecoder().decode(raw)); }
      catch { throw fail(400, 'Invalid publication JSON'); }
      const bundled = await baseline(env), existing = await known(env);
      const current = await env.RELEASES.get('latest', 'json');
      if (path.endsWith('/retract')) {
        if (version(body.tag) === null) throw fail(400, 'Invalid release tag');
        await env.RELEASES.put(`revoked:${body.tag}`, 'true');
        for (const manifest of existing.filter(m => m.release.tag_name === body.tag)) await removeCopy(env, manifest);
        const latest = await publishIndex(env, [bundled, current, ...existing].filter(m => m?.release?.tag_name !== body.tag));
        return reply({retracted: body.tag, latest});
      }
      let manifest;
      try { manifest = validManifest(body); }
      catch { throw fail(400, 'Invalid public release manifest'); }
      const tag = manifest.release.tag_name;
      for (let index = 0; index < ABIS.length; index++) {
        const expected = manifest.release.assets[index];
        const receipt = await env.RELEASES.get(receiptKey(tag, ABIS[index]), 'json');
        if (!receipt || receipt.size !== expected.size || receipt.digest !== expected.digest) throw fail(409, 'All three verified packages must arrive before publication');
      }
      await env.RELEASES.put(manifestKey(tag), JSON.stringify(manifest));
      await env.RELEASES.delete(`revoked:${tag}`);
      const latest = await publishIndex(env, [bundled, current, ...existing, manifest]);
      return reply({published: tag, latest});
    } catch (error) {
      const status = error.status || (error.message === 'Response too large' ? 413 : 503);
      return reply({error: status < 500 ? error.message : 'Publication failed; retry'}, status);
    }
  }};
}
