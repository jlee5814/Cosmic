# Navi service

Owner tools for Cosmic bots. Whisper any bot you own `navi email` and it whispers back your unread Gmail Primary count plus the newest three senders and subjects.

The service runs on the host, outside the game server. It holds the email credentials; the game server only holds a shared token.

## Guarantees (step 1)

- Read only: the mailbox is opened read only and headers are fetched with `BODY.PEEK`, so nothing is marked read, moved, or changed. Message bodies are never fetched.
- Allowlisted owners only: the mailbox belongs to one person, so owning a bot isn't enough. The game server sends the asking character's name and the service answers only names in `NAVI_OWNERS` (comma separated, in `~/.navi/navi.env`; restart the service after editing). With no list, nobody gets an answer.
- Owner only, whisper only: only the bot's registered owner gets an answer. `navi ...` in map or party chat gets a nudge to whisper and never reaches the service.
- Nothing from the mailbox is logged, by the service or the game server.
- The service listens on `127.0.0.1` only. The game container reaches it through `host.docker.internal`; nothing else on the network can.

## Setup

1. Turn on 2-Step Verification for the Google account, then create an app password: Google Account, Security, App passwords. Name it `navi`.
2. Store it in the macOS login keychain. Use the app password, never the account password; Gmail rejects account passwords over IMAP. The command prompts for it, so it never lands in shell history:

   ```sh
   security add-generic-password -s navi-gmail -a you@gmail.com -w
   ```

3. Check it from the terminal:

   ```sh
   python3 tools/navi/navi_service.py --check
   ```

4. Install it as a login agent (starts at login, restarts if it exits, logs to `~/.navi/navi.log`). The first run writes `~/.navi/navi.env` (mode 600) with a random `NAVI_TOKEN`; add `NAVI_OWNERS=<your character>` there:

   ```sh
   tools/navi/install_launch_agent.sh
   ```

   After editing `navi.env`, restart it with `launchctl kickstart -k gui/$(id -u)/local.navi.service`.

5. The compose files load `~/.navi/navi.env` into the game container, so recreate it once after the token exists:

   ```sh
   docker compose -f compose.lan.yaml up -d --build maplestory
   ```

## Use

| Whisper a bot you own | Effect |
|---|---|
| `navi email` / `navi check my email` / `navi inbox` / `navi any new emails` | Unread Primary count plus newest three senders and subjects |
| `navi` | Lists what Navi can do |

To revoke access, delete the app password in your Google account or run `security delete-generic-password -s navi-gmail`.

## Tests

```sh
python3 -m unittest tools/navi/test_navi_service.py
```

Java side: `BotNaviManagerTest`.
