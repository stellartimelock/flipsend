# FlipSend protocol version 1

The product name is FlipSend. The wire prefix stays `stellarsend://` and the beacon app id stays `stellarsend` so existing pairs keep working.

FlipSend speaks one small binary protocol over a mutually authenticated TLS 1.2 socket. Direct transfers stay on the local network. A peer is trusted only after a person pairs it, and only while its certificate public key still matches the stored pin. An optional relay forwards the TLS session when the devices are not on the same network. The relay sees ciphertext only.

## Limits

| Item | Value |
| --- | --- |
| Version | 1 |
| TCP port | 53321 |
| UDP discovery port | 53320 |
| mDNS service | `_stellarsend._tcp.local` |
| Header | 12 bytes |
| Max frame payload | 262144 bytes |
| Max chunk data | 65536 bytes |
| Max text | 1 MiB |
| Max file | 8 GiB |
| Max device name | 64 characters |
| Max file name | 240 characters |

Files are streamed in chunks. The implementation must not read a whole file into memory.

## Frame

```
0  magic     4 bytes  53 53 4E 44   ("SSND")
4  version   uint8    1
5  type      uint8
6  flags     uint16   big-endian, must be 0
8  length    uint32   big-endian payload length
12 payload   length bytes
```

Unknown versions, unknown types, nonzero flags, and oversized payloads are rejected and the connection is closed.

| Type | Name | Payload |
| --- | --- | --- |
| 1 | HELLO | JSON |
| 2 | HELLO_ACK | JSON |
| 3 | OFFER | JSON |
| 4 | ACCEPT | JSON |
| 5 | REJECT | JSON |
| 6 | CHUNK | binary |
| 7 | CANCEL | JSON |
| 8 | COMPLETE | JSON |
| 9 | ERROR | JSON |
| 10 | SERVICE | JSON |
| 11 | SERVICE_RESULT | JSON |
| 12 | TERMINAL_DATA | JSON |
| 13 | SCREEN_FRAME | JPEG bytes |
| 14 | REMOTE_INPUT | JSON |
| 15 | ACK | JSON |

Control JSON is UTF-8 and compact. Field names are fixed. Shared vectors live in `testdata/protocol-vectors.json`.

HELLO: `v`, `deviceId`, `name`, `fingerprint`. A 1.5.56+ hello may also include `resume: true`. Older peers ignore that field.

HELLO_ACK: `deviceId`, `fingerprint`

OFFER: `transferId`, `kind` (`file` or `text`), `name`, `size`, `sha256`, `mime`. A batch may also include `index` and `count` (1-based position and size of the batch). A single file omits both. Receivers that do not know those fields ignore them.

ACCEPT / REJECT / CANCEL: `transferId` and, for REJECT, `reason`. ACCEPT may also include `offset`, a byte count the receiver already has for the same SHA-256. When it is missing or zero, the sender starts at the beginning. A decline, a bad hash, or a cancel deletes the partial file. An interrupted transfer keeps the partial and the next attempt with the same `transferId` resumes from that offset.

ACK: `transferId` and `offset`. Sent only when both HELLOs included `resume: true`. The receiver flushes the partial file and writes this ack after every 1 MiB. The sender waits for that ack before sending further, so a relay drop resumes from the last acknowledged offset instead of from zero.

COMPLETE: `transferId`, `sha256`

ERROR: `code`, `message`. Codes include `key_changed`, `unknown_peer`, `limit`, `hash_mismatch`, `cancelled`, `declined`, `bad_request`, `io`, and `not_implemented`.

SERVICE is a companion call on the same paired socket, after HELLO_ACK. It is not a file transfer. The payload is `id` (UUID), `service` and `op` (lowercase tokens of letters, digits, and dots), and a JSON object `body`. SERVICE_RESULT is `id`, `ok`, `code`, `message`. The message is a short public sentence. It must not contain a password or the opened URL.

