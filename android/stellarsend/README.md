# FlipSend Android library

`org.stellartimelock.send` is the transfer implementation used by the FlipSend app and intended for Stellar OS Files. The app module `:app` only draws a demo window and calls this library. Do not copy the protocol into the host app.

The JVM module `:core` holds the protocol, pairing checks, discovery plan, filename rules, chooser registry, and memo stub. Those pieces are unit-tested without a device. This Android library stores pairing keys, discovers the peer, runs pinned TLS, and shows the send chooser.

## Call Send from Files

```kotlin
val client = StellarSendClient(context)
val request = SendRequest(
    kind = PayloadKind.FILE,
    name = "notes.txt",
    uri = contentUri,
    mimeType = "text/plain"
)

// One dialog. Methods that do not apply to this content, or that are not installed, are omitted.
SendChooser.show(activity, client, request)
```

Text uses the same entry point:

```kotlin
SendChooser.show(
    activity,
    client,
    SendRequest(kind = PayloadKind.TEXT, name = "message.txt", text = body)
)
```

A host that draws its own chooser can ask for the filtered list and dispatch each id:

```kotlin
val methods = client.methods(request)
when (method.id) {
    "wifi-pc" -> client.sendWifi(request)          // call off the UI thread
    "usb" -> client.stageUsb(request)              // returns the adb path to pull
    "stellar-memo" -> client.memo(request.text!!)  // text only; files are rejected
    "gmail", "sms", "textpipe", "share-sheet" ->
        startActivity(client.intentFor(method.id, request))
}
```

`sendWifi` blocks while it discovers the PC and streams the file. Publish progress through `SessionHub`. Incoming transfers and new pairing keys wait on `SessionHub` consent events; call `client.answerConsent(id, accepted)`. Nothing is saved until a person accepts.

## Methods

| Id | Title | Shown when |
| --- | --- | --- |
| `wifi-pc` | To my PC (Wi-Fi) | File or text |
| `usb` | USB | File or text |
| `stellar-memo` | Stellar memo (text only) | Text only |
| `gmail` | Gmail | `com.google.android.gm` is installed |
| `sms` | Text message | A default SMS app is installed |
| `textpipe` | AI Text Manager | `app.textpipe` is installed |
| `share-sheet` | Share sheet | File or text |

Gmail is `ACTION_SEND` with the package set to `com.google.android.gm`. A file is attached with `EXTRA_STREAM` and read permission. If Gmail is missing, the row is hidden; if it still fails to open, the UI says Gmail is not installed.

Text message opens the SMS composer (`smsto:` for text, `ACTION_SEND` for a file) on the default SMS package. AI Text Manager is a separate row and is never required.

The library also exposes an activity labeled **Send to PC**. It receives `ACTION_SEND` from the Android share sheet and opens the same chooser. A host that does not want that share target can remove `org.stellartimelock.send.SendToPcActivity` with a manifest merger rule.

## Pairing keys at rest

The device private key and the paired peer record are sealed with an AES-GCM key that never leaves the Android Keystore. Ordinary files in the inbox and USB outbox are not encrypted. Backup of the library host should stay off; the demo app disables backup.

The pairing QR contains the device id, the public key, its fingerprint, and a local address. It does not contain the private key.

## Permissions the host inherits

| Permission | Why | If denied |
| --- | --- | --- |
| `INTERNET` | Local TCP and UDP sockets | Transfer cannot run |
| `ACCESS_NETWORK_STATE` | Read this phone's local IP and gateway | Manual IP still works |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | mDNS multicast lock | Direct IP and UDP still run |
| `NEARBY_WIFI_DEVICES` (`neverForLocation`) | Local peer discovery on Android 13+ | Direct IP still works |
| `POST_NOTIFICATIONS` | Approval prompts while the screen is off | In-app approval still works while the app is open |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` | Keep the listener alive during a transfer | Receiving stops when the process stops |
| `REQUEST_INSTALL_PACKAGES` | Install an APK with a PackageInstaller session after you confirm | Replace app cannot install |
| `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | On Android 12+, update an app this installer last installed without another prompt | The update still asks once |
| `REQUEST_DELETE_PACKAGES` | Open the system uninstall screen (`ACTION_DELETE`) before a clean replace | The old app cannot be removed first |
| `QUERY_ALL_PACKAGES` | See whether the APK’s package is already installed | A hidden package can be mistaken for “not installed” |
| `READ_EXTERNAL_STORAGE` (API 32 and below) | Read `Documents/Stellar` for the app save before uninstall | The dialog warns that the save was not found |
| `WRITE_EXTERNAL_STORAGE` (API 28 and below) | Write this app’s own save into `Documents/Stellar` | The save stays in app storage |

