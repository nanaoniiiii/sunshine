// The website keeps a bundled current release and discovers newly published
// GitHub releases server-side. Clients never need GitHub to fetch the latest APK.
export const REPO = 'nanaoniiiii/sunshine';
export const ORIGIN = 'https://nanaoni.tech';
const ABIS = ['arm64-v8a', 'armeabi-v7a', 'universal'];
const MAX_APK = 25 * 1024 * 1024;
const SNAPSHOT = `${ORIGIN}/__update_cache__/latest`;
const version = tag => {
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
async function bytes(response, limit) {
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
function validManifest(m) {
  if (m?.schema_version !== 1 || m.repository !== REPO || !m.release) throw Error('Invalid manifest');
  // Reuse all release/asset validation, replacing only the trusted mirror URL.
  return selectRelease([{...m.release, assets: m.release.assets.map(a => {
    if (a.browser_download_url !== `${ORIGIN}${pathFor(m.release.tag_name, a.name)}`) throw Error('Invalid mirror URL');
    return {...a, state: 'uploaded', browser_download_url: githubFor(m.release.tag_name, a.name)};
  })}]);
}
export function createHandler({fetchImpl = fetch, cacheImpl = () => caches.default, now = Date.now} = {}) {
  return {async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith('/updates/') && !url.pathname.startsWith('/downloads/')) return env.ASSETS.fetch(request);
    if (!['GET', 'HEAD'].includes(request.method)) return new Response('Method not allowed', {status: 405, headers: {Allow: 'GET, HEAD'}});
    const cache = cacheImpl();
    const save = (key, response) => ctx.waitUntil(cache.put(key, response).catch(() => {}));
    let bundledManifest;
    async function baselineManifest() {
      if (!bundledManifest) {
        const response = await env.ASSETS.fetch(new Request(`${ORIGIN}/updates/baseline.json`));
        bundledManifest = validManifest(await response.json());
      }
      return bundledManifest;
    }
    async function latest() {
      const previous = await cache.match(SNAPSHOT);
      const saved = previous ? await previous.json().catch(() => null) : null;
      let cached;
      try {cached = validManifest(saved);} catch {}
      if (cached && now() - saved.checked_at < 5 * 60 * 1000) return cached;
      const baseline = await baselineManifest();
      try {
        const response = await fetchImpl(`https://api.github.com/repos/${REPO}/releases?per_page=30`, {
          headers: {'User-Agent': 'Sunshine-Official-Updates', Accept: 'application/vnd.github+json'}, signal: AbortSignal.timeout(6000)
        });
        if (!response.ok) throw Error(`GitHub ${response.status}`);
        const live = selectRelease(JSON.parse(new TextDecoder().decode(await bytes(response, 1024 * 1024))));
        // A healthy upstream is authoritative, including a withdrawn release.
        const result = live;
        save(SNAPSHOT, new Response(JSON.stringify({...result, checked_at: now()}), {headers: {'Cache-Control': 'public, max-age=604800'}}));
        return result;
      } catch {
        const result = [cached, baseline].filter(Boolean).sort((a, b) => version(b.release.tag_name) - version(a.release.tag_name))[0];
        // Short retry backoff, while retaining the last good release during outages.
        save(SNAPSHOT, new Response(JSON.stringify({...result, checked_at: now() - 4 * 60 * 1000}), {headers: {'Cache-Control': 'public, max-age=604800'}}));
        return result;
      }
    }
    try {
      const manifest = await latest(), release = manifest.release;
      if (url.pathname === '/updates/latest.json') {
        const response = json(manifest); response.headers.set('X-Sunshine-Version', release.tag_name);
        return request.method === 'HEAD' ? new Response(null, response) : response;
      }
      const alias = /^\/downloads\/latest\/(arm64-v8a|armeabi-v7a|universal)\.apk$/.exec(url.pathname);
      if (alias) return new Response(null, {status: 302, headers: {
        Location: `${ORIGIN}${pathFor(release.tag_name, nameFor(release.tag_name, alias[1]))}`, 'Cache-Control': 'no-store'
      }});
      const asset = release.assets.find(a => url.pathname === pathFor(release.tag_name, a.name));
      if (!asset) {
        const old = /^\/downloads\/(v\d{4}\.\d{2}\.\d{2}\.\d{1,6})\/(Sunshine-.+)\.apk$/.exec(url.pathname);
        if (old && version(old[1]) !== null && version(old[1]) < version(release.tag_name) &&
          ABIS.some(abi => `${old[2]}.apk` === nameFor(old[1], abi))) return Response.redirect(githubFor(old[1], `${old[2]}.apk`), 302);
        return json({error: 'Unknown update file'}, 404);
      }
      const etag = `"${asset.digest.slice(7)}"`;
      const matches = value => value?.split(',').some(v => v.trim() === etag || v.trim() === '*');
      const ifMatch = request.headers.get('If-Match');
      if (ifMatch && !matches(ifMatch)) return new Response(null, {status: 412, headers: {ETag: etag}});
      if (matches(request.headers.get('If-None-Match'))) return new Response(null, {status: 304, headers: {ETag: etag}});
      const lookup = new Request(`${ORIGIN}${url.pathname}`, {headers: request.headers});
      const cached = await cache.match(lookup);
      if (cached) return await ranged(cached, request, asset.size);
      // DownloadManager resumes with If-Match. Our public SHA-256 ETag differs
      // from ASSETS' internal validator: validate it here, never pass it through
      // and accidentally turn a local asset read into a GitHub fallback.
      const assetHeaders = new Headers(request.headers);
      for (const key of ['Range', 'If-Match', 'If-None-Match', 'If-Range', 'If-Modified-Since', 'If-Unmodified-Since']) assetHeaders.delete(key);
      // Only the baseline's files are deployed as static assets. A new release
      // otherwise hits SPA fallback, which custom headers can mislabel as an APK.
      if ((await baselineManifest()).release.tag_name === release.tag_name) {
        const bundled = await env.ASSETS.fetch(new Request(`${ORIGIN}${url.pathname}`, {method: request.method, headers: assetHeaders}));
        if (bundled.ok && /application\/(vnd\.android\.package-archive|octet-stream)/i.test(bundled.headers.get('Content-Type') || '')) {
          const headers = new Headers(bundled.headers);
          headers.set('ETag', etag); headers.set('Accept-Ranges', 'bytes');
          if (bundled.status === 200) headers.set('Content-Length', String(asset.size));
          return await ranged(new Response(bundled.body, {status: bundled.status, headers}), request, asset.size);
        }
      }
      const upstream = await fetchImpl(githubFor(release.tag_name, asset.name), {signal: AbortSignal.timeout(45000)});
      if (!upstream.ok) throw Error('Download origin unavailable');
      const apk = await bytes(upstream, MAX_APK);
      const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', apk))].map(x => x.toString(16).padStart(2, '0')).join('');
      if (apk.length !== asset.size || `sha256:${digest}` !== asset.digest) throw Error('Download checksum mismatch');
      const response = new Response(apk, {headers: {'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': String(apk.length), 'Content-Disposition': `attachment; filename="${asset.name}"`,
        'Cache-Control': 'public, max-age=604800', ETag: `"${digest}"`, 'Accept-Ranges': 'bytes', 'X-Content-Type-Options': 'nosniff'}});
      save(new Request(`${ORIGIN}${url.pathname}`), response.clone());
      return await ranged(response, request, asset.size);
    } catch {return json({error: 'Update service temporarily unavailable; please retry'}, 503);}
  }};
}