| Service | Op | Body | Result |
| --- | --- | --- | --- |
| `vault.clipboard` | `put` | `password` (1 to 1024 characters; a Fortress login is `username` then a newline then `password`), optional `clearAfterSeconds` (5–300, default 45) | Copies it to the PC clipboard. The PC clears it after that many seconds only if that exact value is still there, and shows a notice. |
| `remote.browser` | `status` | `{}` | Reports whether open, live view, and typing are on. |
| `remote.browser` | `open` | `url`, http or https, no user info, at most 2048 characters | Opens that link on the PC. |
| `remote.browser` | `frame` | `{}` | Live view. This version answers `browser_view_unavailable`. |
| `remote.browser` | `input` | `kind` `tap` or `text`, optional `x` and `y` from 0 to 1, `text` up to 512 characters | Page input. This version answers `browser_input_unavailable`. |
| `terminal` | `status` | `{}` | Reports whether Terminal is on and whether allowlist mode is on. |
| `terminal` | `run` | `command` up to 4000 characters, `confirmed` boolean, `sig` base64url | Runs the command on the PC when Terminal is on. Output is streamed as TERMINAL_DATA before SERVICE_RESULT. |
| `remote.control` | `status` | `{}` | Reports that remote desktop is ready. |
| `remote.control` | `request` | `session` UUID, `from` `pc` or `phone` | Asks the other device to Allow one session. `from` `phone` is remote desktop and does not need a phone permission. `from` `pc` is Control this phone. An allowed request from the phone keeps that socket open: the computer streams SCREEN_FRAME and reads REMOTE_INPUT. A 1.5.34 phone reads the result, closes, and opens `watch` on a new connection; the Allow stays in place. If the computer cannot capture, the result says to update the FlipSend app on the phone. |
| `remote.control` | `drive` | `session` | After Allow, the controller sends REMOTE_INPUT frames (tap, longpress, swipe, scroll, text, Back / Home / Recents). |
| `remote.control` | `watch` | `session` | After Allow, the PC sends SCREEN_FRAME JPEGs and reads REMOTE_INPUT. The HELLO may include the same `direct` offer as a file send, so the frames move to the punched link when it opens and stay on the relay when it does not. |
| `remote.control` | `stop` | `session` | Ends the session on both sides. |

`longpress` is a right click. `scroll` and `wheel` carry `dy` in notches from -8 to 8. On Wi-Fi or a punched direct link the PC sends about 10 frames a second, JPEG quality 60, at most 1280 pixels wide. On the relay that drops to about 4 frames a second, quality 45, and 960 pixels wide. Each JPEG stays within 200000 bytes. An always-allow choice is stored on the PC and is not a protocol field.

TERMINAL_DATA is `id`, `seq` (starting at 1), `stream` (`out` or `err`), and `text`. It is sent only while a `terminal` `run` is in progress, after the SERVICE request and before SERVICE_RESULT. File offers never use this type. An older peer that receives a `terminal` service answers `not_implemented` and does not emit TERMINAL_DATA.

`sig` is an ECDSA P-256 SHA-256 signature, DER encoded, over the UTF-8 bytes `flipsend-terminal-v1\n<id>\n<command>\n<0 or 1>\n`. The signing key is the phone's pairing private key. The PC checks it against the paired device's public key. The key is the pairing identity, not a separate device-bound key. A missing or bad signature is `unauthenticated` and the command text is not written to the log.

The PC runs the command only when Terminal is on. Allowlist mode, on by default, requires the full command text to match an allowlist entry. `del`, `rm`, `rmdir`, `format`, and `shutdown` are refused with `needs_confirm` unless `confirmed` is true. The phone asks before it sends that flag. Each accepted or refused command is appended to `terminal.log` on the PC. The result message is a short sentence such as `Finished. Exit code 0.` and does not repeat the command.

An unknown service or operation is `not_implemented`. The phone asks; the paired PC answers. A device that does not host companion calls still returns SERVICE_RESULT with `not_implemented` instead of treating the frame as a file offer. Companion calls require the same mutual TLS pin as a transfer. They are refused for an unknown or changed key.

A CHUNK payload is a 16-byte UUID in big-endian network order (the 32 hex digits of the canonical UUID, not a mixed-endian GUID), then a big-endian uint64 offset, then the bytes. Offsets must increase with no gaps. A chunk must not run past the offered size.

## Handshake

Both sides send HELLO, then HELLO_ACK.

1. The fingerprint in HELLO must equal the SHA-256 of the peer certificate SubjectPublicKeyInfo that TLS already presented.
2. If a peer is stored, the fingerprint and device id must match. A mismatch is `key_changed`. Unpair both sides and pair again.
3. If no peer is stored, the listener accepts a new peer only inside an explicit two-minute pairing window and only after a person confirms. On the relay, that same window also accepts a new certificate when a peer is already stored, and a confirmed device replaces the saved peer. The direct LAN listener still accepts a new peer only when none is stored. Otherwise the result is `unknown_peer`.
4. HELLO_ACK must repeat the same device id and fingerprint as HELLO.

The sender then sends OFFER and waits. The receiver shows the name and size and must not write the file until the person accepts. ACCEPT starts the chunks. REJECT declines. CANCEL stops the transfer. COMPLETE carries the SHA-256 of the bytes. The receiver checks that hash against both the offer and the bytes it wrote, then answers with COMPLETE. On failure it deletes the partial file.

A pairing connection may close after HELLO_ACK. End of stream at that point means the devices paired and no file was offered.

