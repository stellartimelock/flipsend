# FlipSend

FlipSend moves files and text between a Windows PC and a paired Android phone. Pairing is a one-time QR exchange. After that, the two devices find each other on Wi-Fi and talk over mutually authenticated TLS. Direct Wi-Fi is tried first. If that fails, both sides can dial an optional relay you host. There is no account, no ads, and no telemetry.

The Windows app is a small tray program. The Android transfer code lives in a library that Stellar OS Files can call. The Android app named FlipSend is a thin demo around that library. The phone app is version 1.5.1. The Windows app is version 1.5.1. The website is [flipsend.app](https://flipsend.app). An iPhone uses the web client at [flipsend.stellartimelock.com](https://flipsend.stellartimelock.com).

## Install and pair

On a Windows PC, install `FlipSend.msi` from the GitHub Actions artifact (a per-user install under `%LOCALAPPDATA%\StellarSend`) or run the portable `FlipSend.exe`. Older `StellarSend.exe` copies still run. The installer and the exe are unsigned. The app sits in the system tray and can receive while the window is closed. A file send or receive does not pull that window in front of other apps. A short tray notice names the file. Settings has **Bring window to front on transfer** if you want the window to open. It is off. Turn on **Start with Windows** if it should launch at login.

On the phone, install the FlipSend 1.4.2 APK. Open **Pair with computer**, then scan the QR code on the PC. If the camera is unavailable, type the 8 character code shown under the QR. The PC asks you to confirm before it saves the pairing. After that, the phone lists the computer under **Paired computers** and later sends do not ask you to pair again. **Unpair** on either side forgets the other device.

The phone home is clean cards: status, pairing, send, then **Apps**, **Received**, and **More**. Receiving is one status and one moving bar. Apps from the paired PC install themselves. An update of an app already on the phone replaces the old copy on its own. Wallet, OS, Fortress, Text Manager, and Delivery Driver are never uninstalled. The Apps list shows each app's name and icon. **Received** lists every file that arrived, newest first, with the name, size, and date. Tap a text file to read it in the app. **Share** and **Show in folder** stay on that screen. **Auto-install updates from my PC** is on by default. **Install unknown apps** opens the system setting for that permission.

Choose **Choose received folder** on the PC, or the same button on the phone, for where files land. Send a file, a folder, text, or the clipboard from either side. **Send APK to phone** on the PC uses the phone's install flow. When the signing certificates match, the phone updates the app in place and keeps its data. **Clean replace** uninstalls the old app after the system confirm, then installs the new APK. The save file in `Documents/Stellar` is not deleted, and opening the app again restores its data from that save. Replacing FlipSend itself is one install step. An empty or much smaller save is refused until you confirm.

There is no cloud copy of the files. Direct Wi-Fi is tried first, and a VPN-assigned address is a later candidate rather than a skipped one. If nothing on the LAN answers, each side connects out to the built-in relay at `wss://stellar-send-relay.onrender.com/v1` (clear the address and save to stay on Wi-Fi only). A sleeping relay shows **Waking relay…** and retries for about 90 seconds. The relay forwards ciphertext only. Same-Wi-Fi and USB transfers are unlimited on every plan and are not counted. Relay data follows the plan on the phone and covers the paired PC. Free relay is about 100 MB a month. Permanent Elite is 1 GB. All free accounts share a 50 GB monthly pool. It resets on the 1st. Wi-Fi transfers still work when relay data is used up. **Relay plans** on the phone compares Free, Pro, Plus, and Max. For a faster direct transfer, turn on the VPN's allow local network option. USB still works. The status line shows **Direct on Wi-Fi**, **Through relay**, or **Waking relay…**.

## Why .NET for the desktop app

The desktop app is .NET 10 and Avalonia 11. That stack has a native tray icon, drag-and-drop, DPAPI for the pairing key, and a single-file Windows executable. A Tauri app would also need Rust, Node, and a WebView. .NET is the smaller build for this product.

## Layout

| Path | What it is |
| --- | --- |
| `desktop/` | Protocol, discovery, TLS transfer, adb fallback, and the tray app |
| `android/core` | The same protocol and decisions, as a JVM library with unit tests |
| `android/stellarsend` | Android library: keystore, discovery, transfer, send chooser |
| `android/app` | Demo app that only calls the library |
| `docs/PROTOCOL.md` | Versioned wire format |
| `desktop/src/StellarSend.Relay` | Small relay process. A reverse proxy terminates HTTPS on port 443 |
| `relay/` | Dockerfile and notes for hosting that process |
| `web/` | Static browser client for iPhone and other devices without the app |
| `testdata/` | Shared frame and pairing fixtures |

## Architecture

```
Windows tray                          Android library
  Avalonia window                       Send chooser
  DPAPI identity                        Keystore identity
        \                               /
         FlipSend protocol v1
         mDNS + UDP + direct IP
         optional outbound wss relay (ciphertext only)
         TLS 1.2, pinned public keys
         framed file and text transfer
         companion calls (clipboard, browser, terminal)
```

Both sides listen. The sender dials. Incoming files and new peers wait for a person to accept them. A TCP probe that sends no bytes is closed so discovery does not block a real handshake. Companion calls use that same paired socket: the phone asks, the PC answers. They are not a second trust system.

The send chooser is a registry. Each method says which content it accepts and which package it needs. The dialog lists whatever survives those filters. Adding a method means adding a registry entry and a handler, not a new screen.

## Threat model

What this version protects:

- A device that was not paired cannot complete a transfer. The TLS certificate must match the pin taken from the QR, and the HELLO fingerprint must match that certificate.
- A paired device that later presents a different key is rejected with a re-pair error.
- The pairing QR and the logs do not carry the private key. Log lines that contain a pairing URL, a `"password"` field, or a private-key header are replaced with `[redacted]`.
- A clipboard, browser, or terminal call is accepted only after the same paired handshake. The PC does not open a separate port for them.
- A terminal command also has to be signed by the pairing key. That key is the pairing identity, not a device-bound keystore key. The PC switch starts off. Allowlist mode can limit the exact command text. `del`, `rm`, `format`, and `shutdown` ask for confirmation on the phone. The PC appends each command to `terminal.log`.
- Filenames are stripped of path separators and reserved Windows names, and files are written only inside the inbox directory.
- Network addresses used for discovery and pairing must be local. Public addresses are not dialed.
- USB commands run only after a person confirms, and only against an `adb` binary and a remote path under `/sdcard/` or `/storage/`.

What this version does not protect:

- Someone on the same local network can see that two devices are talking. TLS hides the contents. This is not anonymity.
- A relay you configure learns that two connections share a room token and sees TLS ciphertext. It does not learn the device ids from that token and it does not see the files.
- Files stored in the inbox or sent over USB are ordinary files. Only pairing keys are encrypted at rest (DPAPI on Windows, Android Keystore AES-GCM on the phone).
- The two-minute pairing window trusts the person looking at the screen. A confirmation dialog is required before the new key is saved.
- The hotspot fallback probes at most 16 addresses on a recognized phone-hotspot gateway. It is not a general network scan, and it can miss a phone that is outside that sample.
- The Windows executable is unsigned. SmartScreen may warn.
- `adb` is as powerful as the debugging session. FlipSend does not bundle it and will not run it without confirmation.
- A password handed to the PC sits on the clipboard until the 30 second clear, or until you replace it. Anyone who can read that clipboard in that window can read it.

## Non-goals

- No account, analytics, ads, or crash reporting. The relay forwards ciphertext only. It cannot read the files. It counts relay egress against the free fallback cap in `relay/tiers.json` (about 100 MB a month, 1 GB for permanent Elite, 50 GB shared by all free accounts). Local and USB transfers are not part of that count.
- No encryption of ordinary files at rest.
- The send-chooser memo entry is still a stub. It returns not implemented and refuses files. It does not submit a transaction or implement a time lock.
- No bundled `adb`, no code signing, and no multi-peer mesh. There is no App Store iOS app. An iPhone uses the web client at https://flipsend.stellartimelock.com.
- Remote desktop shows the paired Windows screen on the phone. Tap, drag, long-press, and two-finger scroll control the mouse. The phone does not ask for an extra Android permission for that direction. Terminal still runs one command at a time in `cmd` on the paired PC when that PC's Terminal switch is on. Remote Browser opens one link on the local network and, in this version, does not view or type into the page. Memo commands, when connected, are limited to ping, open a link, copy text, and lock the PC. They still do not open a shell.

## Desktop setup

Install the .NET 10 SDK. From the repository root:

```bash
dotnet test desktop/StellarSend.slnx -c Release
dotnet run --project desktop/src/StellarSend.App/StellarSend.App.csproj
```

The tray app is a Windows program. The core tests run on Linux and Windows. Pairing storage uses DPAPI, so creating an identity in the GUI requires Windows. The tests use an in-memory key protector.

Package a portable executable on Windows:

```powershell
./desktop/package.ps1
./scripts/install.ps1
./scripts/install.ps1 -Startup
./scripts/uninstall.ps1
```

`install.ps1` copies `dist\StellarSend.exe` to `%LOCALAPPDATA%\StellarSend` and registers the Explorer command. `-Startup` adds a per-user Run entry. `uninstall.ps1` removes the command, the startup entry, and the copied exe. The same switches exist on the executable: `--register-shell`, `--unregister-shell`, `--enable-startup`, `--disable-startup`, and `--send-file <path>`.

The Explorer entry is `HKCU\Software\Classes\*\shell\StellarSend`. Its command is `"<exe>" --send-file "%1"`. It applies to the current user and does not need an administrator. A second launch with `--send-file` hands the path to the running instance, waits until that instance accepts it, and exits. If the running instance does not answer within 5 seconds, this launch sends the file itself. Both outcomes are written to the log.

Publish the same portable exe from any machine that has the .NET 10 SDK:

```bash
dotnet publish desktop/src/StellarSend.App/StellarSend.App.csproj \
  -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true \
  -p:IncludeNativeLibrariesForSelfExtract=true \
  -p:EnableCompressionInSingleFile=true \
  -o dist
```

Do not commit `dist/` or the exe. CI uploads `StellarSend.exe` as a build artifact. It is not a GitHub Release and it is not signed.

## Pairing

1. Open FlipSend on both devices.
2. On one device, choose **Show pairing code**. That opens a two-minute window and shows a QR.
3. On the other, scan the code or paste it and choose **Pair from code**.
4. The device that showed the code asks for confirmation before it saves the peer.
5. Status changes to the paired device name.

**Unpair** deletes the peer and keeps this device's key. **Re-pair** deletes the key and the peer and creates a new key. Both sides must pair again after a re-pair. A changed key on an existing peer is refused until you do that.

The QR holds the device id, the public key, the public-key fingerprint, the best local IP, and every other candidate address. Paste works when the camera is unavailable. The phone tries those addresses together and uses the first one that completes the handshake.

## Finding the other device

Search order is mDNS, UDP broadcast, the manual IP, the last-known IP, the other addresses from the pairing code, and, only on a known phone-hotspot gateway, that gateway plus a capped sample of its subnet. Virtual adapters such as vEthernet, Hyper-V, and WSL are left out. Link-local `169.254` addresses are left out. A VPN address stays in the list after Wi-Fi and Ethernet. The search gives up after 4 seconds. Both devices then dial the relay address (the built-in one, unless it was cleared). The message, when nothing works, suggests the VPN's allow local network option.

The phone can listen for the PC in the background. That notification is on its own channel, **Background listening**, so it can be hidden without hiding file-received notices. **Listen for PC in background** is on by default. Turn it off to listen only while the app is open. **Hide listening notification** opens that channel in system settings. Those two settings, the relay address, and **Auto-install updates from my PC** are not part of the Documents/Stellar save. The auto-install switch is a small JSON file in the app's private storage, replaced by rename, and it is not encrypted with a device-bound key.

Phone hotspots often drop multicast. Put the PC on the phone's hotspot, or enter the PC's local IP in the phone and the phone's local IP on the PC. Addresses such as `192.168.43.1` are treated as hotspot gateways. A normal home gateway such as `192.168.1.1` is not range-scanned.

## Transfers

Drag a file onto the Windows window, choose a file, or type text. The phone's **Send** button opens the chooser. Both directions show bytes, a rate and remaining estimate when enough time has passed, and success, failure, cancel, and retry. One retry runs after a broken connection. A decline or a bad hash does not retry.

The receiver writes `name.partial` and renames it only after the SHA-256 matches. Cancel or failure deletes the partial file.

## App updates

An APK received on the phone, or an APK you pick and mark **Replace app**, can update an app that is already installed. FlipSend reads the package name and the signing certificates, and looks in `Documents/Stellar` for `<package>.json` (and `Documents/Stellar/saves/<package>.json`). The dialog shows the file name, the date as MM/DD/YYYY with a 12-hour clock, and the item count. If that file is missing, the dialog says so. The save file is never deleted. Opening the app again restores its data from that save.

When the new APK is signed with the same certificates, or the certificates cannot be read, the default is a normal update. PackageInstaller installs it in place and Android keeps the app's private data. **Clean replace** is on that dialog if you want it, and it is offered again if that update fails.

Clean replace asks Android to uninstall the old app with `ACTION_DELETE`, which always shows the system confirm screen, then installs the new APK. FlipSend does not delete the save file. Cancelling uninstall leaves the old app in place and does not install the new APK. If the certificates differ, Clean replace is the action on the dialog, because an in-place update would fail. You still have to tap it. Nothing is uninstalled on its own.

The receiving screen is one status and one moving bar. Apps from the paired PC install themselves. There is no Install all, no "Needs your tap" list, and no Clean that deletes apps. Photos and other non-APKs can still ask. Android may still confirm a first install. Protected apps are never uninstalled. FlipSend itself still installs last.

When **Auto-install updates from my PC** is on, a batch that just arrived from the paired computer starts those installs as soon as the files are saved. Each update that Android can apply silently is committed with `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`. The permission `UPDATE_PACKAGES_WITHOUT_USER_ACTION` is declared. An older APK is left as it is. Clean replace is never started by this path. The file is already on the phone before any install starts, so the transfer can be direct Wi-Fi or the relay with a VPN left on.

The Windows app does not install APKs. Sending or receiving one there only says that the phone can update the app in place or offer Clean replace.

## Save files

A user save is written to a temporary file and renamed into place. The previous copy rotates through `.bak1` to `.bak5`. An empty save, a save with fewer than half as many items, or a larger file that is not a save is refused until you confirm. The prompt shows both item counts and can restore a backup. A JSON list, or a JSON object without a version, still opens. Those files are not deleted to migrate them.

Dates shown in the app use MM/DD/YYYY and 12-hour time. The phone screen resizes for the keyboard and scrolls the focused field into view.

The phone app is free and open source. It is not on Google Play; it is meant for F-Droid (draft metadata in [fdroid/](fdroid/)). Same-Wi-Fi and USB transfers are unlimited. If those cannot reach, FlipSend uses the hosted relay. That relay is a free fallback. It is not a separate purchase. There is nothing to buy on Windows. If a delivery fee is calculated, the asset is XLM, 10% goes to the Project Development Fund, and the payout rail is Chime. Passwords are not written into save files. The privacy policy is [docs/privacy.html](docs/privacy.html).

## Stellar Vault

The paired phone can place one password on the PC clipboard. The PC copies it, shows “Copied to the PC clipboard. It will clear in 45 seconds.”, and clears the clipboard after 45 seconds only when it still holds that same value. A newer copy replaces the timer. If you paste something else first, that text stays. The password is not written to the log.

From an app that embeds the library, off the UI thread:

```kotlin
val result = client.pushPasswordToPc(password)
```

Another app signed with the same certificate can send an intent instead. Declare `org.stellartimelock.send.permission.VAULT_HANDOFF` and start:

- action `org.stellartimelock.send.action.PUSH_PASSWORD`
- extra `password`
- the activity `org.stellartimelock.send.VaultHandoffActivity`, which is exported only to holders of that signature permission

The activity reads the extra, removes it, and finishes after a toast. A differently signed app cannot call it.

Fortress 1.3.8 **Send to PC** (`StellarSend.kt`) prefers an exported service, then a fire-and-forget activity:

- action `org.stellartimelock.stellarsend.action.COPY_TO_PC` on package `org.stellartimelock.stellarsend`
- service first (`startService`); activity fallback with `CATEGORY_DEFAULT` (`startActivity`, no result)
- extras `username`, `password`, `title` (label only), `clearAfterSeconds`, `source`=`stellar-vault`, `kind`=`login` or `card`
- clipboard text is username, then a newline, then password
- FlipSend allows only Fortress (`com.stellartimelock.stellarvault`), or `source`=`stellar-vault` when the service cannot see the caller
- `clearAfterSeconds` is honored (5–300, default 45). The secret is never logged.

Fortress password-bot file handoff (1.5.54) moves an encrypted file only. FlipSend never decrypts, logs, or keeps the bytes after the transfer.

Phone receive, from the paired PC only:

- incoming names `fortress-bot-request.json` and `fortress-bot-failed.json` (any `fortress-bot-*.json` except `fortress-bot-response.json`)
- FlipSend starts Fortress (`com.stellartimelock.stellarvault`) with action `com.stellartimelock.stellarvault.action.BOT_HANDOFF`
- extras: `name` (file name), `content` (UTF-8 file text), data + type `application/json`, `android.intent.extra.STREAM` (the FileProvider URI), `filename`, `FLAG_GRANT_READ_URI_PERMISSION`
- it is not a received-list row and does not need a tap
- `fortress-bot-response.json` is never handed back to Fortress as a request

Phone send, Fortress only:

- Fortress tries `SEND_FILE_TO_PC` first, then `COPY_TO_PC` with `kind`=`file`
- extras `kind`=`file`, `name`=`fortress-bot-response.json`, `content` (ciphertext JSON string), `source`=`stellar-vault`
- FlipSend writes `content` to a temp file, sends it under `name`, and deletes the temp copy. `android.intent.extra.STREAM` / `uri` is a fallback
- FlipSend checks the calling package `com.stellartimelock.stellarvault` and its signing cert (release prefix `fac61745` or the sideload/debug prefix `7994727d`)
- the file is never copied to the clipboard
- unpaired peers are rejected

Windows writes that exact name into `%LOCALAPPDATA%\StellarSend\inbox` and overwrites. It does not prompt or open the file.

## Stellar memo commands

The Remote tab describes a channel that can deliver a small command from the user's Stellar account when the phone and PC are not on the same network. The allowed commands are ping, open an http or https link, copy a short text to the clipboard, and lock the PC. The sender must be the configured account and the command must verify under a seed-derived key. A replay or an unknown command does nothing. The desktop is not controlled.

This version does not watch an account, store a seed, or contact the network. The design is in `docs/MEMO_COMMANDS.md`. Remote Browser remains the live local plugin.

## Terminal

The phone's **Terminal** tab sends a command to the paired PC. You can type one or pick a saved command. The PC runs it and streams the output back. Direct Wi-Fi is tried first. If that fails, both sides use the same outbound encrypted relay as a file transfer, so a VPN can stay on.

On the PC, **Terminal** is in the Send tab. **Let the paired phone run commands** is off until you turn it on. **Allowlist only** starts on, so nothing runs until you add the exact command. Every command is appended to `terminal.log` in the FlipSend data folder. `del`, `rm`, `format`, and `shutdown` ask for confirmation on the phone before they are sent. The command is signed with the pairing key. Unpair, or turn the switch off, and the phone cannot run anything.

## Remote Browser

Remote Browser is the first plugin on the PC tray’s Remote tab. The phone sends an http or https link. The PC opens it in the system browser. `file:`, `javascript:`, and links with a username or password in them are refused. The result text does not repeat the link.

Live view and typing are defined operations (`frame` and `input`) and answer `browser_view_unavailable` and `browser_input_unavailable` until a browser surface is attached. This version does not capture the screen and does not control the desktop. The Remote tab lists that state. The phone’s Remote card sends “Open on PC” and says the same.

## USB

When Wi-Fi cannot connect and `adb` is on the PC:

1. Enable Android USB debugging and accept the PC's debugging prompt.
2. In the Windows app, refresh devices and confirm the serial and state.
3. Confirm before push or pull. Push writes to `/sdcard/Download/StellarSend/`. Pull reads `/sdcard/Android/data/org.stellartimelock.stellarsend/files/stellar-outbox`.
4. On the phone, the USB chooser entry copies the selection into that outbox and shows the path.

`adb` is located on `PATH` or under `ANDROID_HOME` / `ANDROID_SDK_ROOT`. The file name must be `adb` or `adb.exe`. FlipSend does not ship a copy. Unauthorized, offline, and empty device lists are shown as status, not as a silent success.

## Android demo

Install JDK 21 and Android SDK 35. From `android/`:

```bash
./gradlew clean :core:test :stellarsend:assemble :app:assembleDebug
```

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. The integration example for Stellar OS Files is in [android/stellarsend/README.md](android/stellarsend/README.md).

Gmail uses `ACTION_SEND` targeted at `com.google.android.gm` and attaches the file. If Gmail is not installed, that row is hidden, and a failed launch says Gmail is not installed. Text message opens the SMS or MMS composer. If the package `app.textpipe` is installed, the chooser also offers AI Text Manager. The app does not require that package. **Share sheet** opens the system share UI. Other apps can share into FlipSend through the target labeled **Send to PC**.

## Permissions

The Android library requests network and notification permissions so it can listen and ask before receiving. Each one is explained in the demo screen and in the library README. Camera is requested only for a QR scan. Denying nearby-Wi-Fi or notifications leaves manual IP, paste-to-pair, and in-app approval working.

## Dependencies that are not obvious

| Dependency | Why it is here |
| --- | --- |
| Avalonia 11.3 | Tray, window, drag-and-drop |
| QRCoder | Windows QR drawing |
| DPAPI (`ProtectedData`) | Encrypts the Windows pairing key for the current user |
| Bouncy Castle `bcpkix` | Android and Java have no public API to mint a self-signed certificate. TLS itself is the platform `SSLSocket` / `SslStream` |
| ZXing core | QR bitmap in the Android library |
| ZXing Android Embedded | Camera scan in the demo app only |
| `org.json` | Protocol JSON on the JVM tests. The Android app uses the platform `org.json` and does not package the Maven jar |

mDNS is implemented in-tree so the phone and the PC share one packet format. There is no extra mDNS library.

## Checks

```bash
dotnet test desktop/StellarSend.slnx -c Release
cd android && ./gradlew clean :core:test :stellarsend:assemble :app:assembleDebug
```

GitHub Actions, in `.github/workflows/build.yml`, runs the desktop tests, publishes the Windows exe artifact, and runs the Android tests plus debug assembly. Action versions are pinned to major releases. The workflow has no secrets.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| Could not find the paired device | Wake both apps, join the same Wi-Fi or hotspot, or enter the other device's local IP. The error lists the checks that ran. |
| The paired device key changed | Unpair on both sides, then pair again. Re-pair rotates this device's key. |
| Pairing code rejected | Use a code from this app. Public IPs, private keys, and extra secret fields are refused. |
| USB device unauthorized | Unlock the phone and accept the USB debugging prompt, then refresh. |
| adb was not found | Install platform-tools. Do not copy `adb.exe` into this repo. |
| Gmail is not installed | The Gmail row is only for `com.google.android.gm`. Use the share sheet for another mail app. |
| SmartScreen warns | The CI exe is unsigned. |
| Port 53321 is in use | Quit the other FlipSend instance. |

## Limitations

- One paired peer.
- The phone receives while its listener service is running. Force-stopping the app stops receiving until it is opened again.
- Hotspot search is a bounded sample, not a full /24 scan.
- TLS is 1.2 only, by choice, so both platforms present certificates the same way.
- The Windows USB pull path is the demo app's package path. A different Stellar OS application id has a different outbox path; `stageUsb` returns that path.
- Remote Browser opens a link. It does not show the page or take taps and typing, and it does not control the desktop.
- Stellar memo commands are specified and stubbed. The PC does not watch an account, read a seed, or contact the network.
- A Vault handoff needs the existing pair. The signature permission blocks a differently signed app. The 30 second clear runs on the PC.
- Manual hardware checks still matter: a real QR scan, a phone hotspot, an `adb` push or pull, a Vault copy and 30 second clear, and an “Open on PC” link. The automated tests cover the protocol, pins, discovery decisions, chooser filtering, clipboard policy, URL checks, and a loopback TLS transfer. They do not attach a handset or a Windows desktop session.

## License

FlipSend is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE). SPDX-License-Identifier: GPL-3.0-or-later.

Bundled third-party code keeps its own license: `web/js/jsQR.js` (Apache-2.0, see `web/js/jsQR.LICENSE`) and `web/js/qrcode.js` (MIT, see `web/js/qrcode.LICENSE`).
