# Changelog

## Unreleased (fdroid-prep)
- Google Play is dropped. The `play` and `playBeta` flavors, Play Billing, the $2.99 unlock card, the Play upload-key signing config and the `play/` listing folder are gone. The app is free for everyone.
- Licensed GPL-3.0-or-later. F-Droid metadata in `fastlane/` and a draft fdroiddata file in `fdroid/`.
- Release builds are unsigned in Gradle so F-Droid can sign them. `-PflipsendCloudUpdate=false` turns off the built-in update check from flipsend.app (F-Droid builds use it). Default builds keep it on.
- No feature changes. Remote desktop, app installs from the PC and the relay work as before.

## 1.5.63
- Windows: when the relay is up but the phone does not join the room, the status line and the transfer row say "Phone is offline". The send stays queued and goes again automatically (1, 2, then every 5 minutes, and at once when the phone connects). The 1.5.62 "Re-pair needed after two misses" guess is gone; Re-pair needed now only appears after the phone really rejects this PC's key or pin (key_changed after retries, or unknown_peer). The phone-side Forget confirm (1.5.62) and the Forget fix (1.5.61) stay.
- Phone: idle relay presence. After a relay drop the phone rejoins under a short timed CPU hold (90 s cap, first 3 quick rejoins only) instead of a backoff that stalls while the phone sleeps, and an inexact Doze-friendly alarm retries every ~5 minutes until it is back. Screen-on, a network or Wi-Fi change (including no-change callbacks), and app open rejoin the relay immediately and re-announce on LAN. A network hop now also closes the socket that was waiting in the relay room. Relay join and drop reasons are logged ("Relay joined", "Relay dropped: reason", "Relay rejoin: reason"). Nothing is held while the phone waits in its room; 1.5.57 intervals (30 s LAN announce, 45 s ping, 1-60 s backoff) are unchanged. VPN/LAN binding (Surfshark) unchanged.
- Phone 1.5.63 (versionCode 73, Play beta 731). Windows 1.5.63. Signing unchanged. No new key.

## 1.5.62
- When the phone stops answering this PC (relay up, phone never joins the room two sends in a row), Windows says "Re-pair needed" instead of looping on "phone did not join the room". Re-pair is one tap straight to the QR; the old pairing still stays until the phone confirms.
- The pairing dropped because Forget on the phone is one tap and, since 1.5.61, sticks, but the PC is never told. Forget on the phone now asks first.

## 1.5.61

- Forget on the phone now sticks. A connection or companion report (for example install.result) that was already running kept the old PC and wrote peer.bin back after Forget. Those paths no longer re-save a forgotten PC; only Scan a code / pairing pairs again. Production and sideload versionCode 71. Play beta versionCode 711. Signing unchanged. No new key.

## 1.5.58

- Trust new key: an already-paired PC whose presented TLS pin matches the saved pin never raises the prompt. The prompt is only for a real mismatch of two well-formed pins.
- A pending Trust offer is dropped (not shown) once the saved pin matches it, and a later handshake that presents the saved pin clears it. Tapping Trust with the saved pin writes nothing.
- A connection that loops back to this phone (its own key) is ignored before the pin check, and this phone's own key is never offered or saved as the PC pin.
- An incoming handshake that fails reads the pin from that handshake only. A pin from an earlier connection is not a presented pin.
- Windows: the home window shows Send, Send text, Send copied text, **Remote desktop**, pairing status, and the footer with no page scroll. Only the transfers list scrolls. Pairing/QR collapses when a phone is already paired. Phone 1.5.58 (versionCode 69, Play beta 691). Windows 1.5.58. Signing is unchanged. No new key.

## 1.5.57

- Phone battery: TransferService no longer holds the Wi-Fi lock or a partial wake lock while idle. Those locks are taken only during an incoming or outgoing transfer, ref-counted, with a 10-minute wake timeout. Wi-Fi uses LOW_LATENCY when the phone has it, otherwise HIGH_PERF.
- LAN announce is skipped off Wi-Fi. Fast 3 s beacons run only during pairing/repair and for 2 minutes after screen-on, a network change, app foreground, or a transfer. Otherwise every 30 s.
- Relay client keepalive ping is 45 s. The phone still answers server pings, including while waiting to join, so a ping is not treated as a join reply (the ~85 s idle drop). The relay server now reads the waiting socket so those pongs are processed. Idle disconnects log the WebSocket close code. Rejoin backoff is 1, 2, 5, 15, 30, then 60 s, reset after a join that stays up more than 30 s.
- The duplicate TransferService network callback is gone. Engine's debounced callback still re-announces and rebinds on a real network change (VPN/Wi-Fi bind from 1.5.56 stays).
- Phone version 1.5.57. Production and sideload versionCode 68. Play beta versionCode 681. Windows stays 1.5.56. Signing is unchanged. No new key.

## 1.5.56

- VPN: Surfshark and other tun/CGNAT addresses are not advertised, not probed as LAN, and not used as relay room identity. Room membership is the device id, so a PC cannot pair with itself. The phone binds LAN sockets to Wi-Fi when the VPN is bypassable, and skips LAN for the relay when it is not. Windows binds LAN dials to the physical Wi-Fi or Ethernet address.
- Relay: WebSocket keepalive is 10 seconds and slices are 8KB (under the 64 KB proxy cap). HELLO may include `resume: true`. When both sides support it, the receiver flushes and acks every 1 MB and the sender waits, so a mid-transfer NormalClosure resumes from that offset instead of from zero, in both directions.
- Status: **A VPN is on. Using the relay.** When the phone VPN is not bypassable: **For faster Wi-Fi transfers, add FlipSend to your VPN's bypass list.**
- Trust: phone-to-PC no longer shows Trust new key when the presented TLS pin matches the stored pin, or when no cert was on the socket (`presented=none`). A different well-formed pin is still rejected. The PC presents the pairing identity as the TLS server cert. Pairing handshake is not weaker.
- 1.5.55 stays: one short PC status line, **Disconnect phone**, fallback key_changed retry, own-key self-dial, CopyToPcService startForeground.
- Phone and Windows version 1.5.56. Production and sideload versionCode 67. Play beta versionCode 671. Signing is unchanged. No new key.

## 1.5.55