One automatic retry is allowed for a transient I/O failure when the receiver has not completed the transfer and the sender has not cancelled. A decline, a bad key, or a hash mismatch is not retried.

## TLS

TLS 1.2 with mutual certificates. Each device holds a self-signed ECDSA P-256 certificate whose subject is its device id. The certificate includes Key Usage digitalSignature and Extended Key Usage Client Authentication plus Server Authentication so Android 15 and Windows both present it during the handshake. The pin is the lowercase hex SHA-256 of the certificate SubjectPublicKeyInfo, not the full certificate, so a device may re-issue the same key with those usages and keep the existing pair. TLS 1.3 mutual authentication is intentionally unused because the platforms do not agree on when the peer certificate is available.

The trust manager accepts a certificate only when its pin matches the paired peer, or when a pairing window is open and the person then confirms. Hostname checks are not used. Peers are found by local address, then authenticated by the pin.

A browser can speak this same TLS 1.2 session. The web client uses WebCrypto for ECDH P-256, ECDSA P-256, AES-128-GCM, and HMAC-SHA256. It does not add a second record layer. The pairing private key is stored as JSON in IndexedDB under the page origin. There is no passphrase. The page is relay-only. Its pairing QR leaves `host` empty and omits `ips`, and the app dials the pairing room.

## Pairing QR

```
stellarsend://pair?v=1&id=<uuid>&pk=<spki-base64url>&fp=<sha256-hex>&host=<local-ip>&port=53321&name=<device>&ips=<ip>,<ip>
```

`pk` is the public SubjectPublicKeyInfo, base64url without padding. `fp` must be the SHA-256 of those exact bytes. The QR must not contain a private key, seed, token, or password. `host` is the first candidate and must be empty or a local address (RFC1918). `ips` is the comma-separated candidate list in rank order: Wi-Fi and Ethernet with a gateway, then the same without a gateway, then other adapters, then VPN-assigned addresses. vEthernet, Hyper-V, WSL, `169.254` link-local, loopback, and public addresses are omitted. Unknown fields are ignored. A fixture is in `testdata/pairing.json`. Older codes without `ips` still parse; `host` is the only candidate.

## Discovery

After pairing, a device announces itself and looks for the paired device id:

1. mDNS query and announcement for `_stellarsend._tcp.local` on 224.0.0.251:5353. The TXT record is `id=<device uuid>`. The SRV record carries the TCP port. The A record carries a local IPv4 address.
2. UDP broadcast on port 53320. The datagram is JSON `v`, `app` (`stellarsend`), `id`, `port`, `name`, at most 512 bytes. Datagrams that contain `pk`, `private`, or `secret` are ignored.
3. A TCP probe of the manual IP, the last-known IP, and each extra address learned from pairing.
4. On a phone-hotspot gateway only (`192.168.43.1`, `192.168.49.1`, `192.168.137.1`, `172.20.10.1`, `192.168.225.1`), a probe of that gateway plus at most 16 addresses in its /24. The last-known host octet is included when it falls in that prefix. `.0`, `.1`, `.255`, and the device's own address are skipped. A home gateway such as `192.168.1.1` does not start a range probe.

The whole search budget is 4 seconds. Probes run a few at a time and stop when one connects. A hanging TCP connect to a stale last-known address (for example a home `192.168.1.x` while the phone is off that LAN) is aborted when the budget ends. A listener treats a TCP connection that sends no bytes within a short wait as a probe and closes it, so probes do not occupy the TLS handshake. Public addresses are never probed. If nothing answers, the sender wakes the hosted relay at `wss://stellar-send-relay.onrender.com/v1` and delivers through it. The LAN-miss error still names the checks that ran; same-Wi-Fi and USB stay available.

While a pairing window is open, the UDP beacon may also include `code`, the 8 character short code taken from the fingerprint. It is not a secret and it is not a private key. A beacon without that field is unchanged.

## Relay

Both apps use `wss://stellar-send-relay.onrender.com/v1` unless a person saves a different address. Clearing the address and saving it stays on Wi-Fi only. `wss` or `https` on port 443 is the public form. The socket path is `/v1` (a trailing slash is the same socket). `ws` and `http` are accepted only for `localhost` and `127.0.0.1`. No inbound port is required. `GET /healthz` returns `200` and JSON `{"ok":true,"store":"upstash"}` or `"file"`. `GET /privacy` serves the privacy policy. A `404` on `/healthz` still means the process is awake; connection failures are what the apps retry.

