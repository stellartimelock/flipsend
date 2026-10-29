# Direct link across two Wi-Fi networks

1.5.33 builds this path for Android and Windows. Same Wi-Fi still uses the LAN socket on TCP 53321. iPhone and the browser stay on the relay.

No Tailscale, no extra app, no account. No new signing key. Price stays $2.9999.

## What "direct" means

When the two devices are on different networks, FlipSend tries a direct encrypted socket. The relay is only the meeting place. File bytes use the relay only if the direct try fails. The home pill says **Direct** while that socket is the one carrying the transfer.

## How the two sides meet

1. Both sides open the usual relay room and finish the paired TLS session. The relay still sees ciphertext only.
2. The relay's ready note includes `seen`, the public address it observed for that socket. Each side also knows its own LAN addresses and the UDP and TCP ports it bound for the punch.
3. Those candidates ride inside the hello on the paired TLS session. The relay cannot read them.
4. For a few seconds they send a small UDP punch packet, then open TCP to the peer's public address and LAN addresses. The socket that connects is a new TLS session with the existing device certificates. The lower device id is the TLS client.
5. If that socket does not open, the file continues on the relay. The transfer id and resume offset stay the same.

Wi-Fi Direct is not started. A miss there is quiet, and the punch or the relay takes over.

## When a network changes

The phone and the PC both drop the old sockets and listen again. A send that was moving keeps its transfer id and resumes from the received offset. The next file try is Wi-Fi, then Bluetooth, then the punched link, then the relay.

## Security

- Same pairing certificates. A punched socket is trusted only if the peer certificate still matches the stored pin.
- Endpoint lists travel inside the TLS session. They are not a new account and they are not logged.
- Bytes that never touch the relay are not counted against the monthly cap, same as LAN, Bluetooth, and USB.
