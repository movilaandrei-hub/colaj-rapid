// Builds the installable phone app (GitHub Pages serves /docs) from colaj-rapid.html.
// Usage: node build-site.js
const fs = require('fs'), path = require('path');
const root = __dirname, out = path.join(root, 'docs');
fs.mkdirSync(out, { recursive: true });

const app = fs.readFileSync(path.join(root, 'colaj-rapid.html'), 'utf8');
const version = new Date().toISOString().replace(/\D/g, '').slice(0, 12);

const head = `<!doctype html>
<html lang="ro">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#2B54F0">
<meta name="description" content="Faci pozele, aplicația face colajul, caruselul sau Reel-ul animat.">
<link rel="manifest" href="manifest.webmanifest">
<link rel="icon" href="icon-192.png">
<link rel="apple-touch-icon" href="icon-192.png">
<style>:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}body{margin:0}img{max-width:100%}[hidden]{display:none!important}</style>
</head>
<body>
`;
const tail = `
<script>
if ('serviceWorker' in navigator) addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
</script>
</body>
</html>
`;
fs.writeFileSync(path.join(out, 'index.html'), head + app + tail);

fs.writeFileSync(path.join(out, 'manifest.webmanifest'), JSON.stringify({
  name: 'Boomly',
  short_name: 'Boomly',
  description: 'Faci pozele, aplicația face colajul, caruselul sau Reel-ul animat.',
  lang: 'ro',
  start_url: './',
  scope: './',
  display: 'standalone',
  orientation: 'portrait',
  background_color: '#ECEEF1',
  theme_color: '#2B54F0',
  icons: [
    { src: 'icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
    { src: 'icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
    { src: 'icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
  ],
}, null, 2));

fs.writeFileSync(path.join(out, 'sw.js'), `// Offline support: the app shell is cached; fonts and the zip library are cached after first use.
const CACHE = 'colaj-rapid-${version}';
const SHELL = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './icon-512.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const req = e.request; if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin === location.origin) {
    // app files: network first so updates arrive, cache when offline
    e.respondWith(fetch(req).then(r => { const copy = r.clone(); caches.open(CACHE).then(c => c.put(req, copy)); return r; }).catch(() => caches.match(req).then(r => r || caches.match('./index.html'))));
  } else if (/fonts\\.(googleapis|gstatic)\\.com|cdnjs\\.cloudflare\\.com/.test(url.host)) {
    e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(r => { const copy = r.clone(); caches.open(CACHE).then(c => c.put(req, copy)); return r; })));
  }
});
`);
console.log('docs/ built, version ' + version);

// Android app (Capacitor): same page, fonts bundled so it works offline, no service worker or zip library.
(async () => {
  const www = path.join(root, 'android-app', 'www'), fontsDir = path.join(www, 'fonts');
  fs.mkdirSync(fontsDir, { recursive: true });
  const link = app.match(/<link rel="stylesheet" href="(https:\/\/fonts\.googleapis\.com[^"]+)">/);
  let page = app.replace(/<script src="https:\/\/cdnjs\.cloudflare\.com\/ajax\/libs\/jszip[^>]*><\/script>\n?/, '')
    .replace(/<link rel="preconnect"[^>]*>\n?/g, '');
  // scripts from jsDelivr are copied into the app
  for (const m of [...page.matchAll(/<script src="(https:\/\/cdn\.jsdelivr\.net\/npm\/[^"]+)"[^>]*><\/script>/g)]) {
    try {
      const file = path.basename(new URL(m[1]).pathname), libDir = path.join(www, 'lib');
      fs.mkdirSync(libDir, { recursive: true });
      fs.writeFileSync(path.join(libDir, file), Buffer.from(await (await fetch(m[1])).arrayBuffer()));
      page = page.replace(m[0], `<script src="lib/${file}"></script>`);
      console.log('android-app/www: bundled ' + file);
    } catch (e) { console.warn('script not bundled:', m[1], e.message); }
  }
  if (link) {
    try {
      const ua = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36';
      let css = await (await fetch(link[1].replace(/&amp;/g, '&'), { headers: { 'User-Agent': ua } })).text();
      const urls = [...new Set(css.match(/https:\/\/fonts\.gstatic\.com\/[^)]+/g) || [])];
      for (const [i, u] of urls.entries()) {
        const file = `f${i}.woff2`;
        fs.writeFileSync(path.join(fontsDir, file), Buffer.from(await (await fetch(u)).arrayBuffer()));
        css = css.split(u).join(file);
      }
      fs.writeFileSync(path.join(fontsDir, 'fonts.css'), css);
      page = page.replace(link[0], '<link rel="stylesheet" href="fonts/fonts.css">');
      console.log(`android-app/www: ${urls.length} font files bundled`);
    } catch (e) { console.warn('fonts not bundled (offline?):', e.message); }
  }
  fs.writeFileSync(path.join(www, 'index.html'), head.replace(/<link rel="manifest"[^>]*>\n|<link rel="(apple-touch-)?icon"[^>]*>\n/g, '') + page + '\n</body>\n</html>\n');
  console.log('android-app/www built');
})();