The first text frame is `{"v":1,"room":"<64 lowercase hex>","role":"listen"|"dial"}`. A phone that has a Play purchase may add `purchaseToken`, `productId`, `basePlanId`, `packageName`, and `entitlementKey`. An older join may instead add `purchase` and `plan` (`free`, `pro`, `plus`, or `max`). Those fields are optional. A frame without them is the same JSON as before. The server answers `{"v":1,"ok":true}` and may add `tier`, `used`, `limit`, and `enforced`. Clients treat any object with `"ok":true` as ready. Then the server copies binary frames between the one listener and the one dialer in that room. A second waiter of the same role replaces the previous waiter. The bytes after the join are the devices' own TLS session. The relay does not have the pairing keys and does not see file contents.

Each forwarded WebSocket message is at most 8 KB (under the common 64 KB proxy cap). The apps send a ping every 10 seconds so an idle proxy does not send `NormalClosure`. A duration or idle close mid-transfer is retried from the last acknowledged offset. Fair-use quota still closes the socket when the monthly cap is used up.

A usage probe is a join with `"op":"usage"` and no room. The relay answers with the same usage fields and closes. At the monthly cap the relay answers `{"v":1,"ok":false,"error":"quota","message":"..."}` or sends that text frame and closes. Local and direct transfers never consult this cap.

The entitlement key is SHA-256 hex of `stellarsend-quota-v1|<phone device id>`. The phone sends it as Play's obfuscated account id and on the join. The paired PC stores the key from HELLO and presents it when it joins. HELLO may include `plan`, `quotaKey`, `relayUsed`, and `relayLimit`. Those fields are omitted when empty, so an older hello stays byte-for-byte the same.

The paired room is SHA-256 of `stellarsend-room-v1|<lower uuid>|<higher uuid>` with the ids in lowercase D format, sorted by byte order. During pairing, the showing device listens on SHA-256 of `stellarsend-pair-v1|<showing device id>` and the scanner dials that room. While the pairing window is open, that pairing room is used even if a peer is already stored. After the splice, the dialer is the TLS client and the listener is the TLS server. A confirmed pair closes the window, and the next listen uses the paired room.

Known vectors: devices `11111111-1111-1111-1111-111111111111` and `22222222-2222-2222-2222-222222222222` share room `8d691f26b6b708eb4b0a058b4ab26c3550f9b2006086aba986f692df67957a74`. Pairing shown by `11111111-1111-1111-1111-111111111111` uses `1b0b8e985e76c507f6a56dd97199f4a92ea67e8b78479e84df7e13d23e96e186`. That phone's entitlement key is `c30c56297025c9d0cdca357ebae9c3526f97f1155bf7938d10e243bd44bd6875`.

### Bot relay hub (relay-side, legacy)

The relay server still accepts `op=bot` frames from pre-1.5.21 clients. FlipSend 1.5.21+ does not send or poll these. The bot messages feature was removed from the phone and PC apps.

Join: `{"v":1,"op":"bot","room":"<64 hex>","role":"put"|"get"}` plus the usual optional entitlement fields. The server answers the same ready object, then:

- **put**: the client sends one more text frame `{"id":"<uuid>","blob":"<base64url ciphertext>"}`. The relay stores that blob for 30 minutes (at most 32 notes per room, 12 KiB blob). It never decrypts.
- **get**: the relay answers `{"v":1,"ok":true,"messages":[{"id","blob","expiresAt"}]}` and leaves the notes in place until they expire.

Caps live in `relay/tiers.json` and in relay env vars, so a limit change does not need an app update. The relay counts bytes it writes to the peer sockets. Free accounts get about 100 MB per calendar month. Permanent Elite gets 1 GB. All free accounts share a 50 GB monthly pool, and each free account is limited to 5 MB per minute. When the pool is used up the relay says `Relay busy, Wi-Fi transfers still work`. Elite and unlimited owner accounts are outside that pool. Unlimited is only the two owner emails, and only when the join carries a server-checked HMAC (`account` and `accountProof`). A client flag cannot grant it. If `RELAY_UNLIMITED_SECRET` is unset, nobody is unlimited. Local, LAN, and USB transfers never consult these caps. When no Play service account is configured, a claimed paid product stays on the free cap.

The status text is `Direct on Wi-Fi`, `Through relay`, or `Waking relay…`. An empty relay URL skips this path.

## USB

USB is not part of the framed protocol. The phone copies a file into its app outbox. The PC, after a person confirms, runs `adb pull` or `adb push`. `adb` is not bundled. Remote paths must stay under `/sdcard/` or `/storage/` and must not contain `..`.

## Stellar memo

`Stellar memo (text only)` is a named interface for a future encrypted memo. Version 1 returns not-implemented for text and rejects files. It does not create keys, talk to a network, or implement a time lock.

Memo commands are a different channel. The PC would watch the user's Stellar account and run a small verified command without a live link to the phone. The interface and the stub are in `docs/MEMO_COMMANDS.md`. This version does not watch an account or read a seed. Remote Browser remains the live local plugin.
