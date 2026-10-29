# Stellar memo commands

Memo commands are a second way to ask the paired PC to do a small thing. They ride the user's Stellar account, so they do not need the phone and the PC to be on the same network at the same time. Remote Browser is unchanged: it stays a live, local, paired-TLS plugin for opening a link and, later, viewing that browser. Memo commands do not control the desktop.

This version defines the channel and refuses to act. The tray app does not watch an account, does not read a seed, and does not contact the Stellar network.

## What a command may do

| Op | Effect | Limit |
| --- | --- | --- |
| `ping` | A short notice on the PC | none |
| `open_url` | Opens one http or https link in the PC browser | 256 characters, no user info |
| `copy_text` | Copies text to the clipboard and clears it after 30 seconds if it is still that text | 256 characters |
| `lock_pc` | Locks the Windows session | none |

Any other op, including a shell, a mouse move, or a full desktop view, is rejected. The public result never includes the URL, the copied text, or a seed.

`Stellar memo (text only)` in the send chooser is a different stub. It still returns not implemented and still refuses files. It does not submit a transaction.

## How a future watcher recognizes a command

A classic Stellar memo is at most 28 bytes, which is too small for an encrypted URL. The memo is only the channel tag. The ciphertext travels beside it.

- Memo text must be exactly `sscmd.1`.
- The transaction source account must be the account the person configured on the PC.
- The ciphertext is at most 512 bytes. A later watcher reads it from that transaction. This version does not fetch it.
- The plaintext, after it is opened, is at most 512 bytes of UTF-8 JSON:

```json
{"v":1,"id":"<uuid>","exp":1710000000,"op":"ping"}
```

`open_url` adds `url`. `copy_text` adds `text`. `exp` is a Unix expiry. The PC allows 120 seconds of clock skew and rejects a command whose expiry is more than 24 hours ahead. A command id is remembered after it runs, so a replay does nothing.

## Verifying the sender

The PC runs a command only when both checks pass:

1. The transaction source is the configured Stellar account, checksum included.
2. The ciphertext opens and the signature verifies under a key derived from the user's seed.

The seed is not the value stored for local pairing. Derivation, when it exists, uses a domain-separated label `stellarsend memo-command v1` and keeps the seed and the private key inside a vault. The vault has no method that returns them. Logs redact a `"seed"` or `"mnemonic"` field. This version's crypto object is `UnconfiguredMemoCrypto`: it reports that no seed was read, and it never returns a verified command.

`DisconnectedMemoLedger` does not contact the network. `PollOnce` returns immediately unless a future build supplies both a configured vault and a ledger that actually talks to the network.

## What is stubbed

- No account id is stored.
- No seed is requested or stored.
- No Horizon, RPC, or Soroban call is made.
- `lock_pc` has a Windows implementation, and it is not called while the watcher is disconnected.
- Live view of a browser remains the Remote Browser plugin on the local link, and it is still off in this version.
