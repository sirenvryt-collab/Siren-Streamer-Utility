package com.donutstreamer;

import com.donutstreamer.chat.StreamChat;
import com.donutstreamer.music.Music;
import com.donutstreamer.music.Spotify;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.util.Formatting;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class Commands {
    private Commands() {}

    private static Long amount(CommandContext<FabricClientCommandSource> c, String name) {
        try {
            return Money.parse(StringArgumentType.getString(c, name));
        } catch (NumberFormatException e) {
            Actions.chat(Formatting.RED, "Invalid amount. Try something like 5000, 10k or 1.5m.");
            return null;
        }
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> entry(String name, String kind) {
        return literal(name).then(argument("amount", StringArgumentType.word())
            .executes(c -> {
                Long v = amount(c, "amount");
                if (v != null && v > 0) Actions.record(kind, "", v);
                return 1;
            })
            .then(argument("who", StringArgumentType.word()).executes(c -> {
                Long v = amount(c, "amount");
                if (v != null && v > 0) Actions.record(kind, StringArgumentType.getString(c, "who"), v);
                return 1;
            })));
    }

    /** start "<item>" <minBid> [time]  (time defaults to 5m; a plain number means minutes) */
    private static LiteralArgumentBuilder<FabricClientCommandSource> auctionStartNode(String name, boolean legacySeconds) {
        return literal(name).then(argument("item", StringArgumentType.string())
            .then(argument("minBid", StringArgumentType.word())
                .executes(c -> {
                    Actions.auctionStartText(StringArgumentType.getString(c, "item"),
                            StringArgumentType.getString(c, "minBid"), "5m");
                    return 1;
                })
                .then(argument("time", StringArgumentType.word()).executes(c -> {
                    String time = StringArgumentType.getString(c, "time");
                    // The old /auctionstart took plain seconds; keep that for the legacy alias only.
                    if (legacySeconds && time.matches("\\d+")) time = time + "s";
                    Actions.auctionStartText(StringArgumentType.getString(c, "item"),
                            StringArgumentType.getString(c, "minBid"), time);
                    return 1;
                }))));
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> d, CommandRegistryAccess access) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("ds");
        root.executes(c -> { Actions.openWindow(); return 1; });

        root.then(literal("help").executes(c -> { Actions.help(); return 1; }));
        root.then(literal("window").executes(c -> { Actions.openWindow(); return 1; }));

        // manual entry, for anything the chat reader missed
        root.then(entry("in", Session.IN));
        root.then(entry("out", Session.OUT));
        root.then(entry("buy", Session.BUY));
        root.then(literal("sell").then(argument("amount", StringArgumentType.word()).executes(c -> {
            Long v = amount(c, "amount");
            if (v != null && v > 0) Actions.record(Session.SELL, "", v);
            return 1;
        })));

        root.then(literal("undo").executes(c -> { Actions.undo(); return 1; }));
        root.then(literal("reset").executes(c -> { Actions.requestReset(); return 1; }));
        root.then(literal("summary").executes(c -> { Actions.summary(); return 1; }));
        root.then(literal("players").executes(c -> { Actions.players(); return 1; }));
        root.then(literal("sound").executes(c -> { Actions.toggleSound(); return 1; }));
        root.then(literal("names").executes(c -> { Actions.toggleNames(); return 1; }));
        root.then(literal("doubles").executes(c -> { Actions.toggleDoubles(); return 1; }));
        root.then(literal("percent").executes(c -> { Actions.togglePercent(); return 1; }));
        root.then(literal("sells").executes(c -> { Actions.toggleSells(); return 1; }));
        root.then(literal("sellsource").executes(c -> { Actions.toggleSellSource(); return 1; }));

        root.then(literal("limit")
            .then(literal("off").executes(c -> { Actions.setLimit(0); return 1; }))
            .then(argument("amount", StringArgumentType.word()).executes(c -> {
                Long v = amount(c, "amount");
                if (v != null) Actions.setLimit(v);
                return 1;
            })));

        root.then(literal("goal")
            .then(literal("off").executes(c -> { Actions.setGoal(0); return 1; }))
            .then(argument("amount", StringArgumentType.word()).executes(c -> {
                Long v = amount(c, "amount");
                if (v != null) Actions.setGoal(v);
                return 1;
            })));

        root.then(literal("balance")
            .then(literal("off").executes(c -> { Actions.setBalance(-1); return 1; }))
            .then(argument("amount", StringArgumentType.word()).executes(c -> {
                Long v = amount(c, "amount");
                if (v != null) Actions.setBalance(v);
                return 1;
            })));

        root.then(literal("tilt")
            .then(literal("off").executes(c -> { Actions.setTilt(0); return 1; }))
            .then(argument("n", IntegerArgumentType.integer(2, 20)).executes(c -> {
                Actions.setTilt(IntegerArgumentType.getInteger(c, "n"));
                return 1;
            })));

        root.then(literal("rakeback")
            .then(literal("off").executes(c -> { Actions.setRakeback(0); return 1; }))
            .then(argument("pct", DoubleArgumentType.doubleArg(0.5, 50)).executes(c -> {
                Actions.setRakeback(DoubleArgumentType.getDouble(c, "pct"));
                return 1;
            })));

        LiteralArgumentBuilder<FabricClientCommandSource> hud = literal("hud");
        hud.then(literal("toggle").executes(c -> { Actions.toggleHud(); return 1; }));
        hud.then(literal("reset").executes(c -> { Actions.resetLayout(); return 1; }));
        hud.then(literal("scale").then(argument("n", FloatArgumentType.floatArg(0.5f, 3.0f)).executes(c -> {
            Actions.setHudScale(FloatArgumentType.getFloat(c, "n"));
            return 1;
        })));
        hud.then(literal("all")
            .then(literal("on").executes(c -> { Actions.setAllWidgets(true); return 1; }))
            .then(literal("off").executes(c -> { Actions.setAllWidgets(false); return 1; })));
        for (Widgets.W w : Widgets.W.values()) {
            hud.then(literal(w.id)
                .executes(c -> { Actions.toggleWidget(w); return 1; })
                .then(literal("on").executes(c -> { Actions.setWidgetOn(w, true); return 1; }))
                .then(literal("off").executes(c -> { Actions.setWidgetOn(w, false); return 1; }))
                .then(literal("reset").executes(c -> { Actions.resetWidget(w); return 1; }))
                .then(literal("scale").then(argument("n", FloatArgumentType.floatArg(0.4f, 4.0f)).executes(c -> {
                    Actions.setWidgetScale(w, FloatArgumentType.getFloat(c, "n"));
                    return 1;
                })))
                .then(literal("bg").then(argument("percent", IntegerArgumentType.integer(10, 100)).executes(c -> {
                    Actions.setWidgetBg(w, IntegerArgumentType.getInteger(c, "percent"));
                    return 1;
                })))
                .then(literal("color").then(argument("color", StringArgumentType.word())
                    .suggests((ctx, b) -> {
                        String rem = b.getRemaining().toLowerCase();
                        for (String n : Theme.colorNames()) if (n.startsWith(rem)) b.suggest(n);
                        if ("default".startsWith(rem)) b.suggest("default");
                        return b.buildFuture();
                    })
                    .executes(c -> {
                        Actions.setWidgetColor(w, StringArgumentType.getString(c, "color"));
                        return 1;
                    }))));
        }
        root.then(hud);

        // ---- look: themes, card style, number format ----
        root.then(literal("customize").executes(c -> { Actions.customizeHelp(); return 1; }));
        root.then(literal("theme")
            .executes(c -> { Actions.themeList(); return 1; })
            .then(argument("name", StringArgumentType.word())
                .suggests((ctx, b) -> {
                    String rem = b.getRemaining().toLowerCase();
                    for (Theme.Preset p : Theme.PRESETS) if (p.id().startsWith(rem)) b.suggest(p.id());
                    return b.buildFuture();
                })
                .executes(c -> { Actions.setTheme(StringArgumentType.getString(c, "name")); return 1; })));
        root.then(literal("style")
            .then(literal("glow").executes(c -> { Actions.toggleGlow(); return 1; }))
            .then(literal("shadow").executes(c -> { Actions.toggleShadow(); return 1; }))
            .then(literal("animations").executes(c -> { Actions.toggleAnimations(); return 1; })));
        root.then(literal("format")
            .then(literal("short").executes(c -> { Actions.setMoneyFull(false); return 1; }))
            .then(literal("full").executes(c -> { Actions.setMoneyFull(true); return 1; }))
            .then(literal("decimals").then(argument("n", IntegerArgumentType.integer(0, 3)).executes(c -> {
                Actions.setMoneyDecimals(IntegerArgumentType.getInteger(c, "n"));
                return 1;
            }))));

        // ---- auctions (DonutSMP) ----
        LiteralArgumentBuilder<FabricClientCommandSource> auction = literal("auction");
        auction.executes(c -> { Actions.auctionStatus(); return 1; });
        auction.then(auctionStartNode("start", false));
        auction.then(literal("stop").executes(c -> { Actions.auctionStop(); return 1; }));
        auction.then(literal("status").executes(c -> { Actions.auctionStatus(); return 1; }));
        auction.then(literal("browser").executes(c -> { Actions.openAuctionBrowser(); return 1; }));
        auction.then(literal("track").executes(c -> { Actions.toggleAuctionTracking(); return 1; }));
        auction.then(literal("anywhere").executes(c -> { Actions.toggleAuctionAnywhere(); return 1; }));
        root.then(auction);

        // ---- music (system media controls by default, Spotify Web API optional) ----
        LiteralArgumentBuilder<FabricClientCommandSource> music = literal("music");
        music.executes(c -> { Music.showStatus(); return 1; });
        music.then(literal("status").executes(c -> { Music.showStatus(); return 1; }));
        music.then(literal("source")
            .then(literal("system").executes(c -> { Music.setSource("system"); return 1; }))
            .then(literal("spotify").executes(c -> { Music.setSource("spotify"); return 1; })));
        music.then(literal("app")
            .then(literal("any").executes(c -> { Music.setApp("any"); return 1; }))
            .then(argument("name", StringArgumentType.greedyString()).executes(c -> {
                Music.setApp(StringArgumentType.getString(c, "name"));
                return 1;
            })));
        music.then(literal("login").executes(c -> { Spotify.login(null); return 1; })
            .then(argument("clientId", StringArgumentType.word()).executes(c -> {
                Spotify.login(StringArgumentType.getString(c, "clientId"));
                return 1;
            })));
        music.then(literal("logout").executes(c -> { Spotify.logout(); return 1; }));
        music.then(literal("play").executes(c -> { Music.play(); return 1; }));
        music.then(literal("pause").executes(c -> { Music.pause(); return 1; }));
        music.then(literal("toggle").executes(c -> { Music.togglePlay(); return 1; }));
        music.then(literal("next").executes(c -> { Music.next(); return 1; }));
        music.then(literal("prev").executes(c -> { Music.previous(); return 1; }));
        music.then(literal("volume").then(argument("percent", IntegerArgumentType.integer(0, 100)).executes(c -> {
            Spotify.volume(IntegerArgumentType.getInteger(c, "percent"));
            return 1;
        })));
        root.then(music);

        // ---- stream mode / nametag / watermark ----
        root.then(literal("stream")
            .executes(c -> { Actions.toggleStreamMode(); return 1; })
            .then(literal("coords").executes(c -> { Actions.toggleStreamCoords(); return 1; }))
            .then(literal("chatcoords").executes(c -> { Actions.toggleStreamChatCoords(); return 1; }))
            .then(literal("names").executes(c -> { Actions.toggleStreamNames(); return 1; }))
            .then(literal("status").executes(c -> { Actions.streamStatus(); return 1; })));
        root.then(literal("nametag").executes(c -> { Actions.toggleSelfNametag(); return 1; }));
        root.then(literal("watermark").executes(c -> { Actions.toggleWatermark(); return 1; }));

        // ---- Twitch / YouTube chat on screen ----
        LiteralArgumentBuilder<FabricClientCommandSource> streamChat = literal("chat");
        streamChat.executes(c -> { Actions.chatStatus(); return 1; });
        streamChat.then(literal("status").executes(c -> { Actions.chatStatus(); return 1; }));
        streamChat.then(literal("twitch")
            .then(literal("off").executes(c -> { Actions.chatTwitchOff(); return 1; }))
            .then(argument("channel", StringArgumentType.greedyString()).executes(c -> {
                Actions.chatTwitch(StringArgumentType.getString(c, "channel"));
                return 1;
            })));
        streamChat.then(literal("youtube")
            .then(literal("off").executes(c -> { Actions.chatYouTubeOff(); return 1; }))
            .then(argument("video", StringArgumentType.greedyString()).executes(c -> {
                Actions.chatYouTube(StringArgumentType.getString(c, "video"));
                return 1;
            })));
        streamChat.then(literal("youtubekey")
            .then(literal("paste").executes(c -> { Actions.chatYouTubeKeyPaste(); return 1; }))
            .then(literal("clear").executes(c -> { Actions.chatYouTubeKeyClear(); return 1; }))
            .then(argument("key", StringArgumentType.word()).executes(c -> {
                Actions.chatYouTubeKey(StringArgumentType.getString(c, "key"));
                return 1;
            })));
        streamChat.then(literal("settings").executes(c -> { Actions.chatSettings(); return 1; }));
        streamChat.then(literal("set").then(argument("option", StringArgumentType.word())
            .suggests((ctx, b) -> {
                String rem = b.getRemaining().toLowerCase();
                for (String k : StreamChat.optionKeys()) if (k.startsWith(rem)) b.suggest(k);
                return b.buildFuture();
            })
            .executes(c -> { Actions.chatSet(StringArgumentType.getString(c, "option"), ""); return 1; })
            .then(argument("value", StringArgumentType.greedyString()).executes(c -> {
                Actions.chatSet(StringArgumentType.getString(c, "option"), StringArgumentType.getString(c, "value"));
                return 1;
            }))));
        streamChat.then(literal("ignore")
            .then(literal("list").executes(c -> { Actions.chatIgnoreList(); return 1; }))
            .then(literal("clear").executes(c -> { Actions.chatIgnoreClear(); return 1; }))
            .then(literal("add").then(argument("name", StringArgumentType.word()).executes(c -> {
                Actions.chatIgnore(true, StringArgumentType.getString(c, "name"));
                return 1;
            })))
            .then(literal("remove").then(argument("name", StringArgumentType.word()).executes(c -> {
                Actions.chatIgnore(false, StringArgumentType.getString(c, "name"));
                return 1;
            }))));
        streamChat.then(literal("highlight")
            .then(literal("list").executes(c -> { Actions.chatHighlightList(); return 1; }))
            .then(literal("clear").executes(c -> { Actions.chatHighlightClear(); return 1; }))
            .then(literal("add").then(argument("word", StringArgumentType.greedyString()).executes(c -> {
                Actions.chatHighlight(true, StringArgumentType.getString(c, "word"));
                return 1;
            })))
            .then(literal("remove").then(argument("word", StringArgumentType.greedyString()).executes(c -> {
                Actions.chatHighlight(false, StringArgumentType.getString(c, "word"));
                return 1;
            }))));
        streamChat.then(literal("off").executes(c -> { Actions.chatOff(); return 1; }));
        streamChat.then(literal("clear").executes(c -> { Actions.chatClear(); return 1; }));
        streamChat.then(literal("commands").executes(c -> { Actions.chatToggleCommands(); return 1; }));
        root.then(streamChat);

        d.register(root);

        // Short aliases from the original auction mod.
        d.register(auctionStartNode("auctionstart", true));
        d.register(literal("auctionstop").executes(c -> { Actions.auctionStop(); return 1; }));
    }
}
