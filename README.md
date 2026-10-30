# Donut Streamer

A client-side Fabric mod for **Minecraft 1.21.11** that gives DonutSMP streamers three tools in one jar:

1. **Session tracker**: reads payment messages from chat and keeps your profit, record, streaks and charts
   up to date automatically, with a fully draggable stream HUD (in the style of Gambler Plus).
2. **Auction tools**: a live auction panel (countdown, top bidder, top bid) and a searchable item browser,
   fed by the same chat messages.
3. **Now playing**: a Spotify-style HUD panel for whatever is playing on your PC (Spotify Free, YouTube Music, Apple Music, browsers...), with play/pause/skip keys. No account or Premium needed.

No hotkeys to press for the tracker, nothing to click. The mod only reads chat you can already see.

Plus stream tools: a **stream mode** that hides your coordinates, your own **nametag in F5**, optional **Twitch / YouTube chat** on screen, and a small blue **"Sirens Streamer Mod"** tag you can turn off.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for 1.21.11 (0.18.1 or newer) and Java 21.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and `donutstreamer-<version>.jar` in your `mods` folder.

## Tracker

| Chat message | Meaning |
|---|---|
| `name paid you $ amount` | money received (counts as a win) |
| `You paid name $ amount` | money sent (counts as a loss) |
| `You bought N item for $ amount` | subtracts from live profit, not a gamble |
| `$ amount` on its own (selling) | sale income, tracked separately |

Payments under **10M** are ignored by the tracker. The space after `$` is optional.
Net profit = received - sent - bought. Sales are shown separately; `/ds sells` adds them to net.
If your server shows sales in the action bar instead of chat, run `/ds sellsource`.
Lines must *start* with the pattern, so a player typing a fake line in public chat is ignored.

**Doubled payments.** If a player pays you and then pays you exactly double without you paying them in
between, the chat line gets a gold tag such as `[DOUBLED: 10K -> 20K]` (or `x2 in a row` for a chain).
Paying that player resets it. Toggle with `/ds doubles`.

**Tracker window.** `/ds` opens net profit, record, timer, charts and a per-player table
(what each player sent you, what you sent them, net, and rakeback owed if `/ds rakeback 5` is set).

**HUD.** Separate elements, each on/off and draggable: net profit, W-L record, streak, last payment,
timer, profit graph, result bars, win-rate bar, last-10 form, totals, averages, biggest in/out, peak/low,
best/worst streak, profit per gamble/hour, top payer, balance, goal and loss-limit bars.
Open chat (**T**), drag elements with the left mouse button, and use the **HUD editor** button (top right)
to toggle elements and settings. Elements that would end up off-screen after a resize are pulled back in.

**Alerts.** `/ds tilt 5` (warning after 5 payments sent in a row with nothing back), `/ds limit 500k`
(loss limit), `/ds goal 2m` (take-profit).

## Auctions (DonutSMP only)

Auction tools only run while connected to `donutsmp.net`.

- Press **B** to open the item browser: search, scroll, click an item, set the minimum bid and time, press
  **Start Auction**. Or use a command: `/ds auction start "Dragon Egg" 5m 10m` (item, minimum bid, time).
- The **auction panel** appears on screen while an auction runs, with a circular countdown ring that turns amber at 30s and flashes red in the last 10s. It is a normal HUD element, so you can drag it
  in the HUD editor, scale it, or hide it with **H**.
- While an auction runs, every `name paid you $ amount` at or above the minimum bid counts as a bid; the highest
  one is shown as top bidder. Bids can be smaller than the tracker's 10M minimum. When the timer ends, the
  winner is announced in your chat. The timer never extends, whatever happens.
- `/ds auction track` decides whether payments that count as bids are also added to the gambling tracker
  (default: yes).

Times accept `90s`, `5m`, `1m30s`, `2h15m` (up to 24h). A plain number means minutes.
`/auctionstart` and `/auctionstop` still work as short aliases (a plain number in `/auctionstart` means seconds).

## Music (no Premium, no login)

The music panel reads what your computer's **media controls** say is playing, so it works with **Spotify Free**, YouTube Music,
Apple Music, Windows Media Player, browser players and most other apps. Nothing to sign into, nothing sent over the internet.

