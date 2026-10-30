# Changelog

## 1.0.0
- Music now works without Premium: the panel reads your computer's media controls (Spotify Free, YouTube Music, Apple Music, browsers...). `/ds music source spotify` keeps the old Web API source.
- Every HUD element has its own size, background opacity and colour; the HUD editor now selects elements, resizes them by dragging a corner, and has an inspector panel. New `/ds hud <element> scale|bg|color|on|off|reset`.
- Themes (`/ds theme`), card style switches (`/ds style glow|shadow|animations`), number format (`/ds format`), `/ds customize`.
- Stream chat: long messages wrap instead of being cut off; bubbles, owner/mod/VIP/sub badges, timestamps, highlight words, ignore list, bot and link filters, newest-at-top, fade/expire, and many look options via `/ds chat set`.
- Stream mode (`/ds stream`): hides F3 coordinates (position, chunk, targeted block/fluid), optionally blanks coordinates in incoming chat, and hides player names on the HUD.
- Own nametag in third person (F5, `/ds nametag`), drawn through the game's normal nametag path so team prefixes and colours show like they do for other players.
- Optional on-screen Twitch and YouTube chat panel (`/ds chat ...`), read-only, with its own HUD element.
- Small blue "Sirens Streamer Mod" watermark, bottom-left, toggle with `/ds watermark`.
- Adds two small mixins (`donutstreamer.mixins.json`), set to fail soft: if a Minecraft update changes the target, that one feature turns itself off instead of crashing.
- UI polish pass: gradient HUD cards with a glowing accent edge and layered shadows, lit progress bars, tracker window with glassy stat cards and a live dot, HUD editor with toggle switches, auction browser with rounded rows, price pills and a live countdown-ring preview of the time you type.
- Auction panel: 12-tick countdown ring with a bright head dot, red pulsing border and flashing ring in the last 10s, item slot, and the top bid flashes white when someone outbids.
- Spotify panel: bigger glossy cover with a pulsing glow, cover-tinted card wash, 6-bar equalizer with peak caps, PLAYING/PAUSED tag, and a progress bar with a glowing knob.
- All new visuals are flat `fill` calls driven by the clock; text is still cached and nothing allocates per frame.
- Visual refresh: rounded cards with soft shadows, dim labels with bright values, rounded bars, a circular auction countdown, and a redesigned Spotify panel (cover art, equalizer, scrolling title, progress knob).
- Spotify remote: now-playing HUD panel plus play/pause/next/previous/volume from the game (`/ds music`, keybinds), via Spotify's Web API with PKCE sign-in. Client-side only; needs Spotify Premium.
- Merged the Donut Auction HUD into Donut Streamer: one mod, one config, one HUD editor.
- Auction panel is now a draggable, scalable HUD element; `/ds auction ...` commands (old `/auctionstart` and `/auctionstop` kept as aliases).
- Auction bids use the tracker's anchored chat pattern, so fake lines typed in public chat can't hijack an auction.
- Performance: background config/CSV writes, cached HUD text and chart data, chat pre-filter, item list built at startup, no per-frame allocation in the browser.
- Fixes: amount parser no longer accepts `5d`/`1e9`/`NaN`; `999999` formats as `1M` instead of `1000K`; CSV fields are quoted; corrupt configs are kept as `.corrupt` instead of overwritten; widgets can no longer be stranded off-screen.
- Added icon, license, `.gitignore`, and a release workflow.
