# FlipSend relay

Both the phone and the PC connect **out** to this process. It pairs two peers that present the same room token and then copies bytes from one socket to the other. Those bytes are the devices' own TLS session. The relay does not have their keys and does not read the files.

The built-in address is:

`wss://stellar-send-relay.onrender.com/v1`

`GET /healthz` returns `200` and JSON `{"ok":true,"store":"upstash"}` or `"file"`. The WebSocket path is `/v1` (and `/v1/`).

## Caps

`relay/tiers.json` is the cap table for the free relay fallback. This process reads it. Tests fail if the free allowance would cost more than the app price covers at the rates in that file.

Egress is the bytes the relay writes onto the peer sockets. One frame may land a few kilobytes past the cap; the next frame stops. Local, LAN, and USB transfers never reach this process and are never counted.

Free accounts get about 100 MB of relay traffic per calendar month (`fairUseGb` 0.1, or `RELAY_FAIR_USE_GB`). Permanent Elite accounts in `RELAY_FAMILY_ELITE`, and Elite keys in `RELAY_ELITE_ACCOUNTS`, get 1 GB (`eliteRelayGb`, or `RELAY_ELITE_GB`). It resets on the 1st (UTC). All free accounts together share a monthly pool of 50 GB (`freePoolGb`, or `RELAY_FREE_POOL_GB`). Each free account is also limited to 5 MB per minute (`freeRateMbPerMin`, or `RELAY_FREE_RATE_MB_PER_MIN`). When the pool is used up, free accounts hear that the relay is busy. Wi-Fi and USB keep working. Elite and the unlimited owner accounts are outside that pool and that rate limit.

## Run

From the repository root, with the .NET 10 SDK:

```bash
PORT=8080 dotnet run --project desktop/src/StellarSend.Relay/StellarSend.Relay.csproj
```

The process listens on `http://0.0.0.0:$PORT`. A reverse proxy should terminate HTTPS on 443 and forward `/v1` here. Leave the relay URL blank in the app to stay on Wi-Fi only.

Browsers need CORS on the HTTP responses. Every response includes `Access-Control-Allow-Origin: *`, and `OPTIONS` returns 204. The WebSocket splice does not check `Origin` and did not change. Deploy this process again before the web client can call `/healthz`. The proxy must pass the CORS headers through.

Docker, from the repository root:

```bash
docker build -f relay/Dockerfile -t stellar-send-relay .
docker run --rm -p 8080:8080 stellar-send-relay
```

A free-tier host that can run a container, or a small VM, is enough. The relay forwards ciphertext only. Monthly usage counters are separate from the files.

Each direction keeps about 2 MB of ciphertext queued. When that fills, the relay waits, which slows the sender's TCP instead of growing RAM. WebSocket pings go out every 15 seconds. A listen socket that closes is dropped so the next dial waits for a live peer. Close reasons are written to the process log.

## Deploy

The live service is the Render Docker web service **stellar-send-relay** (`https://stellar-send-relay.onrender.com`, WebSocket path `/v1`). It was created in the Render dashboard. `render.yaml` names that same service so a blueprint sync does not create a second URL.

Until the blueprint sync is in effect, redeploy the code by hand:

1. Merge the relay change to `main`. Do not create another Render service.
2. Render Dashboard → **stellar-send-relay** → **Manual Deploy** → **Deploy latest commit**.
3. Open `https://stellar-send-relay.onrender.com/healthz` and confirm it returns 200.

`autoDeployTrigger: commit` in `render.yaml` makes later pushes to the linked branch deploy this service after Render has synced the blueprint. Environment variables stay in the dashboard.

## Caps

| Variable | Meaning |
| --- | --- |
| `PLAY_SERVICE_ACCOUNT_JSON` | Service-account JSON, or a path to that file. When unset, plan caps are not checked. The fair-use cap still is. |
| `TIERS_PATH` | Plan table. Defaults to `tiers.json` beside the binary. |
| `RELAY_USAGE_PATH` | Cap ledger. Defaults to `relay-usage.json`. Put this on a persistent disk. The Render free disk is replaced with the instance, and the cap resets when that file is lost. |
| `UPSTASH_REDIS_REST_URL` | With the token below, owner usage counters survive a restart. |
| `UPSTASH_REDIS_REST_TOKEN` | Upstash REST token. Both Upstash variables are required. Otherwise the owner counters use the file store. |
| `RELAY_USAGE_FLUSH_SECONDS` | How often owner counters flush. Default 120, clamped to 30–3600. |
| `RELAY_ADMIN_TOKEN` | Bearer token for `GET /v1/admin/usage` and `POST /v1/admin/cap`. Both routes are 404 when this is unset. |
| `RELAY_FAIR_USE_GB` | Free monthly allowance in decimal GB. Defaults to `fairUseGb` in `tiers.json`, which is 0.1 (100 MB). |
| `RELAY_ELITE_GB` | Permanent Elite monthly allowance in decimal GB. Defaults to `eliteRelayGb`, which is 1. |
| `RELAY_FREE_POOL_GB` | Monthly cap for all free accounts together, in decimal GB. Defaults to `freePoolGb`, which is 50. |
| `RELAY_FREE_RATE_MB_PER_MIN` | Per free account, decimal MB forwarded in one minute. Defaults to `freeRateMbPerMin`, which is 5. `0` turns the rate limit off. |
| `RELAY_ELITE_ACCOUNTS` | Comma-separated entitlement keys treated as Elite. They get the 1 GB allowance, not unlimited. |
| `RELAY_FAMILY_ELITE` | Comma-separated entitlement keys for permanent friends-and-family Elite. They do not expire. They get the same 1 GB allowance. |
| `RELAY_UNLIMITED_ACCOUNTS` | Comma-separated emails allowed to be unlimited. When unset, the server uses stellartimelock@gmail.com. |
| `RELAY_UNLIMITED_SECRET` | HMAC secret. Unlimited is granted only when the join's email is on the list and `accountProof` is HMAC-SHA256 of `stellarsend-unlimited-v1\|email` with this secret. If this is unset, nobody is unlimited. |

`GET /healthz` returns `200` and JSON that names the owner-counter store (`upstash` or `file`). `GET /privacy` serves the privacy policy. The admin JSON has month totals, today’s bytes, subscriber counts by tier, free users, users over 80% of their cap, estimated egress at $0.15/GB, uptime, the store name, and `fairUseGb`. It does not list purchase tokens or user ids. See `docs/PLAY_STORE.md`.

`POST /v1/admin/cap` uses the same bearer token. The body is `{"account":"<entitlement key>","action":"raise"|"lower"|"reset","capGb":80}`. `capGb` is required for raise and lower. Raise must be above the account's current cap. Lower must be below it. Reset removes the override, restores the server default, and sets this month's relay count back to zero. The response does not repeat the account id. A wrong token is 401. A missing token hides the route with 404.

The verifier calls only `https://androidpublisher.googleapis.com` and `https://oauth2.googleapis.com`. It does not log purchase tokens. A Google error fails closed to the free cap, not to unlimited.

The phone presents the Play token. The PC presents the entitlement key it learned from the phone. The higher cap in the room covers both seats. The cap ledger is what stops a transfer. The Upstash counters are the owner report and are not a second cap.
