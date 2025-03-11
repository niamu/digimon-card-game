const CACHE_VERSION = "2020-03-10";

self.addEventListener("install", function (event) {
  event.waitUntil(
    caches.open(CACHE_VERSION).then(function (cache) {
      return cache.addAll([
        "./index.html",
        "./opencv_js.wasm",
        "./js/card-scanner.js",
        "./js/opencv_js.js",
        "./js/hash_db.json",
        "./css/style.css",
        "./images/bg.png",
        "./images/img_logo.png",
        "./fonts/montserrat-v29-latin-regular.woff2",
        "./fonts/montserrat-v29-latin-900.woff2",
        "./fonts/montserrat-v29-latin-900italic.woff2",
      ]);
    }),
  );
});

self.addEventListener("fetch", function (event) {
  event.respondWith(
    caches.open(CACHE_VERSION).then(async function (cache) {
      return cache.match(event.request).then(function (response) {
        if (response?.url.endsWith(".wasm")) {
          const headers = new Headers(response.headers);
          headers.delete("Content-Type");
          headers.append("Content-Type", "application/wasm");
          return new Response(response.body, {
            status: response.status,
            statusText: response.statusText,
            headers: headers,
          });
        }
        return (
          response ||
          fetch(event.request).then(function (response) {
            cache.put(event.request, response.clone());
            return response;
          })
        );
      });
    }),
  );
});

self.addEventListener("activate", function activator(event) {
  event.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(
        keys
          .filter(function (key) {
            return key.indexOf(CACHE_VERSION) !== 0;
          })
          .map(function (key) {
            return caches.delete(key);
          }),
      );
    }),
  );
});
