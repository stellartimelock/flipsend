# FlipSend web client

This folder is both the FlipSend landing page and the iPhone/browser relay. It is the page at [https://flipsend.stellartimelock.com](https://flipsend.stellartimelock.com). [https://flipsend.app](https://flipsend.app) can point at the same files. `app.flipsend.app` can too.

The page speaks the same pairing protocol and the same TLS 1.2 session as the Android and Windows apps. Encryption uses WebCrypto. The relay sees ciphertext only.

## Cloudflare Pages

- Root directory: `web`
- Build command: none
- Output directory: `/` (the `web` folder itself)

The folder is already static. `node --test test/interop.test.mjs` checks the crypto and frame format. It is not part of the Pages build.

`_headers` sets `nosniff` and a no-cache policy for the service worker. A proxy in front of the site must pass those headers through. This build uses cache name `flipsend-web-11` and fetches HTML/JS/CSS from the network first so an old passphrase page cannot stick.

## Landing and pairing

The first screen is the app landing: the FlipSend icon, a "Coming soon to F-Droid" badge, features, and a Free section. FlipSend is free and open source, and Bot Link is a free companion app. There is no Google Play listing. Privacy and terms live at `/privacy` and `/terms`. **Connect with iPhone** opens the existing QR + Scan flow.

QR pairing is one-time. Scan, choose how to send, done. The session then ends. There is no lasting browser passphrase, biometric lock, or saved pairing. A fresh identity is made for that visit.

After pairing, pick a file and tap Send. The file picker includes photos, videos, and other files. Received files, including videos, can be sent again during that session. The page's QR leaves `host` empty, so the app uses the relay pairing room. If the camera is blocked, Scan falls back to a photo of the code.

## iPhone

Add the page to the Home Screen from Safari. Receiving a file offers Save to Files when the share sheet can take a file, and a download otherwise. One transfer runs at a time.

## Relay

The page needs the relay to answer browser requests. CORS headers are in the relay process. Room IDs stay the same so a later bot-relay hub can sit in front without changing this page. WebSocket splicing is unchanged.