- The PC window uses one short status line (Connected / Phone away, using relay / Can't reach phone). mDNS, relay wake, retry counts, and exception text sit behind Details or the log. Finished transfers stay on the first screen. **Remote desktop** keeps that label.
- Off-home Wi-Fi says the phone isn't on this Wi-Fi and FlipSend is sending through the relay. A relay drop says it dropped and FlipSend is retrying. That is not a broken pairing.
- **Disconnect phone** is in the ··· menu. It asks first, copies peer.bin to a timestamped .bak, then unpairs. It never runs without that confirm.
- A single key_changed on relay or Bluetooth, when this pin worked in the last 7 days, retries (Retrying.) instead of telling you to unpair both sides. A key_changed on Wi-Fi, or three in a row, still stays terminal. The log names local check vs phone-sent key_changed and the first 8 hex of the pins. Same TLS comparisons.
- If Surfshark (or another VPN) gives the phone and PC the same tunnel address, the PC can dial itself and see its own key. That is a self-dial: FlipSend retries the relay instead of saying the key changed. When a VPN tunnel is on, the status line is **A VPN is on. FlipSend may not find your phone over Wi-Fi.**
- Phone: CopyToPcService (`SEND_FILE_TO_PC` / `COPY_TO_PC`) calls startForeground before it checks the caller, then stops the foreground service on reject or finish. A rejected caller (adb shell) no longer crashes the app or RemoteControlService.
- Phone and Windows version 1.5.55. Production and sideload versionCode 66. Play beta versionCode 661. Signing is unchanged. No new key.

## 1.5.54

- Fortress password-bot file handoff only. A paired-PC `fortress-bot-request.json` or `fortress-bot-failed.json` is handed to Fortress with `BOT_HANDOFF` extras `name` and `content` (UTF-8) plus a FileProvider URI. `fortress-bot-response.json` is not routed back to Fortress. It is not a received-list row and its contents are never logged.
- Fortress may fire `SEND_FILE_TO_PC` then `COPY_TO_PC` with `kind=file`, `name`, `content`, and `source=stellar-vault`. FlipSend writes the inline content to a temp file, sends it, and deletes the copy. STREAM/uri is a fallback. The file is never copied to the clipboard. Unpaired peers are rejected.
- Windows writes that response under `%LOCALAPPDATA%\StellarSend\inbox` with the exact name and overwrites. It does not prompt or open the file. PC transport, pairing, TLS, and identity stay the 1.5.46 code. No new key.
- Phone and Windows version 1.5.54. Production and sideload versionCode 65. Play beta versionCode 651. Signing is unchanged. No new key.

## 1.5.53

- Phone home puts Received, Apps, Remote desktop, and Check phone screen directly under Send, with tighter 48dp rows. The home button is **Remote desktop** again, not Control PC, and the label has no "(beta)". Design C colors, type, and the icon stay. Phone TLS, pinning, Trust, and pairing are unchanged.
- Windows: the PC window matches the phone Design C home: one window, gold Send and Receive at the top, recent transfers on the first screen, pairing rows in the QR card, Linked plus one meta line, existing B&W icon.
- A busy network port after a restart is a plain-words retry banner, not "Something went wrong". The banner clears once FlipSend is listening again.
- PC transport, pairing, TLS, and identity.bin load are the 1.5.46 code. identity.bin and peer.bin stay in `%LOCALAPPDATA%\StellarSend`. No new key.
- Phone and Windows version 1.5.53. Production and sideload versionCode 64. Play beta versionCode 641. Signing is unchanged. No new key.

## 1.5.52

- Trust new key on the phone writes the seen pin to app-private peer.bin and reloads it. The Trust tap no longer loops when the PC has not written this phone's pin yet. The phone TLS check captures the PC cert and finishes the handshake instead of closing first. Forget plus Scan a code from either side rewrites both pins. Both sides log the pin before and after Trust.
- Phone and Windows version 1.5.52. Production and sideload versionCode 63. Play beta versionCode 631. Signing is unchanged. No new key.

## 1.5.51

- Trust new key works when the other side closes TLS first. The PC captures the presented cert before it rejects. The phone no longer silently closes on a new PC pin: it finishes the handshake, shows Trust new key, and auto-trusts the already-paired PC after Trust. Forget plus Scan a code from either side rewrites both pins.
- Phone and Windows version 1.5.51. Production and sideload versionCode 62. Play beta versionCode 621. Signing is unchanged. No new key.

## 1.5.50

- Trust new key on Windows writes the seen pin to `%LOCALAPPDATA%\StellarSend\peer.bin` even when the handshake threw AuthenticationException. The TLS check no longer keeps the old pin after Trust. The Trust tap retries the handshake right away.
- Phone and Windows version 1.5.50. Production and sideload versionCode 61. Play beta versionCode 611. Signing is unchanged. No new key.

## 1.5.49

- Phone home, Settings, and Received use Design C list rows. Scan a code and Type a code sit in the QR card. Send is the one gold button. Connections, Transfers, and Apps cards fold into a Linked header and one relay meta line. The footer is two pills: StellarTimeLock site and Play Store.
- Trust new key on Windows writes the new pin to peer.bin and keeps it when the last host updates. The next handshake uses that pin. Phone and PC show one line when keys do not match: Paired, keys don't match, scan code to fix.
- Phone and Windows version 1.5.49. Production and sideload versionCode 60. Play beta versionCode 601. Signing is unchanged. No new key.

## 1.5.48

- Both sides now notice a pin mismatch. A closed TLS handshake on Wi-Fi, Bluetooth, or the relay shows Trust new key for the already-paired device. Home no longer says Connected when that handshake failed.
- Scan a code / Type a code writes both pins again. Repeat pairing through the same-device merge still keeps the stored pin. Trust with no presented pin accepts the next handshake.
- Phone and Windows version 1.5.48. Production and sideload versionCode 59. Play beta versionCode 591. Signing is unchanged. No new key.

## 1.5.47

- The pairing identity in identity.bin is the remote-desktop TLS cert. There is no second cert. Windows now keeps that private key inside the PFX so swapping StellarSend.exe for FlipSend.exe cannot mint a new pin. A readable identity.bin, backup, or FlipSend/StellarSend copy is never replaced.
- Remote desktop no longer dead-ends on "paired device key changed." The phone shows Trust new key with the PC name, then re-pins. Repeat pairing still keeps the stored pin.
- Phone lists no longer freeze the UI on a long received history. Home, Received, and Apps page their rows. The pairing pin and received.json stay in memory after the first read. The home QR is not rebuilt on every status tick.
- Phone and Windows version 1.5.47. Production and sideload versionCode 58. Play beta versionCode 581. Signing is unchanged. No new key.

## 1.5.46

- Windows 1.5.45 crashed on launch: the exe was renamed to FlipSend, but the tray still loaded `avares://StellarSend/Assets/icon.png`. Asset URIs now use the loaded assembly name. Startup and a test open every avares asset so this cannot regress.
- Phone and Windows version 1.5.46. Production and sideload versionCode 57. Play beta versionCode 571. Signing is unchanged. No new key.

## 1.5.45

- Repeat pairing with the same phone keeps the stored pin. Hosts can update. peer.bin is not rewritten during an open re-pair, and cancel restores the old trust.
- The phone stays in the paired relay room while a re-pair is open, and it rejoins that room after a drop. The PC does the same.
- Auto-install reports install.result again. If the first call misses, FlipSend tries two more times. Skipped or already-current apps still get a line on the PC.
- Phone Home puts Scan a code, Send text, and Send on the first screen. Connections, Transfers, and Apps sit below. Windows matches that layout.
- Sideload checks https://flipsend.app/update.json only when FlipSend is opened or a push asks. A newer build signed 7994727d downloads and prompts Install. No polling.
- Settings can email a one-time FlipSend invite. The code works once and expires in 24 hours.
- APK output names and the Windows exe say FlipSend. Package IDs stay org.stellartimelock.stellarsend.
- iPhone and the browser show the real caps: 100 MB of relay a month, 5 MB a minute, and 48 MB per file in the browser. The 50 GB figure is the shared pool. Received files can be opened or saved to Photos, and the in-progress line clears to Received.
- Remote desktop: the live view sits between Disconnect / Keyboard and the system nav bar. Status bar, cutout, and nav bar stay clear.
- One purchase unlocks the whole app. Remote desktop is the headline perk in Settings, the unlock card, and Play listing copy. Production Play still hides the in-app remote-desktop UI.
- Phone and Windows version 1.5.45. Production and sideload versionCode 56. Play beta versionCode 561. Signing is unchanged. No new key.

## 1.5.44

- Phone and PC can send files over Bluetooth when Wi-Fi is not there. FlipSend tries Wi-Fi, then Bluetooth, then the relay. Bluetooth is a normal RFCOMM connection with the same pairing certificates as Wi-Fi. It does not use the relay allowance.
- The Connections card no longer says Bluetooth is Not supported. The badge is Connected, Available, Off, or Not set up.
- Settings has a Bluetooth checklist. The button asks for Nearby devices permission, or opens Bluetooth settings when Bluetooth is off, or opens app settings if that permission was denied.
- Phone and Windows version 1.5.44. Production and sideload versionCode 55. Play beta versionCode 551. Signing is unchanged. No new key.

## 1.5.43

- Free relay is about 100 MB a month. Permanent Elite is 1 GB a month. All free accounts share a 50 GB monthly pool, and each free account is limited to 5 MB a minute. The Connections card shows Relay: X of Y MB used this month. When the pool is used up, free accounts see "Relay busy, Wi-Fi transfers still work." Wi-Fi transfers still work when a personal allowance is used up. Unlimited relay is only stellartimelock@gmail.com and stellartimelock@gmail.com, and the relay checks an account proof. A client flag cannot grant it. The numbers live on the relay (`relay/tiers.json` and env vars), so they can change without an app update. The relay service has to be deployed before the new caps apply. The web cache is `flipsend-web-8` so the landing line updates.
- Phone home splits the old status line into three Aurora Glass cards. **Connections** is one row per method (icon, name, and Connected / Available / Off / Not set up). Bluetooth is **Not supported**. **Transfers** is its own card. Received APKs sit in an **Apps** card with name, version, time, and Install or Update. Newest first. Older rows are behind Show more.
- **Check phone screen** is a small button with a Beta badge. It is the old Share screen control: it asks Android to capture, then shows this phone on the paired PC. **Remote desktop** stays where it was, with a Beta badge. The production Play build still hides remote desktop.
- Phone version 1.5.43. Production and sideload versionCode 54. Play beta versionCode 541. Windows stays 1.5.43. Signing is unchanged. No new key.
- Sending or receiving a file on Windows no longer pulls FlipSend in front of other apps. A short tray notice names the file. Settings has **Bring window to front on transfer**. It is off unless you turn it on. With it off, a file from the paired phone is saved without an Accept tap. Remote desktop still opens its Allow window. A second launch still shows the window.

## 1.5.42

- Remote desktop touch on the phone no longer drag-selects when you move around the computer. One finger moves the cursor, or pans the picture when it is zoomed in, and the mouse button stays up. A tap clicks. Pinch zooms. Drag-select starts only after a long-press, or a two-finger drag that is not straight up or down. Two fingers moving up or down still scroll. A small movement stays a tap. The cursor eases toward the finger instead of jumping. A move on the computer does not press the mouse button.
- Phone version 1.5.42. Production and sideload versionCode 53. Play beta versionCode 531. Windows stays 1.5.36. Signing is unchanged. No new key.

## 1.5.41

- Home has a compact StellarTimeLock box in that app's Aurora Glass colors: background `#0A0E17`, accent `#5E9FFF`, gold `#E8B84A`. It does not use FlipSend's colors. The line is "Lock XLM until the future arrives." Tap opens StellarTimeLock, or the Play listing when it is not installed.
- The English Play listing does not mention remote desktop or relay plans. The only purchase named there is the $2.99 unlock. The relay in that listing is the free fallback.
- Phone version 1.5.41. Production and sideload versionCode 52. Play beta versionCode 521. Windows stays 1.5.36. Signing is unchanged. No new key.

## 1.5.40

- The production Play dex has no "Remote desktop" phrase. That screen, its buttons, and those words stay on sideload and `playBeta`.
- If the computer closes the remote-desktop request after Allow, the phone opens the watch connection and the picture keeps moving. That includes a close that happens after one frame was already sent. The computer no longer reads and writes that secure stream at the same time.
- Phone version 1.5.40. Production and sideload versionCode 51. Play beta versionCode 511 (base * 10 + 1). Windows stays 1.5.36. Both Play AABs use the existing upload key. Sideload stays on 7994727d. No new signing key. Promo price stays $2.9999. The Play product stays `flipsend_unlock` at $2.99.

## 1.5.39

- Remote desktop is not in the regular Play release. `BuildConfig.REMOTE_DESKTOP` is false on the `play` flavor. The buttons, the settings card, the screen activity, the notification name, and the phone → PC protocol stay off. The words are not shown.
- `playBeta` is the same Play build with remote desktop on, labeled **Remote desktop (beta)**. Sideload keeps it with that same label. Production versionCode is 50. Beta versionCode is 501 (base * 10 + 1). Both Play AABs use the existing upload key. Sideload stays on 7994727d. No new key.
- `:app:playReleaseAab` is the production and internal AAB. `:app:playBetaReleaseAab` is the open or closed testing AAB. Which file goes to which track is in `play/README.md`.
- Phone version 1.5.39. Windows stays 1.5.36.

## 1.5.38

- FlipSend no longer sells relay plans. The only purchase is the one-time unlock: $2.9999 in the app and on the site, and `flipsend_unlock` at $2.99 on Play.
- The Data plans button, the plan chart, and Play Billing for those products are gone. The free relay fallback stays. When that cap is used up, the message says to use the same Wi-Fi.
- The web landing price line is a small Bot Link note: coming soon, relay plans from $5/mo. Service worker cache is `flipsend-web-7`. Redeploy `web/` to the Cloudflare Pages project `flipsend-web`.
- Phone version 1.5.38 (versionCode 49). Windows stays 1.5.36. Sideload signer stays 7994727d. No new signing key.

## Play Store package

- The Play build is `./gradlew :app:playReleaseAab`, signed with an upload key from `FLIP_UPLOAD_*`. Sideload builds stay on the existing debug signer. The Play flavor drops accessibility remote control, APK install, the full app list, the full-screen install alert, the battery-exemption prompt, and the dataSync foreground type. The Play product `flipsend_unlock` is $2.99. Promo copy stays $2.9999. Listing text is in `play/listing/en-US/`. The public page is `site/`. Steps were in `play/README.md`.

## 1.5.37

- Google Play requires target API 36. compileSdk and targetSdk are 36 on the app and the Android library modules. Android Gradle Plugin is 8.13.2.
- The always-on listen starts as connectedDevice on sideload and Play. dataSync times out after 6 hours and cannot start from boot. If Android still reports that timeout, the service stops and the listen comes back as connected device.
- Share screen asks for the whole display. That consent is used once. Edge-to-edge stays on, and the remote-desktop bars stay clear of the status bar, navigation bar, and keyboard. Back uses the predictive-back callback.
- Nearby Wi-Fi remains the local-network grant. Native libraries stay uncompressed so Play can zip-align them to 16 KB. No new signing key.
- Phone version 1.5.37 (versionCode 48) for both flavors. Windows stays 1.5.36. Sideload signer stays 7994727d. Promo price stays $2.9999. The Play product and listing are $2.99.

## 1.5.36

- After the computer allowed remote desktop, it treated the request as a finished transfer, closed the link, and showed a file progress bar. An allowed request now stays open, starts screen capture, sends frames, and reads taps from the phone. The window says **Remote desktop active - Disconnect**. Each step is in the log. A phone still on 1.5.34 closes that request and opens a watch connection; the Allow stays so that watch can start. If the computer cannot capture, it tells the phone to update the FlipSend app.
- A send from the PC could reach this computer, or pair with the PC's own relay listener, then say the phone answered and that the relay was still waking up. The relay was already up. The PC now skips its own address and its own certificate, leaves the relay room before it dials, and says the phone did not join when the relay is up and the phone is not in the room.
- The phone accepts the next connection on its own thread so one handshake cannot block the listen socket. The foreground service stays awake while it is listening and rejoins the relay room in about a second on Wi-Fi.
- Phone version 1.5.36 (versionCode 47). Windows version 1.5.36. Same debug signer. Price stays $2.9999.

## 1.5.35

- Remote desktop on 1.5.34 closed the phone screen and said "This computer no longer recognizes this phone" after a network switch. The pairing file on the PC had not changed, and the PC log never said it rejected the phone. A TLS `certificate_unknown` alert (the link closed, or this device met its own relay listener) was rewritten into that re-pair sentence, then repeated. The phone screen now stays open, shows the real reason, and tries once more. Only a finished handshake that names a different key may ask for a re-scan, and that line is not repeated in a loop.
- The PC logs each remote-desktop step (request, auto-allow, capture, frames, and the exception if the secure connection closes). It does not listen in the paired relay room while it is dialing that room. Enlarge shows a large code with a close button and does not move the paired relay. A dropped session can be started again. Frames go out in small relay messages. Capture uses the whole desktop, including a monitor to the left of the primary.
- A second Windows launch shows the window that is already running, then exits. Closing the window hides it. The tray icon stays, with Open and Quit. Install turns on start with Windows. Tile icons are drawn in the window.
- Phone version 1.5.35 (versionCode 46). Windows version 1.5.35. Same debug signer. Price stays $2.9999.

## 1.5.34

- Remote desktop. From the phone you see the paired computer's screen and control it: tap, drag, long-press, two-finger scroll, and a keyboard. The computer shows one Allow, with always allow this phone. Same Wi-Fi, the direct link, or the relay.
- 1.5.18 sent that tap to Android's Accessibility list (`android.settings.ACCESSIBILITY_SETTINGS`). On a OnePlus that page is the system list, not FlipSend, and a restricted-settings switch snaps off and leaves you there. Remote desktop does not open it. Control this phone is its own setting, with a checklist that opens the FlipSend service page and app info, and it does not send you back.
- Phone version 1.5.34 (versionCode 45). Windows version 1.5.34. Same debug signer. Price stays $2.9999.

## 1.5.33

- When the phone and the PC are on two different Wi-Fi networks, FlipSend tries a direct encrypted connection. The relay only introduces them. The file uses that link, with the same pairing keys. If the link does not open, the file goes through the relay and can still resume.
- The status says Direct while that link is up.
- The phone and the PC both drop old sockets and listen again when their own network changes. Windows waits until the new address is actually bound before it rebinds, so a network change is not missed.
- Windows no longer says "Phone not on this Wi-Fi" when the phone is answering on the LAN port. A cached address from the previous network is ignored. The search listens long enough to hear the phone's beacon, and it uses the Wi-Fi adapter that shares the phone's subnet rather than the VPN. A connect that reaches the FlipSend port is on Wi-Fi.
- Phone version 1.5.33 (versionCode 44). Windows version 1.5.33. Same debug signer. Price stays $2.9999.

## 1.5.32

- The home status is one block: the live route (same Wi-Fi, direct, relay, USB, or offline), the other routes that are ready, and a one-line hint when a better route did not answer. No second label that says the opposite.
- Transfers stay in a queue on the main page (in progress, pending, finished). A send does not open another screen.
- A network change drops the old sockets and finds the computer again. Same Wi-Fi stays direct. Otherwise the relay carries the bytes. A partial file resumes.

- The hosted relay was buffering an entire PC→phone send in RAM (unbounded channel, plus a copy of every WebSocket frame). On Render's free instance that filled memory, the sockets dropped, and Windows reported `IOException: The write operation failed` partway through a large file. A dial could also be paired with a listen socket that had already died, which showed up as "Couldn't reach your phone".
- The relay now keeps about 2 MB in flight per direction and waits when that is full, so the sender's TCP slows down. Frames are pooled instead of copied twice. WebSocket keepalive pings are on. A waiting listener is removed when its socket closes. Close reasons are logged.
- Windows sends are resumable. The same transfer id is reused. An IO failure retries up to 5 times with backoff and continues from the phone's accept `offset`. Quota or close text from the relay is shown during the upload. If the relay TLS handshake fails right away, Windows re-dials for about 60 seconds while the phone listens again.
- The phone keeps the partial file (transfer id, size, and sha) and answers accept with that offset. After a relay session it listens again quickly and answers relay pings.
- Phone version 1.5.32 (versionCode 43). Windows version 1.5.32. Same debug signer. Price stays $2.9999.

## 1.5.31

- Auto-install of an upgrade for an app FlipSend did not install (adb-installed Clock / Meme Trader) no longer stalls silently on Android 15. 1.5.30 handed PackageInstaller an Activity PendingIntent for `ApkInstallStatusActivity`. With FlipSend in the background, Android BAL-blocked that start, so `STATUS_PENDING_USER_ACTION` never arrived and no confirm or notification appeared.
- Session status now goes to `ApkInstallReceiver` as a mutable broadcast. In the background, FlipSend posts **Tap to install** and that tap opens the system confirm. In the foreground, the confirm opens directly. `received.json` is updated and the PC gets **Install Pending**. Failures report a reason. Silent updates of apps FlipSend installed itself are unchanged.
- Phone version 1.5.31 (versionCode 42). Windows version 1.5.31. Same debug signer. Price stays $2.9999.

## 1.5.30

- After a successful receive, the copy into **StellarTimeLock backups/FlipSend** now actually lands there. 1.5.29 kept the transfer in `files/inbox` and never wrote the export copy: the MediaProvider-owned folder looks present, `canWrite()` can lie, and Android 15 MediaStore will not create a top-level non-standard folder via `RELATIVE_PATH`.
- If FlipSend cannot write that folder and has no persisted SAF tree, it prompts once to pick **StellarTimeLock backups/FlipSend**. The pick is remembered with a persistable URI permission. Copies go through DocumentFile. Existing inbox files are exported after the pick.
- The transfer itself still finishes first and never waits on the picker. No All files access. Same debug signer. Price stays $2.9999.
- Phone version 1.5.30 (versionCode 41). Windows version 1.5.30.

## 1.5.29

- PC-to-phone Wi-Fi sends work again. 1.5.28 accepted the file, then tried to write `*.apk.partial` into **StellarTimeLock backups/FlipSend/**. That folder can look writable under scoped storage, then the create fails. The phone dropped the socket with no REJECT, so Windows retried once, logged nothing useful, and said "Couldn't reach your phone".
- Receive into app-private `files/inbox`. After a successful receive, copy into **StellarTimeLock backups/FlipSend/** through SAF or MediaStore when that folder is actually writable. The transfer does not need All files access.
- Every file from the paired PC auto-accepts (not just APKs). Unpaired senders still get the consent prompt; Accept/Decline is registered again, and tapping the notice opens the in-app prompt.
- A sink or setup failure sends REJECT or ERROR with a reason instead of a silent drop. The sink is opened before ACCEPT.
- Windows logs the real exception type and message on every send attempt, including the last one. Post-connect, post-ACCEPT, and REJECT failures are not labeled "Couldn't reach your phone". Row errors go to stellar-send.log. LAN TCP probes close the socket after a hit.
- Phone version 1.5.29 (versionCode 40). Windows version 1.5.29. Price stays $2.9999. Signing unchanged.

## 1.5.28

- Share screen and Direct no longer drop with `SSLV3_ALERT_CERTIFICATE_UNKNOWN` on Android 15 (OnePlus 13R). The phone's pairing cert was missing Client/Server Auth, so the phone never sent it and Windows hung up.
- Fix: new phone certs include those usages (same as Windows). An already-saved phone key is re-wrapped on first launch. The pairing pin is the public key, so the computer still recognizes this phone — no re-pair in the usual case.
- If the PC still rejects the phone (old Windows build, or the keys really differ), the phone says **Re-scan / re-pair** instead of an SSL dump. Tap that, then scan the code on the PC.
- Windows home matches the phone Design C theme: Deep Space / cream / gold / teal, FlipSend title + ···, slim Connected bar, always-on QR, Send / Phone screen / Apps / Received tiles, Send text under the tiles. Settings stay behind ···.
- Phone version 1.5.28 (versionCode 39). Windows version 1.5.28. Price stays $2.9999. Signing unchanged.

## 1.5.27

- Phone home matches layout C (dashboard + QR). FlipSend title, ··· opens Settings, slim connection bar, pairing code always on screen, then Send / Share screen / Apps / Received. The note, Send text, and Send copied text stay under the tiles.
- No new transfer features. Price stays $2.9999. Signing unchanged.
- Phone version 1.5.27 (versionCode 38). Windows in this repo stays 1.5.25.

## 1.5.26

- FlipSend can update itself over the paired link: direct Wi-Fi, or the hosted relay at `wss://stellar-send-relay.onrender.com/v1` when the phone is off-home. USB/adb is not required for FlipSend updates once this build is on the phone.
- Why it failed: a background self-update asked Android for a silent install (`USER_ACTION_NOT_REQUIRED`). On Android 14+ that often returns STATUS_FAILURE_BLOCKED — the same bug 1.5.20 fixed for other apps. Installing FlipSend also kills the process, so the in-memory queue never retried.
- Fix: background / relay self-updates use `USER_ACTION_UNSPECIFIED` so Android can show its confirm instead of blocking. A pending self-update is saved on disk and resumed after `MY_PACKAGE_REPLACED`. FlipSend stays last in a batch and is never uninstalled. Same debug signing. Price stays $2.9999.
- Phone version 1.5.26 (versionCode 37). Windows version stays 1.5.25.

## 1.5.25

- Home (when paired): **Send text** is on the main Send to PC card — type or paste, or send copied text. File and Share this screen stay on that card. They are no longer only in Settings.
- Windows: Send text stays on the main Send card. Received text gets its own card with a big **Copy** button.
- Fresher cards and pill buttons on phone and PC. Dark theme. Body text still at least 16sp / 16px.
- New true black-and-white launcher and Windows tray icon (phone → PC). Not gray. Package id unchanged.
- Phone version 1.5.25 (versionCode 36). Windows version 1.5.25. Price stays $2.9999.

## 1.5.24

- Off-home APK delivery: when the paired phone is not on this Wi-Fi, the PC stops waiting on a stale LAN address after a few seconds, wakes `wss://stellar-send-relay.onrender.com/v1`, and sends through that relay. The PC log writes **Waking relay…** on that fallback.
- The phone keeps its relay listen up off-home (cellular or other Wi-Fi). Leaving home Wi-Fi reconnects the relay. FlipSend's own APK is sent last and still auto-installs as an update. No uninstall.
- Phone version 1.5.24 (versionCode 35). Windows version 1.5.24. Price stays $2.9999.

## 1.5.23

- Phone APKs and other FlipSend saves land in **StellarTimeLock backups/FlipSend/**. No regular received-folder picker.
- Phone version 1.5.23 (versionCode 34). Windows version stayed 1.5.20. Price stays $2.9999.

## 1.5.22

- Home and Settings show **FlipSend by StellarTimeLock**. No other UI, behavior, or feature changes. The approved "Other apps by this developer" list is unchanged (FlipSend, Sourceify, Naturally + StellarTimeLock); FlipSend still does not ship that list.
- Phone version 1.5.22 (versionCode 33). Windows version stays 1.5.20. Price stays $2.9999.

## 1.5.21

- Bot messages removed. The toggle in Settings, the inbox/outbox/compose UI, the background polling, and the PC counterpart (BotHubSession) are gone. Bot-to-bot sync runs over Slack instead. The relay server still accepts op=bot frames from pre-1.5.21 clients.
- Phone version 1.5.21 (versionCode 32). Windows version stays 1.5.20. Price stays $2.9999.

## 1.5.18

- Remote control between the paired phone and Windows PC, over the same encrypted link. Off until Settings **Remote control** is on. Each session needs one Allow tap on the device being controlled. A **Being controlled — Stop** banner or notice stays up until you tap Stop.
- Control the phone from the PC: in the Phone screen window, mouse clicks, drags, and scrolls become taps, swipes, and scrolls. Typing becomes text. Back, Home, and Recents buttons are on that window. The phone needs FlipSend turned on once in Android accessibility settings.
- Control the PC from the phone: the PC sends its screen as JPEG frames (same size cap as Share this screen). The phone shows the stream and a basic keyboard. Touches become mouse and typing on Windows.
- iPhone stays view-only. Remote control is not available there.
- Phone version 1.5.18 (versionCode 29). Windows version 1.5.18. Price stays $2.9999.
- Compile fix: incoming connections no longer hide the remote-control session, so `acceptInput` resolves and the Android app builds.
- Home no longer crashes in tests (or on a first paint) just to check the remote-control banner.

## 1.5.17

- Fortress **Send to PC** now lands in FlipSend. The phone exports `org.stellartimelock.stellarsend.action.COPY_TO_PC` as a service (preferred) and a translucent activity fallback with CATEGORY_DEFAULT. Fortress sends `username`, `password`, `source=stellar-vault`, and `clearAfterSeconds`. Both username and password go to the PC clipboard. Only Fortress can call it. The secret is never logged.
- Phone version 1.5.17 (versionCode 28). Windows version 1.5.17. Price stays $2.9999.

## 1.5.16

- Home is a real main page: Send to iPhone, Apps, pairing, and a big Connected / Not connected / Disconnected line. Settings is no longer a dump on Home.
- Tap **Settings** (top-right on Home). Bot and AI connections is the first thing on that screen. Off until you turn it on (`bot_ai_connections`).
- Windows Settings puts the same switch first.
- Phone version 1.5.16 (versionCode 27). Windows version 1.5.16. Price stays $2.9999.

## 1.5.15

- Bot messages: The One (phone) and Accounts (PC) can leave a short note on the internet relay. The relay only stores ciphertext. Pickup is about 30 minutes. Incoming notes from Accounts wait for one Approve tap on the phone.
- Bot and AI connections is off until you turn it on (Settings on the phone, Settings on the PC). SharedPreferences / settings.json key `bot_ai_connections`. Missing means off. Bot messages stays hidden until then.
- PC: received notes and text-like files from the phone can be selected or copied (Copy on the received view).
- Cast to TV sits next to Share this screen. The phone lists Chromecasts / wireless displays, then uses Android's Cast sheet. The PC opens Windows Project. Drops are shown in FlipSend.
- Phone version 1.5.15 (versionCode 26). Windows version 1.5.15. Price stays $2.9999.

## 1.5.14

- The website is both the FlipSend landing page and the iPhone/browser relay. Connect with iPhone opens pairing. Scan, send, done. No lasting browser passphrase or saved pairing. Service worker cache is `flipsend-web-5`.
- Phone home is dark with large text. First screen shows Send to iPhone and Apps. Pairing and status stay on the home screen. Extra controls are in Settings.
- Silent disconnects are shown and logged. Wi-Fi is tried first, then the HTTPS/WSS relay. FlipSend restarts Wi-Fi listen if it dies.
- Older copies of the same app are deleted when a newer APK arrives. This build also sweeps leftovers already on the phone.
- Incoming apps from the PC always start a PackageInstaller session. Same-signer updates install in place. Android's own confirm still shows if Android needs it. Transfer Succeeded is not treated as Installed.
- This pass lands on top of 1.5.15. Phone version stays 1.5.15 (versionCode 26) so we do not roll versionCode backward. Price stays $2.9999.

## 1.5.13

- Received apps stay one row per app. While an app is installing, that row shows a moving bar and how far along it is.
- When the install has actually updated the app, the row says Installed. It does not stay on Installing after the new version is on the phone.
- Phone version 1.5.13 (versionCode 24). Price stays $2.9999.

## 1.5.12

- Browser sign-in is a QR and Scan. After pairing: File, then Send. No passphrase. No essay.
- Videos are not hidden. The picker asks for photos, videos, and any file. A video from a friend shows up and can be sent. Open and Share use a real video type, not generic bytes.
- Phone version 1.5.12 (versionCode 23). Price stays $2.9999.

## Browser sign-in

- The browser page is a QR and a Scan button. Either device can scan the other.
- No passphrase. No instruction block.

## 1.5.11

- 1.5.10 never compiled and never reached the phone. `ApkInstallActivity` still called `ReceivePage(this, flow)` after Receiving was reduced to one argument (`activity`).
- Same product behavior as 1.5.10: incoming updates from the paired PC replace the old copy on their own. No Clean replace tap. No FlipSend confirm. Photos still ask. Protected apps are never uninstalled.
- Phone version 1.5.11 (versionCode 22). Price stays $2.9999.

## 1.5.10

- Incoming updates from the paired PC replace the old copy and install the new one on their own. No Clean replace tap. No FlipSend "waiting for you to confirm."
- Android may still show its own confirm for a first install. Photos still ask.
- Wallet, OS, Fortress, Text Manager, and Delivery Driver are never uninstalled.
- Phone version 1.5.10 (versionCode 21). Price stays $2.9999.

## 1.5.9

- Phone screens match the clean flipsend.app look: dark cards, muted notes, pill buttons, one clear status.
- Receiving is one status and one moving bar. Apps from the paired PC install themselves. No "Needs your tap" list, no Install / Clean pile, no "Waiting for the next file" when nothing is coming.
- Photos and other non-APKs can still be opened. Android may still confirm a first install. Protected apps are never uninstalled. No Clean action that deletes apps or their data.
- Phone version 1.5.9 (versionCode 20). Windows version unchanged. App price stays $2.9999.

## 1.5.8

- Incoming APK installs show a moving progress bar (bytes into the install session, then session progress). A spinner or a still screen is not enough.
- FlipSend asks every permission it actually uses when it opens: camera, notifications, nearby Wi-Fi, older storage, then Install unknown apps if that Settings switch is still off. It does not wait until mid-install. Screen capture is still asked when Share this screen is tapped (Android requires that).
- If the phone is already paired and listening, it no longer says the relay is still waking up or to try again in a minute. Same-Wi-Fi is ready to receive.
- Share this screen on the phone shows the live phone display on the paired Windows PC. Stop sharing is the same button. Same pairing, no extra account.
- Phone app price is $2.9999, not $0.99 / $1.
- Phone version 1.5.8 (versionCode 19). Windows version 1.5.8.

## 1.5.7

- Apps sent from the paired PC now install themselves. FlipSend no longer stops and waits for a tap in FlipSend.
- If Android still needs a confirm (first install of an app that is not already on the phone), FlipSend opens Android's own sheet instead of giving up.
- An update of an app already on the phone is started as soon as the file arrives. Press-style updates no longer sit in the list doing nothing.
- Photos and other non-APK files still ask before they are kept.
- Wallet, OS, Fortress, Text Manager, and Delivery Driver are still never uninstalled.
- Phone version 1.5.7 (versionCode 18). Windows version unchanged.

## 1.5.6

- Phone and PC screens keep their contents on a normal phone width and inside the PC window. Long names, commands, paths, and button labels wrap or stack instead of running off the edge.
- Received file cards stack Open / Share / Show in folder. Apps and receiving cards stack Install / Clean replace. Home "More" no longer squeezes Send clipboard next to Send folder.
- Body type stays at least 16sp on the phone and 16px on the PC. The plans table, send-to-PC page, text viewer, and PC window were below that.
- Dark theme and `space_*` colors are unchanged. Pairing and the transfer protocol are unchanged.
- Phone version 1.5.6 (versionCode 17). Windows version 1.5.6.

## 1.5.5

- Incoming apps (APKs) from the paired computer are accepted without tapping Allow and without a FlipSend notification. Other file types still ask before saving.
- After an incoming app is accepted, FlipSend installs it in place (a normal update that keeps app data). It does not uninstall first and does not wipe data. No tap-to-install notification is left behind.
- The older APK file and received-list row for that same package are removed when the newer one arrives.
- Wallet, OS, Fortress, Text Manager, and Delivery Driver are never uninstalled. If one of those cannot be updated without a data wipe, the install is skipped and the row says so.
- FlipSend's own package still installs last.
- Phone version 1.5.5 (versionCode 16). Windows version unchanged.

## 1.5.4

- **UI overhaul (phone + PC):** Fewer screens and fewer controls; one obvious next step per screen. Same four jobs (pair, send, receive, install updates) with less clutter.
  - Android: status card, pairing card, send card, and Apps + Received buttons are always visible. All secondary controls (settings, relay, iPhone, clipboard, folder, terminal, open link, manual host, restore save, re-pair, forget) are collapsed behind a "More ▾" toggle. Terminal still opens as a full-page view.
  - PC: tab control removed; everything is a single scroll panel. Cards: status + pairing, send, transfers, settings, terminal, Stellar Vault. USB section removed from the main view.
- **Performance:** APK icons in the received list and receiving screen are now loaded off the main thread using a two-thread pool and an LruCache. The main thread no longer stalls on disk I/O or package-manager IPC for each APK row.
- **Transfer retry:** When the relay drops mid-transfer, the sender's automatic retry now waits 1.5 s instead of 0.3 s. The receiver-side relay loop sleeps 1 s before reconnecting; the longer sender delay gives the receiver time to rejoin so the single retry attempt is more likely to succeed without user intervention.
- Phone version 1.5.4 (versionCode 15). Windows version 1.5.4.

## 1.5.3

- When a newer APK for the same package arrives, older APK files that were received before 1.5.2 (and therefore lack a stored package name) are now identified by reading the file, matched to the correct package, and deleted from disk. Entries with a stored package name that somehow escaped the arrival-time prune are cleaned up the same way on startup.
- On startup the received list runs a full deduplication pass: for every package that has more than one APK entry in the log, all but the newest entry and its file are removed. This means old APK files can no longer accumulate across sessions.
- After Android confirms a successful install, the APK file is deleted from the phone's inbox automatically. The log entry stays so the row continues to show "Installed" with a "Clear installed" button; tapping that removes the entry from the list.
- Phone version 1.5.3 (versionCode 14). Windows version unchanged.

## 1.5.2

- PR #16: received list groups APKs by package, newest only, with Update/Installed/Not installed/Older tags and install-all queue. `pruneOlderApks` removes older log entries and files when a new APK arrives. `clearApkEntry` removes an installed APK entry and its file. Older APKs are hidden behind a toggle. Sort order: Update → Not installed → Installed → Older.
- Phone version 1.5.2 (versionCode 13). Windows version unchanged.

## 1.5.1

- Re-pair no longer deletes the saved device before the new pairing is confirmed. The previous peer stays in place until both sides finish the hello acknowledgement. If the attempt fails or the pairing window times out, the previous identity and peer are kept.
- `identity.bin` and `peer.bin` are replaced together: each is written to a temp file, flushed, and renamed. If the peer file cannot be replaced, the previous identity is written back.
- When a send reaches a computer that no longer accepts this phone, the phone shows **Pair again** instead of waiting until the send times out.
- A second Windows launch with `--send-file` no longer waits forever on the single-instance lock. It hands the file to the running FlipSend, writes the attempt to the log, and sends the file itself if that instance does not acknowledge within 5 seconds.
- Phone version 1.5.1 (versionCode 12). Windows version 1.5.1.

## 1.5.0

- A static web client in `web/` sends and receives files from a browser, including iPhone Safari. It uses the same pairing QR and the same end-to-end TLS 1.2 session as the apps. WebCrypto encrypts in the page. The relay does not see plaintext or keys.
- Pairing stays in IndexedDB, sealed with a passphrase. It is not bound to the device. The page is at https://flipsend.stellartimelock.com. The marketing site stays https://flipsend.app.
- Android **Send to an iPhone** and Windows **Open on web** show the pairing QR for that page. While that pairing window is open, the relay listen room is the pairing room, so a browser can pair even when another device is already saved. A confirmed device replaces the saved peer. Same-Wi-Fi listening stays pinned to the current peer.
- The relay answers browser preflight with CORS headers. Deploy the relay again before the page can read `/healthz`. The WebSocket splice is unchanged.
- Phone version 1.5.0 (versionCode 11). Windows version 1.5.0.

## 1.4.2

- Every account has a monthly relay fair-use cap, including Elite and permanent friends-and-family Elite. The default is 50 GB of relay traffic per calendar month. It resets on the 1st (UTC). Same-Wi-Fi, LAN, and USB transfers are not counted and keep working when the cap is used up.
- The relay enforces the cap. `RELAY_FAIR_USE_GB` changes the default. `POST /v1/admin/cap` with the existing `RELAY_ADMIN_TOKEN` raises, lowers, or resets one account's cap. `RELAY_ELITE_ACCOUNTS` and `RELAY_FAMILY_ELITE` name Elite and permanent friends-and-family keys. Those accounts are not exempt.
- The phone shows a usage bar and "X of 50 GB relay this month", plus "Resets on the 1st." When the cap is hit, relay transfers pause with a clear message. Local transfers stay available.
- Phone version 1.4.2 (versionCode 10). Windows version 1.4.1.

## 1.4.1

- APKs that arrive from the paired PC can install on their own. **Auto-install updates from my PC** is on by default, and it applies only to those files. The switch is a plain file on the phone, written by temp file and rename. It is not part of the Documents/Stellar save and it is not sealed with a device key.
- Where Android allows it, FlipSend commits a PackageInstaller session with `USER_ACTION_NOT_REQUIRED`. That is an update of an app FlipSend installed last, or FlipSend updating itself, with the same signature, on Android 12 or newer, and only when the APK's targetSdk meets Android's floor (29 on Android 12, 30 on 13, 31 on 14, 33 on 15, 34 on 16, 35 on 17). The phone then posts one notice, such as "Updated: Market, Profile, Keyboard".
- A first install, a different installer, a different signature, a targetSdk below that floor, or Android 11 and older is not installed by itself. Those wait together in one notice, "N apps ready to install". Tapping it runs Install all. If Android still asks after a silent attempt, that app joins the same notice instead of opening a dialog on its own.
- Nothing is uninstalled automatically. Clean replace stays a button you tap. Direct Wi-Fi is still tried first, then the relay, so a VPN can stay on.
- The main screen has an **Apps** button next to Transfer and Terminal. The number on it is how many received apps still need attention. Older copies of the same app are hidden and are not counted.
- The phone home is four cards: Send to PC, Apps, Received files, and Paired devices. The Apps list shows the app name and icon.
- Install all shows each app as Installing, Installed, Needs your tap, or Failed, with a short reason such as signature mismatch, older version than installed, or Install unknown apps is off. The attention line opens that list. Signature mismatch offers Clean replace. Install unknown apps being off offers a button that opens the system setting. A lower version is never installed over a higher one.
- Phone version 1.4.1 (versionCode 9). Windows version stays 1.4.0.

## 1.4.0

- Terminal on the phone runs a command on the paired PC. Pick a saved command or type one. Output streams back on the phone. Direct Wi-Fi is tried first, then the end-to-end encrypted relay, so a VPN can stay on.
- The PC has a Terminal section. The switch is off until you turn it on. Allowlist mode limits the exact commands. Every command is written to `terminal.log` on the PC. `del`, `rm`, `format`, and `shutdown` ask for confirmation on the phone.
- A command is signed with the pairing key. That key is the pairing identity, not a device-bound keystore key. Only a paired device can run a command. File transfers are unchanged.
- Clean replace for an APK of an app that is already installed. Matching signatures update in place and keep app data. Clean replace uses the system uninstall prompt, then installs the new APK. The save file in `Documents/Stellar` is not deleted, and opening the app again restores its data from that save. It is offered when you pick it or when an update fails. Install all does not uninstall.
- Phone version 1.4.0 (versionCode 8). Windows version 1.4.0.

## 1.3.2

- The monthly relay cap from the plan table is enforced when `PLAY_SERVICE_ACCOUNT_JSON` is set. The phone version stays 1.3.2 (versionCode 7), above 1.3.0.
- While a batch is arriving, the phone shows a receiving screen: a large status such as "Receiving 3 of 7: AppName.apk", a progress bar, the sender name, and each file already received with Install or Open. The screen stays up between files. Text follows the system font size and is at least body size.
- Each received APK shows the app name, the incoming version next to the installed version, and a status: Installed, Needs install, Older than installed, or Failed. Install all queues those installs one at a time. On Android 12 and newer, an update of an app FlipSend installed does not ask again; anything else still asks once. The screen ends with a count such as "6 of 7 installed, 1 needs attention". Nothing is uninstalled and app data is left in place.

## 1.3.1

- The app is named FlipSend. The Android package id stays `org.stellartimelock.stellarsend`. Pairing (`stellarsend://`), relay rooms, and `Documents/Stellar` saves are unchanged.
- Monthly relay usage is stored in Upstash Redis when `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN` are set, and in a file otherwise. Writes are debounced. A store outage keeps the counts in memory and retries. It does not wipe them.
- `GET /healthz` reports the active usage store. `GET /privacy` serves the privacy policy. `GET /v1/admin/usage` is the owner stats route, hidden unless `RELAY_ADMIN_TOKEN` is set.
- Release App Bundles can be signed with an upload key supplied at build time. Play Billing is in the release build. `targetSdk` is 35.
- Relay plans screen, Play listing notes, and the flipsend.app static site.
