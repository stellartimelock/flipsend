const CACHE = 'flipsend-web-12'
const ASSETS = [
  '/',
  '/index.html',
  '/css/app.css',
  '/manifest.webmanifest',
  '/js/app.js',
  '/js/client.js',
  '/js/protocol.js',
  '/js/tls.js',
  '/js/cert.js',
  '/js/vault.js',
  '/js/relay.js',
  '/js/files.js',
  '/js/sha256.js',
  '/js/bytes.js',
  '/js/asn1.js',
  '/js/qrcode.js',
  '/js/jsQR.js',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/apple-touch-icon.png',
  '/img/flipsend-icon.png'
]

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(ASSETS)).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key)))).then(() => self.clients.claim()))
})

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  if (event.request.method !== 'GET' || url.origin !== self.location.origin) return
  const live = url.pathname === '/' || url.pathname.endsWith('.html') || url.pathname.endsWith('.js') || url.pathname.endsWith('.css')
  if (live) {
    event.respondWith(
      fetch(event.request).then((res) => {
        const copy = res.clone()
        caches.open(CACHE).then((cache) => cache.put(event.request, copy))
        return res
      }).catch(() => caches.match(event.request, { ignoreSearch: true }))
    )
    return
  }
  event.respondWith(caches.match(event.request).then((hit) => hit || fetch(event.request)))
})
