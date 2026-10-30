package com.donutstreamer.chat;

/** One line of stream chat. Layout fields are filled lazily on the render thread (text widths need the font). */
final class ChatMessage {
    static final int TWITCH = 0, YOUTUBE = 1;

    // badge bits
    static final int OWNER = 1, MOD = 2, VIP = 4, SUB = 8, VERIFIED = 16;

    final int platform;
    final String name;
    final String text;
    final int color;          // ARGB name colour
    final int badges;
    final boolean highlight;
    final long time;

    // render-thread cache (rebuilt when the width or any chat setting changes)
    String nameShown;
    int nameWidth;
    String stamp = "";
    int stampWidth;
    String[] lines = new String[0];
    int nameX;                // where the name starts on the first line (also where wrapped lines start)
    int textX;                // where the message text starts on the first line
    int laidOutWidth = -1;
    int laidOutRev = -1;

    ChatMessage(int platform, String name, String text, int color, int badges, boolean highlight) {
        this.platform = platform;
        this.name = name;
        this.text = text;
        this.color = color;
        this.badges = badges;
        this.highlight = highlight;
        this.time = System.currentTimeMillis();
    }

    int badgeCount() {
        return Integer.bitCount(badges & (OWNER | MOD | VIP | SUB | VERIFIED));
    }
}