Camera is not requested by the library. The demo app requests it only when someone scans a QR code.

## Password handoff and Remote Browser

Both use the paired TLS session. Call them off the UI thread.

```kotlin
client.pushPasswordToPc(password) // PC clipboard, cleared after 45 seconds
client.openBrowserOnPc("https://example.com")
client.browserStatus()
```

`pushPasswordToPc` refuses an empty or over-long password before it dials. `openBrowserOnPc` accepts only an http or https link with no user info. The returned message does not include the password or the link.

Fortress 1.3.8 **Send to PC** starts `org.stellartimelock.stellarsend.action.COPY_TO_PC` on package `org.stellartimelock.stellarsend`. It prefers `CopyToPcService` (`startService`), then `CopyToPcActivity` with `CATEGORY_DEFAULT`. Extras: `username`, `password`, `title` (label only), `clearAfterSeconds`, `source`=`stellar-vault`, `kind`=`login` or `card`. Both username and password go to the PC clipboard. FlipSend only accepts the Fortress package `com.stellartimelock.stellarvault`, or that `source` extra when a service cannot see the caller. The secret is removed from the intent and is never logged.

A separate Stellar Vault app can also fire `org.stellartimelock.send.action.PUSH_PASSWORD` with a string extra `password` at `VaultHandoffActivity`. That older activity is guarded by the signature permission `org.stellartimelock.send.permission.VAULT_HANDOFF`. Vault must be signed with the same certificate and must declare that permission. The activity removes the extra before it finishes.

Live view and typing are not available in this version. `browserStatus` reports them off, and the PC answers `browser_view_unavailable` or `browser_input_unavailable` if asked. The desktop is not controlled.

Stellar memo commands are a separate channel for a ping, an http(s) link, a short clipboard copy, or locking the PC, delivered later from the user's Stellar account. This version does not submit them, watch an account, or accept a seed. The design is `docs/MEMO_COMMANDS.md`.

## Clean APK replace

After a received APK, or from **Replace app**, call `ApkReplaceFlow` on the host activity (the demo does this). Register it before the activity is started. The flow reads the archive and its signing certificates, looks for `<package>.json` under `Documents/Stellar`, and shows the file name, US date, and item count. A missing save is a warning, not a silent continue. The save file is never deleted.

When the certificates match, or either side cannot be read, the default button is an in-place PackageInstaller update that keeps app data. **Clean replace** is the other button. If the certificates differ, Clean replace is the default, because an update would fail. Clean replace is `ACTION_DELETE` with a result, then a PackageInstaller session. A failed in-place update offers Clean replace once. Replacing the host package itself skips the separate uninstall so the session can finish. **Install all** never uninstalls. User saves go through `SaveStore`: temp file plus rename, `.bak1`–`.bak5`, and a confirm step before an empty or much smaller save replaces a fuller one.

The host's main screen should offer an **Apps** button for this list. Install all reports Installing, Installed, Needs your tap, or Failed on each row. Older APKs of the same package are hidden. **Auto-install updates from my PC** defaults on. It runs only for files that arrived from the paired computer. A silent session uses `SessionParams.setRequireUserAction(USER_ACTION_NOT_REQUIRED)` when this app is the installer of record (or is updating itself), the certificates match, and the APK targetSdk meets the platform floor. The phone posts one summary, "Updated: Market, Profile, Keyboard". A first install, another installer, or a signature mismatch is not given its own dialog. One notification, "N apps ready to install", starts Install all. That path never uninstalls. The switch is stored as plain JSON by temp file and rename. It is not written into `Documents/Stellar` and it is not sealed with the keystore.

## USB path

`stageUsb` copies the payload into the host app's external files directory `stellar-outbox` and returns an `/sdcard/Android/data/<applicationId>/files/stellar-outbox/...` path. The Windows app pulls `/sdcard/Android/data/org.stellartimelock.stellarsend/files/stellar-outbox` for the demo package. A Stellar OS host with a different application id must pull its own path. The PC must have USB debugging and `adb` installed. This library does not bundle `adb`.
