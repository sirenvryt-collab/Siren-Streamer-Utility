package com.donutstreamer;

import com.donutstreamer.auction.AuctionBrowserScreen;
import com.donutstreamer.auction.AuctionState;
import com.donutstreamer.auction.ServerDetector;
import com.donutstreamer.auction.TimeParse;
import com.donutstreamer.chat.StreamChat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Everything the tracker does. All feedback is local (action bar / own chat).
 * Nothing here sends packets or touches the world, inventory or other players.
 */
public final class Actions {
    private Actions() {}

    private static boolean limitAlerted = false;
    private static boolean goalAlerted = false;
    private static long resetArmedUntil = 0;

    private static Session s() { return DonutStreamerClient.session; }

    public static void bar(Formatting color, String text) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p != null) p.sendMessage(Text.literal(text).formatted(color), true);
    }

    public static void chat(Formatting color, String text) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p != null) p.sendMessage(Text.literal(text).formatted(color), false);
    }

    private static void checkAlerts() {
        Session s = s();

        if (s.lossLimit > 0 && s.net <= -s.lossLimit) {
            if (!limitAlerted) {
                limitAlerted = true;
                chat(Formatting.RED, "[Tracker] Loss limit reached (" + Money.format(s.lossLimit)
                        + "). Live: " + Money.signed(s.net) + ". Time to take a break.");
            }
        } else {
            limitAlerted = false;
        }

        if (s.goal > 0 && s.net >= s.goal) {
            if (!goalAlerted) {
                goalAlerted = true;
                chat(Formatting.GREEN, "[Tracker] Profit goal reached (" + Money.format(s.goal)
                        + "). Live: " + Money.signed(s.net) + ".");
            }
        } else {
            goalAlerted = false;
        }

        if (s.tiltAlert > 0 && s.streak == -s.tiltAlert) {
            chat(Formatting.GOLD, "[Tracker] " + s.tiltAlert + " payments sent in a row with nothing back. Maybe slow down?");
        }
    }

    /** Called for every payment / sale message read from chat (and by /ds in|out|sell). */
    public static void record(String kind, String who, long amount) {
        Session s = s();
        Session.Entry e = s.add(kind, who, amount);
        s.save();
        s.logCsv(kind, e.who, e.amount, s.delta(e));
        if (s.sound && Session.IN.equals(kind)) {
            ClientPlayerEntity pl = MinecraftClient.getInstance().player;
            if (pl != null) pl.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        checkAlerts();
    }

    public static void undo() {
        Session s = s();
        Session.Entry e = s.undo();
        if (e == null) {
            bar(Formatting.GRAY, "Nothing to undo");
            return;
        }
        s.save();
        s.logCsv("UNDO_" + e.kind, e.who, e.amount, -s.delta(e));
        bar(Formatting.YELLOW, "Removed last entry  |  Live " + Money.signed(s.net));
        checkAlerts();
    }

    public static void requestReset() {
        long now = System.currentTimeMillis();
        if (now <= resetArmedUntil) {
            resetArmedUntil = 0;
            Session s = s();
            s.reset();
            s.save();
            s.logCsv("RESET", "", 0, 0);
            limitAlerted = false;
            goalAlerted = false;
            bar(Formatting.YELLOW, "Session reset");
        } else {
            resetArmedUntil = now + 5000;
            bar(Formatting.GOLD, "Run /ds reset again within 5s to confirm");
        }
    }

    public static void toggleHud() {
        s().hudVisible = !s().hudVisible;
        s().save();
        bar(Formatting.GRAY, "HUD " + (s().hudVisible ? "shown" : "hidden"));
    }

    public static void toggleWidget(Widgets.W w) {
        Session.WidgetCfg c = s().cfg(w);
        c.on = !c.on;
        s().save();
        bar(Formatting.GRAY, w.label + " " + (c.on ? "on" : "off"));
    }

    public static void setAllWidgets(boolean on) {
        for (Widgets.W w : Widgets.W.values()) s().cfg(w).on = on;
        s().save();
        bar(Formatting.GRAY, "All HUD elements " + (on ? "on" : "off"));
    }

    public static void resetLayout() {
        s().widgets.clear();
        s().scale = 1.0f;
        s().save();
        bar(Formatting.GRAY, "HUD layout reset");
    }

    public static void toggleSound() {
        s().sound = !s().sound;
        s().save();
        bar(Formatting.GRAY, "Sounds " + (s().sound ? "on" : "off"));
    }

    public static void toggleNames() {
        s().showNames = !s().showNames;
        s().save();
        bar(Formatting.GRAY, "Player names " + (s().showNames ? "shown" : "hidden"));
    }

    public static void toggleDoubles() {
        s().doubleTag = !s().doubleTag;
        s().save();
        bar(Formatting.GRAY, "Doubled-payment tag " + (s().doubleTag ? "on" : "off"));
    }

    public static void togglePercent() {
        s().percentMode = !s().percentMode;
        s().save();
        bar(Formatting.GRAY, s().percentMode
                ? (s().startBalance > 0 ? "Live shown as % of starting balance" : "Percent mode on (set /ds balance first)")
                : "Live shown as an amount");
    }

    public static void toggleSells() {
        s().includeSells = !s().includeSells;
        s().recompute();
        s().save();
        bar(Formatting.GRAY, "Sells " + (s().includeSells ? "counted in" : "not counted in") + " net profit");
    }

    public static void toggleSellSource() {
        s().sellsFromActionBar = !s().sellsFromActionBar;
        s().save();
        bar(Formatting.GRAY, "Reading sales from " + (s().sellsFromActionBar ? "the action bar" : "chat"));
    }

    public static void setHudScale(float scale) {
        s().scale = scale;
        s().save();
        bar(Formatting.GRAY, "HUD scale " + scale);
    }

    public static void setLimit(long amount) {
        s().lossLimit = amount;
        s().save();
        limitAlerted = false;
        checkAlerts();
        bar(Formatting.AQUA, amount > 0 ? "Loss limit: " + Money.format(amount) : "Loss limit off");
    }

    public static void setGoal(long amount) {
        s().goal = amount;
        s().save();
        goalAlerted = false;
        checkAlerts();
        bar(Formatting.AQUA, amount > 0 ? "Profit goal: " + Money.format(amount) : "Profit goal off");
    }

    public static void setTilt(int n) {
        s().tiltAlert = n;
        s().save();
        bar(Formatting.AQUA, n > 0 ? "Alert after " + n + " payments sent in a row" : "Loss-streak alert off");
    }

    public static void setRakeback(double pct) {
        s().rakebackPct = pct;
        s().save();
        bar(Formatting.AQUA, pct > 0 ? "Rakeback: " + pct + "% of what each player sent you" : "Rakeback tally off");
    }

    public static void setBalance(long amount) {
        s().startBalance = amount;
        s().save();
        bar(Formatting.AQUA, amount >= 0 ? "Starting balance: " + Money.format(amount) : "Balance display off");
    }

    public static void summary() {
        String text = s().summary();
        chat(Formatting.GOLD, text);
        MinecraftClient.getInstance().keyboard.setClipboard(text);
        chat(Formatting.GRAY, "(copied to clipboard)");
    }

    public static void players() {
        Session s = s();
        List<Session.PlayerStat> list = s.playerStats();
        if (list.isEmpty()) {
            chat(Formatting.GRAY, "No player payments yet this session.");
            return;
        }
        chat(Formatting.GOLD, "Payments by player (this session):");
        int shown = 0;
        for (Session.PlayerStat p : list) {
            if (shown++ >= 10) break;
            String rake = s.rakebackPct > 0 ? "  rakeback owed " + Money.format(s.rakebackOwed(p)) : "";
            chat(p.net() >= 0 ? Formatting.GREEN : Formatting.RED,
                    p.name + ": in " + Money.format(p.in) + ", out " + Money.format(p.out)
                            + ", net " + Money.signed(p.net()) + rake);
        }
    }

    // ================= auction =================

    private static MutableText auctionPrefix() {
        return Text.literal("[Auction] ").formatted(Formatting.BLUE, Formatting.BOLD);
    }

    private static void auctionChat(MutableText body) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p != null) p.sendMessage(auctionPrefix().append(body), false);
    }

    /** Auction tools only run on DonutSMP (unless "/ds auction anywhere" is on). Tells the player when blocked. */
    private static boolean requireDonut() {
        if (ServerDetector.isDonutSmp()) return true;
        chat(Formatting.RED, "Auction tools only work on DonutSMP. (To test elsewhere: /ds auction anywhere)");
        return false;
    }

    /** Starts an auction from typed text (commands). Prints what was wrong when the input is invalid. */
    public static void auctionStartText(String item, String minBidRaw, String timeRaw) {
        if (!requireDonut()) return;
        long minBid;
        try {
            minBid = Money.parse(minBidRaw);
        } catch (NumberFormatException e) {
            minBid = -1;
        }
        if (minBid <= 0) {
            chat(Formatting.RED, "Enter a valid minimum bid, e.g. 250k or 1.5m.");
            return;
        }
        int seconds = TimeParse.seconds(timeRaw);
        if (seconds <= 0) {
            chat(Formatting.RED, "Enter a valid time, e.g. 5m, 90s or 1m30s (a plain number means minutes).");
            return;
        }
        AuctionState.INSTANCE.start(item, minBid, seconds);
        announceAuctionStart(item, minBid, seconds);
    }

    /** Local chat line shown when an auction starts (from the browser or a command). */
    public static void announceAuctionStart(String item, long minBid, int seconds) {
        auctionChat(Text.literal("Started auction for ").formatted(Formatting.GRAY)
                .append(Text.literal(item).formatted(Formatting.WHITE))
                .append(Text.literal(" (min bid $" + Money.format(minBid) + ", " + TimeParse.format(seconds) + ")")
                        .formatted(Formatting.GRAY)));
    }

    public static void auctionStop() {
        AuctionState st = AuctionState.INSTANCE;
        if (!st.isActive()) {
            chat(Formatting.GRAY, "No auction is running.");
            return;
        }
        st.stop();   // the result is announced on the next tick
    }

    public static void auctionStatus() {
        AuctionState st = AuctionState.INSTANCE;
        if (!st.isActive()) {
            chat(Formatting.GRAY, "No auction is running. Press B or use /ds auction start \"Item\" <min bid> [time].");
            return;
        }
        String top = st.hasBid()
                ? st.getHighestBidder() + " with $" + Money.format(st.getHighestBid())
                : "no bids yet";
        auctionChat(Text.literal(st.getCurrentItemName()).formatted(Formatting.WHITE)
                .append(Text.literal("  |  ends in " + TimeParse.clock((st.getRemainingMillis() + 999) / 1000)
                        + "  |  min $" + Money.format(st.getMinimumBid()) + "  |  top: " + top)
                        .formatted(Formatting.GRAY)));
    }

    /** Shown in the action bar when a bid becomes the highest. */
    public static void auctionNewTopBid(String who, long amount) {
        bar(Formatting.AQUA, "New top bid: " + who + " $" + Money.format(amount));
        if (s().sound) {
            ClientPlayerEntity pl = MinecraftClient.getInstance().player;
            if (pl != null) pl.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        }
    }

    public static void announceAuctionResult(AuctionState.AuctionResult result) {
        if (result.hasWinner()) {
            auctionChat(Text.literal(result.winner()).formatted(Formatting.AQUA, Formatting.BOLD)
                    .append(Text.literal(" won ").formatted(Formatting.GRAY))
                    .append(Text.literal(result.itemName()).formatted(Formatting.WHITE))
                    .append(Text.literal(" with a bid of ").formatted(Formatting.GRAY))
                    .append(Text.literal("$" + Money.format(result.winningBid())).formatted(Formatting.GREEN))
                    .append(Text.literal("!").formatted(Formatting.GRAY)));
        } else {
            auctionChat(Text.literal("Auction for " + result.itemName() + " ended with no bids.")
                    .formatted(Formatting.GRAY));
        }
    }

    public static void openAuctionBrowser() {
        if (!requireDonut()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> mc.setScreen(new AuctionBrowserScreen()));   // next tick, so the chat screen can close first
    }

    public static void toggleAuctionAnywhere() {
        s().auctionAnyServer = !s().auctionAnyServer;
        s().save();
        bar(Formatting.GRAY, "Auction tools " + (s().auctionAnyServer ? "enabled on any server" : "limited to DonutSMP"));
    }

    public static void toggleAuctionTracking() {
        s().trackAuctionPayments = !s().trackAuctionPayments;
        s().save();
        bar(Formatting.GRAY, "Auction bids " + (s().trackAuctionPayments ? "also count" : "do not count") + " in the tracker");
    }

    public static void openWindow() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> mc.setScreen(new TrackerScreen()));   // next tick, so the chat screen can close first
    }

    // ================= stream mode, nametag, watermark, stream chat =================

    private static String onOff(boolean v) {
        return v ? "on" : "off";
    }

    public static void toggleStreamMode() {
        Session s = s();
        s.streamMode = !s.streamMode;
        s.save();
        if (s.streamMode) {
            chat(Formatting.GREEN, "[Stream mode] ON. F3 coordinates: " + (s.streamHideCoords ? "hidden" : "shown")
                    + ", chat coordinates: " + (s.streamHideChatCoords ? "hidden" : "shown")
                    + ", HUD player names: " + (s.streamHideNames ? "hidden" : "shown")
                    + ". Change with /ds stream coords|chatcoords|names.");
        } else {
            chat(Formatting.GRAY, "[Stream mode] OFF. Everything shows normally again.");
        }
    }

    public static void toggleStreamCoords() {
        s().streamHideCoords = !s().streamHideCoords;
        s().save();
        bar(Formatting.GRAY, "Hide F3 coordinates " + onOff(s().streamHideCoords) + (s().streamMode ? "" : " (needs stream mode: /ds stream)"));
    }

    public static void toggleStreamChatCoords() {
        s().streamHideChatCoords = !s().streamHideChatCoords;
        s().save();
        bar(Formatting.GRAY, "Hide coordinates in chat " + onOff(s().streamHideChatCoords) + (s().streamMode ? "" : " (needs stream mode: /ds stream)"));
    }

    public static void toggleStreamNames() {
        s().streamHideNames = !s().streamHideNames;
        s().save();
        bar(Formatting.GRAY, "Hide HUD player names in stream mode " + onOff(s().streamHideNames));
    }

    public static void streamStatus() {
        Session s = s();
        chat(Formatting.GOLD, "Stream mode is " + onOff(s.streamMode));
        chat(Formatting.WHITE, "  /ds stream coords      hide F3 position, chunk and targeted block: " + onOff(s.streamHideCoords));
        chat(Formatting.WHITE, "  /ds stream chatcoords  blank \"x, y, z\" in incoming chat: " + onOff(s.streamHideChatCoords));
        chat(Formatting.WHITE, "  /ds stream names       hide player names on the HUD: " + onOff(s.streamHideNames));
    }

    public static void toggleSelfNametag() {
        s().selfNametag = !s().selfNametag;
        s().save();
        bar(Formatting.GRAY, "Own nametag in F5 " + onOff(s().selfNametag));
    }

    public static void toggleWatermark() {
        s().watermark = !s().watermark;
        s().save();
        bar(Formatting.GRAY, "Watermark " + onOff(s().watermark));
    }

    public static void chatStatus() {
        chat(Formatting.LIGHT_PURPLE, "Stream chat: " + StreamChat.statusLine());
        chat(Formatting.WHITE, "  Twitch: " + (StreamChat.twitchChannel().isEmpty() ? "not set" : "#" + StreamChat.twitchChannel())
                + "   YouTube: " + (StreamChat.youtubeVideo().isEmpty() ? "not set" : StreamChat.youtubeVideo())
                + (StreamChat.hasYouTubeKey() ? " (key saved)" : " (no API key)")
                + "   hide !commands: " + onOff(StreamChat.hideCommands()));
        chat(Formatting.GRAY, "  Show the panel with /ds hud chat (drag it in the HUD editor).");
    }

    public static void chatTwitch(String channel) {
        String err = StreamChat.setTwitch(channel);
        if (err != null) {
            chat(Formatting.RED, err);
            return;
        }
        s().cfg(Widgets.W.CHAT).on = true;
        s().save();
        chat(Formatting.LIGHT_PURPLE, "Stream chat: connecting to Twitch #" + StreamChat.twitchChannel() + " (read only).");
    }

    public static void chatTwitchOff() {
        StreamChat.clearTwitch();
        chat(Formatting.GRAY, "Twitch chat disconnected.");
    }

    public static void chatYouTube(String video) {
        String err = StreamChat.setYouTube(video);
        if (err != null) {
            chat(Formatting.RED, err);
            return;
        }
        s().cfg(Widgets.W.CHAT).on = true;
        s().save();
        if (StreamChat.hasYouTubeKey()) {
            chat(Formatting.RED, "Stream chat: connecting to YouTube video " + StreamChat.youtubeVideo() + " (read only).");
        } else {
            chat(Formatting.GOLD, "Video saved. YouTube also needs your own API key: copy it, then run /ds chat youtubekey paste "
                    + "(pasting keeps it off your screen).");
        }
    }

    public static void chatYouTubeOff() {
        StreamChat.clearYouTube();
        chat(Formatting.GRAY, "YouTube chat disconnected.");
    }

    public static void chatYouTubeKey(String key) {
        String err = StreamChat.setYouTubeKey(key);
        if (err != null) {
            chat(Formatting.RED, err);
        } else {
            chat(Formatting.GREEN, "YouTube API key saved to config/donutstreamer-chat.json (keep that file private)."
                    + (StreamChat.youtubeVideo().isEmpty() ? " Now run /ds chat youtube <link>." : ""));
        }
    }

    public static void chatYouTubeKeyPaste() {
        String clip = MinecraftClient.getInstance().keyboard.getClipboard();
        chatYouTubeKey(clip);
    }

    public static void chatYouTubeKeyClear() {
        StreamChat.clearYouTubeKey();
        chat(Formatting.GRAY, "YouTube API key removed.");
    }

    public static void chatOff() {
        StreamChat.disconnectAll();
        chat(Formatting.GRAY, "Stream chat disconnected (settings are kept; reconnect with /ds chat twitch <channel>).");
    }

    public static void chatClear() {
        StreamChat.clearMessages();
        bar(Formatting.GRAY, "Stream chat cleared");
    }

    public static void chatToggleCommands() {
        boolean now = StreamChat.toggleHideCommands();
        bar(Formatting.GRAY, "Hide !commands in stream chat " + onOff(now));
    }

    // ================= per-element look, themes, number format, chat settings =================

    public static void setWidgetOn(Widgets.W w, boolean on) {
        s().cfg(w).on = on;
        s().save();
        bar(Formatting.GRAY, w.label + " " + (on ? "on" : "off"));
    }

    public static void setWidgetScale(Widgets.W w, float v) {
        s().cfg(w).scale = Math.max(0.4f, Math.min(4.0f, v));
        s().save();
        bar(Formatting.GRAY, w.label + " size " + String.format(java.util.Locale.ROOT, "%.2f", s().cfg(w).scale));
    }

    public static void setWidgetBg(Widgets.W w, int percent) {
        s().cfg(w).bg = Math.max(10, Math.min(100, percent)) / 100f;
        s().save();
        bar(Formatting.GRAY, w.label + " background " + Math.round(s().cfg(w).bg * 100) + "%");
    }

    public static void setWidgetColor(Widgets.W w, String color) {
        if (color == null || color.equalsIgnoreCase("default") || color.equalsIgnoreCase("reset")) {
            s().cfg(w).accent = 0;
            s().save();
            bar(Formatting.GRAY, w.label + " colour back to default");
            return;
        }
        Integer c = Theme.parseColor(color);
        if (c == null) {
            chat(Formatting.RED, "Colours: " + String.join(", ", Theme.colorNames()) + ", or #RRGGBB (or \"default\").");
            return;
        }
        s().cfg(w).accent = c;
        s().save();
        bar(Formatting.GRAY, w.label + " colour set");
    }

    public static void resetWidget(Widgets.W w) {
        Session.WidgetCfg c = s().cfg(w);
        int[] p = Widgets.defaultPos(w);
        c.x = p[0];
        c.y = p[1];
        c.scale = 1.0f;
        c.bg = 1.0f;
        c.accent = 0;
        s().save();
        bar(Formatting.GRAY, w.label + " reset");
    }

    public static void themeList() {
        chat(Formatting.GOLD, "HUD themes (current: " + s().theme + "). Use /ds theme <name>:");
        for (Theme.Preset p : Theme.PRESETS) {
            chat(p.id().equals(s().theme) ? Formatting.GREEN : Formatting.WHITE, "  " + p.id() + " - " + p.label());
        }
    }

    public static void setTheme(String id) {
        Theme.Preset p = Theme.find(id);
        if (p == null) {
            chat(Formatting.RED, "No theme called \"" + id + "\". Run /ds theme for the list.");
            return;
        }
        s().theme = p.id();
        s().save();
        bar(Formatting.GRAY, "Theme: " + p.label());
    }

    public static void toggleGlow() {
        s().cardGlow = !s().cardGlow;
        s().save();
        bar(Formatting.GRAY, "Glowing card edges " + onOff(s().cardGlow));
    }

    public static void toggleShadow() {
        s().cardShadow = !s().cardShadow;
        s().save();
        bar(Formatting.GRAY, "Card shadows " + onOff(s().cardShadow));
    }

    public static void toggleAnimations() {
        s().animations = !s().animations;
        s().save();
        bar(Formatting.GRAY, "Animations " + onOff(s().animations));
    }

    public static void setMoneyFull(boolean full) {
        s().moneyFull = full;
        s().save();
        bar(Formatting.GRAY, "Amounts shown as " + (full ? "45,000,000" : "45M"));
    }

    public static void setMoneyDecimals(int n) {
        s().moneyDecimals = Math.max(0, Math.min(3, n));
        s().save();
        bar(Formatting.GRAY, "Short amounts use " + s().moneyDecimals + " decimals");
    }

    public static void chatSettings() {
        chat(Formatting.LIGHT_PURPLE, "Stream chat settings (change with /ds chat set <option> <value>):");
        for (String line : StreamChat.describe()) chat(Formatting.WHITE, "  " + line);
        chat(Formatting.GRAY, "  Also: /ds chat ignore add|remove|list|clear <name>   /ds chat highlight add|remove|list|clear <word>");
        chat(Formatting.GRAY, "  Size and background: /ds hud chat scale <n> and /ds hud chat bg <10-100>");
    }

    public static void chatSet(String key, String value) {
        StreamChat.Result r = StreamChat.set(key, value);
        chat(r.ok() ? Formatting.GREEN : Formatting.RED, r.message());
    }

    public static void chatIgnore(boolean add, String name) {
        StreamChat.Result r = StreamChat.ignore(add, name);
        chat(r.ok() ? Formatting.GRAY : Formatting.RED, r.message());
    }

    public static void chatIgnoreList() {
        java.util.List<String> l = StreamChat.ignored();
        chat(Formatting.GRAY, l.isEmpty() ? "Nobody is ignored." : "Ignored: " + String.join(", ", l));
    }

    public static void chatIgnoreClear() {
        StreamChat.clearIgnored();
        chat(Formatting.GRAY, "Ignore list cleared.");
    }

    public static void chatHighlight(boolean add, String word) {
        StreamChat.Result r = StreamChat.highlight(add, word);
        chat(r.ok() ? Formatting.GRAY : Formatting.RED, r.message());
    }

    public static void chatHighlightList() {
        java.util.List<String> l = StreamChat.highlights();
        chat(Formatting.GRAY, l.isEmpty() ? "No highlight words." : "Highlighting: " + String.join(", ", l));
    }

    public static void chatHighlightClear() {
        StreamChat.clearHighlights();
        chat(Formatting.GRAY, "Highlight words cleared.");
    }

    public static void customizeHelp() {
        chat(Formatting.GOLD, "Customize everything:");
        chat(Formatting.WHITE, "  Open chat and click \"HUD editor\": click an element to select it, drag to move, drag its bottom-right corner to resize, and use the panel for size, background, colour and reset.");
        chat(Formatting.WHITE, "  /ds hud <element> scale <0.4-4> | bg <10-100> | color <name|#hex|default> | reset | on | off");
        chat(Formatting.WHITE, "  /ds hud scale <n>   size of everything at once (multiplies with each element's own size)");
        chat(Formatting.WHITE, "  /ds theme [name]   /ds style glow|shadow|animations   /ds format short|full|decimals <0-3>");
        chat(Formatting.WHITE, "  /ds chat settings   all chat look + filter options    /ds music source|app   which player the music panel follows");
        chat(Formatting.WHITE, "  /ds stream status   stream-safety options              /ds help   everything else");
    }

    public static void help() {
        chat(Formatting.GOLD, "Donut Streamer (all local). Profit is read from payment messages in chat.");
        chat(Formatting.WHITE, "/ds  opens the tracker window. In chat, use the \"HUD editor\" button; drag elements to move.");
        chat(Formatting.WHITE, "/ds in|out <amt> [name]  /ds buy <amt> [item]  /ds sell <amt>  (manual entry)   /ds undo  /ds reset  /ds summary  /ds players");
        chat(Formatting.WHITE, "/ds limit|goal <amt|off>  /ds tilt <2-20|off>  /ds rakeback <0.5-50|off>  /ds balance <amt|off>");
        chat(Formatting.WHITE, "/ds percent  /ds sells  /ds sellsource  /ds doubles  /ds sound  /ds names  /ds hud toggle|reset|all on|all off|scale <n>|<element>");
        chat(Formatting.AQUA, "Auctions (DonutSMP): B opens the browser, H shows/hides the panel.");
        chat(Formatting.GREEN, "Spotify remote: /ds music login <clientId>, then \\ play/pause, ] next, [ previous (rebindable).");
        chat(Formatting.WHITE, "/ds music play|pause|toggle|next|prev|volume <0-100>|status|logout");
        chat(Formatting.GOLD, "Look: /ds customize (per-element size/background/colour, themes, number format, chat options)");
        chat(Formatting.AQUA, "Stream: /ds stream (mode on/off)  /ds stream coords|chatcoords|names|status  /ds nametag (own tag in F5)  /ds watermark");
        chat(Formatting.LIGHT_PURPLE, "Stream chat: /ds chat twitch <channel>|off  /ds chat youtube <link>|off  /ds chat youtubekey paste|<key>|clear  /ds chat off|clear|commands|status");
        chat(Formatting.WHITE, "/ds auction start \"<item>\" <min bid> [time]  /ds auction stop|status|browser  /ds auction track|anywhere");
    }
}
