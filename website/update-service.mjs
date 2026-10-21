// Releases are pushed by GitHub Actions. Visitor requests never query GitHub.
export const REPO = 'nanaoniiiii/sunshine';
export const ORIGIN = 'https://nanaoni.tech';
export const ABIS = ['arm64-v8a', 'armeabi-v7a', 'universal'];
export const MAX_APK = 25 * 1024 * 1024;
export const version = tag => {
  const m = /^v(\d{4})\.(\d{2})\.(\d{2})\.(\d{1,6})$/.exec(tag || '');
  if (!m) return null;
  const [, y, mo, d, rev] = m.map(Number), date = new Date(Date.UTC(y, mo - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === mo - 1 && date.getUTCDate() === d
    ? y * 1e10 + mo * 1e8 + d * 1e6 + rev : null;
};
const nameFor = (tag, abi) => `Sunshine-${tag}-${abi}.apk`;
const pathFor = (tag, name) => `/downloads/${tag}/${name}`;
const githubFor = (tag, name) => `https://github.com/${REPO}/releases/download/${tag}/${name}`;
const json = (body, status = 200) => new Response(JSON.stringify(body), {status, headers: {
  'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*'
}});
async function ranged(response, request, size) {
  if (request.method === 'HEAD') return new Response(null, response);
  const range = request.headers.get('Range');
  if (!range || response.status !== 200) return response;
  const validator = request.headers.get('If-Range');
  if (validator && validator !== response.headers.get('ETag')) return response;
  const match = /^bytes=(\d*)-(\d*)$/.exec(range);
  let start, end;
  if (match && (match[1] || match[2])) {
    if (!match[1]) {start = Math.max(0, size - Number(match[2])); end = size - 1;}
    else {start = Number(match[1]); end = match[2] ? Math.min(size - 1, Number(match[2])) : size - 1;}
  }
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || start >= size || end < start) {
    await response.body?.cancel();
    return new Response(null, {status: 416, headers: {'Content-Range': `bytes */${size}`, 'Accept-Ranges': 'bytes'}});
  }
  // ASSETS can ignore Range when invoked from a Worker. Slice the trusted
  // asset stream without buffering an entire APK for each resumed connection.
  const reader = response.body.getReader(); let offset = 0;
  const body = new ReadableStream({
    async pull(controller) {
      try {
        for (;;) {
          const {value, done} = await reader.read();
          if (done) throw Error('APK stream ended before requested range');
          const next = offset + value.length;
          if (next <= start) {offset = next; continue;}
          controller.enqueue(value.subarray(Math.max(0, start - offset), Math.min(value.length, end + 1 - offset)));
          offset = next;
          if (offset > end) {controller.close(); await reader.cancel();}
          return;
        }
      } catch (error) {controller.error(error); await reader.cancel().catch(() => {});}
    },
    cancel(reason) {return reader.cancel(reason);}
  });
  const headers = new Headers(response.headers);
  headers.set('Content-Range', `bytes ${start}-${end}/${size}`);
  headers.set('Content-Length', String(end - start + 1)); headers.set('Accept-Ranges', 'bytes');
  headers.delete('Content-Encoding');
  return new Response(body, {status: 206, headers});
}
export async function bytes(response, limit) {
  if (Number(response.headers.get('Content-Length')) > limit) throw Error('Response too large');
  const reader = response.body.getReader(), chunks = []; let size = 0;
  try {
    for (;;) {const {value, done} = await reader.read(); if (done) break;
      size += value.length; if (size > limit) throw Error('Response too large'); chunks.push(value);}
  } finally {await reader.cancel().catch(() => {});}
  const result = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) {result.set(chunk, offset); offset += chunk.length;}
  return result;
}
export function selectRelease(releases) {
  if (!Array.isArray(releases)) throw Error('Invalid releases');
  const valid = releases.filter(r => version(r.tag_name) !== null && r.draft === false &&
    typeof r.published_at === 'string' && Number.isFinite(Date.parse(r.published_at)) &&
    ABIS.every(abi => r.assets?.some(a => a.name === nameFor(r.tag_name, abi) && a.state === 'uploaded' &&
      a.browser_download_url === githubFor(r.tag_name, a.name) && Number.isSafeInteger(a.size) && a.size > 0 &&
      a.size <= MAX_APK && /^sha256:[a-f0-9]{64}$/i.test(a.digest || ''))));
  valid.sort((a, b) => version(b.tag_name) - version(a.tag_name));
  if (!valid.length) throw Error('No complete published release');
  const r = valid[0];
  return {schema_version: 1, repository: REPO, release: {
    tag_name: r.tag_name, draft: false, prerelease: !!r.prerelease, published_at: r.published_at,
    body: String(r.body || '').slice(0, 12000), assets: ABIS.map(abi => {
      const a = r.assets.find(a => a.name === nameFor(r.tag_name, abi));
      return {name: a.name, size: a.size, digest: a.digest.toLowerCase(),
        browser_download_url: `${ORIGIN}${pathFor(r.tag_name, a.name)}`};
    })
  }};
}
export function validManifest(m) {
  if (m?.schema_version !== 1 || m.repository !== REPO || !m.release) throw Error('Invalid manifest');
  // Reuse all release/asset validation, replacing only the trusted mirror URL.
  return selectRelease([{...m.release, assets: m.release.assets.map(a => {
    if (a.browser_download_url !== `${ORIGIN}${pathFor(m.release.tag_name, a.name)}`) throw Error('Invalid mirror URL');
    return {...a, state: 'uploaded', browser_download_url: githubFor(m.release.tag_name, a.name)};
  })}]);
}
export const fileKey = (tag, name) => `apk:${tag}:${name}`;
export const manifestKey = tag => `manifest:${tag}`;
export const receiptKey = (tag, abi) => `receipt:${tag}:${abi}`;
export function createHandler({cacheImpl = () => caches.default, publisher = null} = {}) {
  return {async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith('/updates/') && !url.pathname.startsWith('/downloads/')) return env.ASSETS.fetch(request);
    if (url.pathname.startsWith('/updates/publish')) return publisher
      ? publisher.fetch(request, env) : json({error: 'Publishing is not configured'}, 503);
    if (!['GET', 'HEAD'].includes(request.method)) return new Response('Method not allowed', {status: 405, headers: {Allow: 'GET, HEAD'}});
    const cache = cacheImpl();
    let bundledManifest;
    async function baseline() {
      if (!bundledManifest) bundledManifest = validManifest(await (await env.ASSETS.fetch(new Request(`${ORIGIN}/updates/baseline.json`))).json());
      return bundledManifest;
    }
    async function latest() {
      const stored = await env.RELEASES?.get('latest', 'json');
      if (stored?.release === null) throw Error('No published release');
      return stored ? validManifest(stored) : baseline();
    }
    try {
      if (url.pathname === '/updates/latest.json') {
        const manifest = await latest(), response = json(manifest);
        response.headers.set('X-Sunshine-Version', manifest.release.tag_name);
        response.headers.set('X-Sunshine-Update-Mode', 'push');
        return request.method === 'HEAD' ? new Response(null, response) : response;
      }
      const alias = /^\/downloads\/latest\/(arm64-v8a|armeabi-v7a|universal)\.apk$/.exec(url.pathname);
      if (alias) {
        const release = (await latest()).release;
        return new Response(null, {status: 302, headers: {Location: `${ORIGIN}${pathFor(release.tag_name, nameFor(release.tag_name, alias[1]))}`, 'Cache-Control': 'no-store'}});
      }
      const pinned = /^\/downloads\/(v\d{4}\.\d{2}\.\d{2}\.\d{1,6})\/(Sunshine-.+)\.apk$/.exec(url.pathname);
      if (!pinned || version(pinned[1]) === null || !ABIS.some(abi => `${pinned[2]}.apk` === nameFor(pinned[1], abi))) return json({error: 'Unknown update file'}, 404);
      const bundled = await baseline();
      const stored = await env.RELEASES?.get(manifestKey(pinned[1]), 'json');
      const manifest = stored ? validManifest(stored) : bundled.release.tag_name === pinned[1] ? bundled : null;
      if (!manifest) {
        if (version(pinned[1]) < version((await latest()).release.tag_name)) return Response.redirect(githubFor(pinned[1], `${pinned[2]}.apk`), 302);
        return json({error: 'Unknown update file'}, 404);
      }
      const asset = manifest.release.assets.find(a => url.pathname === pathFor(manifest.release.tag_name, a.name));
      if (!asset) return json({error: 'Unknown update file'}, 404);
      const etag = `"${asset.digest.slice(7)}"`;
      const matches = value => value?.split(',').some(v => v.trim() === etag || v.trim() === '*');
      if (request.headers.has('If-Match') && !matches(request.headers.get('If-Match'))) return new Response(null, {status: 412, headers: {ETag: etag}});
      if (matches(request.headers.get('If-None-Match'))) return new Response(null, {status: 304, headers: {ETag: etag}});
      const cached = await cache.match(new Request(`${ORIGIN}${url.pathname}`, {headers: request.headers}));
      if (cached && cached.headers.get('ETag') === etag) {
        const response = new Response(cached.body, cached);
        response.headers.set('X-Sunshine-Download-Source', 'cache');
        return ranged(response, request, asset.size);
      }
      const headers = new Headers({'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': String(asset.size),
        'Content-Disposition': `attachment; filename="${asset.name}"`, 'Cache-Control': 'public, max-age=604800', ETag: etag,
        'Accept-Ranges': 'bytes', 'X-Content-Type-Options': 'nosniff'});
      // A deployed copy remains an offline baseline. Future copies are uploaded
      // to KV by the release workflow before its manifest becomes visible.
      if (bundled.release.tag_name === manifest.release.tag_name && bundled.release.assets.some(a => a.name === asset.name && a.digest === asset.digest)) {
        const assetHeaders = new Headers(request.headers);
        for (const key of ['Range', 'If-Match', 'If-None-Match', 'If-Range', 'If-Modified-Since', 'If-Unmodified-Since']) assetHeaders.delete(key);
        const response = await env.ASSETS.fetch(new Request(`${ORIGIN}${url.pathname}`, {method: request.method, headers: assetHeaders}));
        if (!response.ok || !/application\/(vnd\.android\.package-archive|octet-stream)/i.test(response.headers.get('Content-Type') || '')) throw Error('Static package missing');
        headers.set('X-Sunshine-Download-Source', 'static');
        return ranged(new Response(response.body, {headers}), request, asset.size);
      }
      const stream = await env.RELEASES?.get(fileKey(manifest.release.tag_name, asset.name), 'stream');
      if (!stream) throw Error('Uploaded package not available');
      headers.set('X-Sunshine-Download-Source', 'published-copy');
      const response = new Response(stream, {headers});
      ctx.waitUntil(cache.put(new Request(`${ORIGIN}${url.pathname}`), response.clone()).catch(() => {}));
      return ranged(response, request, asset.size);
    } catch {return json({error: 'Update service temporarily unavailable; please retry'}, 503);}
  }};
}