- **Windows**: a small hidden PowerShell process reads the system media session once a second (only while the music panel is on).
- **Linux**: install `playerctl`. **macOS**: reads Spotify and Music (best effort, untested).
- `/ds hud music` turns the panel on. `/ds music` shows what it sees.
- Playing several things at once? `/ds music app spotify` (or `chrome`, `music`...) makes it follow only that app; `/ds music app any` goes back to whatever Windows says is current.
- Play/pause/next/previous use the standard media keys, so they work with any player. Default keys: `\` toggle, `]` next, `[` previous (change them in Minecraft's controls).
- The panel shows title, artist, a cover coloured from the track, an equalizer, and a progress bar with times. Apps that don't report a length (some browser players) just show no bar.
- Free-plan Spotify ads show up as "Advertisement" for as long as the ad plays.

### Spotify Web API (optional, needs Premium)

Prefer the old way? `/ds music source spotify` switches to Spotify's official Web API (`/ds music source system` switches back).

Minecraft can't stream Spotify audio itself (that's Spotify's DRM, not a mod limit). Instead the mod controls the
**Spotify app already running on your PC** through Spotify's official Web API, and shows what's playing in a HUD
panel (generated cover art, scrolling title, animated equalizer, progress bar with knob; draggable like every other element, default bottom-right).

**One-time setup** (Spotify requires a **Premium** account for this, and each person uses their own app):

1. Go to the [Spotify developer dashboard](https://developer.spotify.com/dashboard) and create an app. Tick **Web API**.
2. Add the Redirect URI **exactly**: `http://127.0.0.1:8888/callback` (it must be `127.0.0.1`, not `localhost`).
3. Copy the app's **Client ID** and run `/ds music login <clientId>` in game. Click the link it prints, approve, done.
   Next time, plain `/ds music login` reuses the saved Client ID.

Then open Spotify on your PC and play something. Controls: `/ds music play|pause|toggle|next|prev|volume <0-100>|status|logout`,
or the keybinds **\\** (play/pause), **]** (next), **[** (previous), all changeable in Controls.

Notes:
- Reading and controlling playback both need Premium on Spotify's side. Spotify limits development-mode apps to a few
  authorised users, which is fine for personal use.
- Sign-in uses PKCE, so there is no client secret. Only a refresh token is stored, in `config/donutstreamer-spotify.json`:
  **don't share or commit that file**.
- The mod only contacts `accounts.spotify.com` and `api.spotify.com`, only after you log in, and only polls while the
  panel is on and you are in a world. All of it runs off the game thread.
- If you stream, remember Spotify music on stream can get VODs muted or claimed. Pause it or use stream-safe music.

## Stream tools

**Stream mode** (`/ds stream`) is a master switch. While it is on:

- **F3 coordinates are hidden**: the position, chunk/section and "targeted block / fluid" lines disappear from the F3 screen (`/ds stream coords` turns just this part on or off).
- **Coordinates in chat are blanked** (`/ds stream chatcoords`, off by default): lines like `x: 120 y: 64 z: -300` or `120, 64, -300` show `[coords hidden]`. It rebuilds the line as plain text, so colours inside that one line are simplified. Money like `$1,234,567` is left alone.
- **Player names are hidden on the HUD** (`/ds stream names`).

`/ds stream status` lists the current state. Everything is display-only on your own screen.

**Own nametag in F5** (`/ds nametag`, on by default): in third person your own nametag is drawn through the same path the game uses for other players, so team prefixes and colours (ranks, Media, etc.) look the way they do to everyone else. First person is unchanged.

**Watermark** (`/ds watermark`): a small blue "Sirens Streamer Mod" in the bottom-left corner. Also in the HUD editor.

### Stream chat (Twitch / YouTube)

An optional on-screen chat panel (a normal HUD element: `/ds hud chat`, draggable and scalable). It is read-only; the mod cannot post.

- **Twitch**: `/ds chat twitch <channel>` (a twitch.tv link works too). Connects anonymously over Twitch's public chat server, so no account or token is needed. `/ds chat twitch off` disconnects.
- **YouTube**: needs your own free API key (YouTube Data API v3) and the live stream. Copy the key, run `/ds chat youtubekey paste` (pasting keeps the key off your screen), then `/ds chat youtube <link or video id>`. Each poll uses a few quota units of the 10,000 free per day, which is several hours of chat. The key is stored in `config/donutstreamer-chat.json`: don't share or commit it.
- **Full messages**: long messages are word-wrapped onto more lines instead of being cut off (`/ds chat set wrap off` cuts them with "...").
- **Look and filters** with `/ds chat set <option> <value>` (`/ds chat settings` lists them with their current values): `rows`, `width`, `spacing`, `wrap`, `bubbles`, `header`, `marker`, `badges` (owner/mod/VIP/sub squares), `timestamps`, `shadow`, `namecolors`, `top` (newest at the top), `textcolor`, `fade`, `expire`, `commands` (hide `!commands`), `bots` (hide Nightbot, StreamElements...), `hidelinks`.
- `/ds chat ignore add|remove|list|clear <name>` hides a chatter. `/ds chat highlight add|remove|list|clear <word>` gives messages containing that word a gold highlight.
- Size and background of the whole panel: `/ds hud chat scale <n>` / `bg <10-100>`, or drag its corner in the HUD editor.
- `/ds chat` shows status, `/ds chat clear` empties the panel, `/ds chat off` disconnects both, and `/ds chat commands` toggles hiding of `!commands`.

Nothing connects until you run one of these commands. Your chat setup is remembered and reconnects at launch.

## Customizing everything

- **HUD editor** (open chat, click "HUD editor"): click an element to select it, drag to move it, **drag the square on its bottom-right corner to resize just that element**. A panel at the top shows its size, background opacity, colour (swatches), on/off and reset. The "HUD editor" menu holds the global switches (animations, glow, shadows, full amounts, stream mode, ...).
- **Per element by command**: `/ds hud <element> scale <0.4-4>`, `bg <10-100>`, `color <name|#hex|default>`, `on`, `off`, `reset`. `/ds hud scale <n>` still resizes everything at once; each element's own size multiplies with it.
- **Themes**: `/ds theme` lists them (default, midnight, glass, ember, forest, royal, mono, black); `/ds theme <name>` applies one.
- **Card style**: `/ds style glow|shadow|animations` toggles glowing edges, shadows, and all pulses/equalizer movement (off = still and slightly cheaper).
- **Number format**: `/ds format short|full` (45M or 45,000,000) and `/ds format decimals <0-3>`.
- **Chat**: `/ds chat settings` lists every option; see Stream chat below.
- `/ds customize` prints this list in game.

## Commands

| Command | |
|---|---|
| `/ds`, `/ds help` | tracker window, command list |
| `/ds in\|out <amt> [name]`, `/ds buy <amt> [item]`, `/ds sell <amt>` | add something the reader missed |
| `/ds undo`, `/ds reset` (twice), `/ds summary`, `/ds players` | session tools (`summary` copies to clipboard) |
| `/ds limit\|goal <amt\|off>`, `/ds tilt <2-20\|off>`, `/ds rakeback <0.5-50\|off>`, `/ds balance <amt\|off>` | alerts and numbers |
| `/ds percent`, `/ds sells`, `/ds sellsource`, `/ds doubles`, `/ds sound`, `/ds names` | toggles (`names` hides player names for stream) |
| `/ds hud toggle\|reset\|all on\|all off\|scale <0.5-3>\|<element>` | HUD layout |
| `/ds auction start "<item>" <min bid> [time]`, `stop`, `status`, `browser` | auctions |
| `/ds auction track\|anywhere` | count bids in tracker / allow auctions on any server (testing) |
| `/ds stream [coords\|chatcoords\|names\|status]`, `/ds nametag`, `/ds watermark` | stream mode, own F5 nametag, watermark |
| `/ds chat twitch <channel>\|off`, `/ds chat youtube <link>\|off`, `/ds chat youtubekey paste\|<key>\|clear`, `/ds chat status\|clear\|off\|commands` | Twitch / YouTube chat |
| `/ds music [status]`, `source system\|spotify`, `app <name>\|any`, `play`, `pause`, `toggle`, `next`, `prev` | music panel (no Premium needed); `login`, `logout`, `volume` are for the optional Spotify source |
| `/ds hud <element> scale\|bg\|color\|on\|off\|reset`, `/ds theme`, `/ds style`, `/ds format`, `/ds customize` | per-element look, themes, number format |
| `/ds chat set <option> <value>`, `settings`, `ignore ...`, `highlight ...` | stream chat look and filters |

Amounts accept `k`, `m`, `b`, `t` (for example `1.5m`). Keybinds (**B**, **H**) can be changed in Controls.

## Files

- `config/donutstreamer.json`: session and settings (saved in the background every few seconds and on exit)
- `config/donutstreamer-spotify.json`: your Spotify Client ID and refresh token (keep private)
- `config/donutstreamer-chat.json`: your Twitch channel, YouTube video and YouTube API key (keep private)
- `<game dir>/donutstreamer/bets.csv`: every payment, for post-stream review

## Performance

Nothing touches the disk on the game thread. HUD text, chart data and the player table are cached and only
rebuilt when something changes, chat lines without a `$` are skipped before any pattern matching, and the item
list for the browser is built once at startup.

## Safety

No packets are sent to Minecraft servers by the mod, no automation, no input simulation, no world or inventory reads.
It reads chat you can already see, keeps numbers, and draws a HUD. The only network traffic is the optional Spotify
Web API source talking to Spotify after you sign in (the default music source is local only: it reads your media controls through a hidden PowerShell process, `playerctl` or `osascript`), and the optional stream chat (Twitch's public chat server, or the YouTube Data API) after you set it up.

## Building

```
gradle build
```

needs JDK 21 and Gradle 9.x (or let GitHub Actions do it). The jar is `build/libs/donutstreamer-<version>.jar`
(not the `-sources` jar).

### On GitHub

The workflow lives at `.github/workflows/build.yml`. Some uploaders skip hidden folders, so if the **Actions** tab
shows no workflow, add that file by hand: *Add file > Create new file*, type `.github/workflows/build.yml` as the
name, and paste the contents.

- Every push builds the mod: download the `donutstreamer-jar` artifact from the run.
- Push a tag like `v1.0.0` and the workflow also publishes a GitHub Release with the jar attached.

## License

MIT, see `LICENSE`.
