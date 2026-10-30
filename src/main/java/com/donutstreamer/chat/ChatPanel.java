package com.donutstreamer.chat;

import com.donutstreamer.Ui;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * On-screen Twitch / YouTube chat (a normal HUD widget: draggable, resizable, toggleable).
 * <p>
 * Messages are word-wrapped so nothing is cut off, drawn as soft bubbles with a platform square, optional time,
 * owner/mod/VIP/sub badges, the chatter's own colour and the message. Newest lines sit at the bottom (or the top,
 * your choice) and older ones fade. Every look option lives in /ds chat set ...
 * <p>
 * Cost: wrapping and trimming happen once per message (and again only when you change a setting or the width);
 * a frame just walks the newest few messages and draws strings and flat rectangles.
 */
public final class ChatPanel {
    private ChatPanel() {}

    public static final int DEFAULT_WIDTH = 260;
    public static final int ACCENT = 0xFF9146FF;

    private static final int LABEL = 0xFF8B949E;
    private static final int TWITCH = 0xFF9146FF;
    private static final int YOUTUBE = 0xFFFF3B3B;
    private static final int NAME_MAX = 90;
    private static final int[] BADGE_BITS = {ChatMessage.OWNER, ChatMessage.MOD, ChatMessage.VIP, ChatMessage.SUB, ChatMessage.VERIFIED};

    private static final ChatMessage[] VIEW = new ChatMessage[64];
    private static final ChatMessage[] SEL = new ChatMessage[64];
    private static String hint = "";
    private static String hintFor = null;
    private static int twitchChipW = -1, youtubeChipW = -1;

    public static int width(TextRenderer tr) {
        return StreamChat.cfg().width;
    }

    private static int rowHeight() {
        return 9 + StreamChat.cfg().spacing;
    }

    private static int headerHeight() {
        return StreamChat.cfg().header ? 16 : 4;
    }

    public static int height() {
        return headerHeight() + StreamChat.cfg().rows * rowHeight() + 6;
    }

    private static String fit(TextRenderer tr, String s, int maxWidth) {
        if (tr.getWidth(s) <= maxWidth) return s;
        return tr.trimToWidth(s, Math.max(0, maxWidth - tr.getWidth("..."))) + "...";
    }

    // ---------------- layout (once per message / setting change) ----------------

    private static int badgeColor(ChatMessage m, int bit) {
        switch (bit) {
            case ChatMessage.OWNER: return 0xFFE91916;
            case ChatMessage.MOD: return 0xFF00AD03;
            case ChatMessage.VIP: return 0xFFE005B9;
            case ChatMessage.SUB: return m.platform == ChatMessage.TWITCH ? 0xFF9B6BFF : 0xFF2BA640;
            default: return 0xFF8B949E;
        }
    }

    private static void layout(TextRenderer tr, ChatMessage m, StreamChat.Cfg c, int w, int rev) {
        if (m.laidOutWidth == w && m.laidOutRev == rev) return;
        int x = 8;
        if (c.platformMarker) x += 7;
        if (c.timestamps) {
            LocalTime t = LocalTime.ofInstant(Instant.ofEpochMilli(m.time), ZoneId.systemDefault());
            m.stamp = String.format("%02d:%02d", t.getHour(), t.getMinute());
            m.stampWidth = tr.getWidth(m.stamp);
            x += m.stampWidth + 4;
        } else {
            m.stamp = "";
            m.stampWidth = 0;
        }
        if (c.badges) x += m.badgeCount() * 7;
        m.nameX = x;
        m.nameShown = fit(tr, m.name, NAME_MAX);
        m.nameWidth = tr.getWidth(m.nameShown);
        m.textX = x + m.nameWidth + tr.getWidth(": ");
        int right = w - 8;
        if (c.wrap) {
            m.lines = wrap(tr, m.text, right - m.textX, right - m.nameX);
        } else {
            m.lines = new String[]{fit(tr, m.text, right - m.textX)};
        }
        m.laidOutWidth = w;
        m.laidOutRev = rev;
    }

    /** Greedy word wrap; words longer than a line are split. First line is narrower (after the name). */
    private static String[] wrap(TextRenderer tr, String text, int first, int cont) {
        List<String> out = new ArrayList<>(4);
        StringBuilder line = new StringBuilder();
        int avail = first;
        if (first < 40) {          // very long name: start the message on its own line
            out.add("");
            avail = cont;
        }
        for (String raw : text.split(" ")) {
            if (raw.isEmpty()) continue;
            String word = raw;
            String trial = line.length() == 0 ? word : line + " " + word;
            if (tr.getWidth(trial) <= avail) {
                line.setLength(0);
                line.append(trial);
                continue;
            }
            if (line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                avail = cont;
            }
            while (tr.getWidth(word) > avail && !word.isEmpty()) {
                String part = tr.trimToWidth(word, avail);
                if (part.isEmpty()) part = word.substring(0, 1);
                out.add(part);
                word = word.substring(part.length());
                avail = cont;
            }
            line.append(word);
        }
        if (line.length() > 0 || out.isEmpty()) out.add(line.toString());
        return out.toArray(new String[0]);
    }

    // ---------------- drawing ----------------

    private static void str(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color, boolean shadow) {
        if (s.isEmpty()) return;
        if (shadow) ctx.drawTextWithShadow(tr, s, x, y, color);
        else ctx.drawText(tr, s, x, y, color, false);
    }

    private static void chip(DrawContext ctx, TextRenderer tr, int rightEdge, String label, int color, boolean ok, int[] widthCache, int slot) {
        if (widthCache[slot] < 0) widthCache[slot] = tr.getWidth(label) + 8;
        int cw = widthCache[slot];
        int x = rightEdge - cw;
        Ui.roundRect(ctx, x, 2, cw, 11, Ui.withAlpha(color, ok ? 0x66 : 0x28));
        ctx.drawTextWithShadow(tr, label, x + 4, 3, ok ? 0xFFFFFFFF : LABEL);
    }

    private static final int[] CHIP_W = {-1, -1};

    /** Draws inside the widget box (origin = top-left; the card background is already drawn). */
    public static void draw(DrawContext ctx, TextRenderer tr, int w, int h) {
        StreamChat.Cfg c = StreamChat.cfg();
        int rev = StreamChat.layoutRev();
        long now = System.currentTimeMillis();
        int rowH = rowHeight();
        int top = headerHeight();

        if (c.header) {
            ctx.fill(2, 1, w - 2, 14, 0x12FFFFFF);
            int pulse = Ui.animated() ? 150 + (int) (105 * (0.5 + 0.5 * Math.sin(Ui.animTime() / 420.0))) : 220;
            boolean live = StreamChat.twitchOn() || StreamChat.youtubeOn();
            ctx.fill(8, 5, 12, 9, Ui.withAlpha(live ? 0xFF55FF7F : 0xFF6E7681, live ? pulse : 0xFF));
            ctx.drawTextWithShadow(tr, "CHAT", 16, 3, 0xFFB794F6);
            int edge = w - 6;
            if (StreamChat.youtubeOn()) {
                chip(ctx, tr, edge, "YouTube", YOUTUBE, StreamChat.youtubeConnected(), CHIP_W, 1);
                edge -= CHIP_W[1] + 3;
            }
            if (StreamChat.twitchOn()) {
                chip(ctx, tr, edge, "Twitch", TWITCH, StreamChat.twitchConnected(), CHIP_W, 0);
            }
            ctx.fill(6, 14, w - 6, 15, 0x18FFFFFF);
        }

        int n = StreamChat.newest(VIEW);
        long expireMs = c.expireSeconds * 1000L;
        int budget = h - top - 4;
        int gap = c.bubbles ? 3 : 1;

        // pick the newest messages that fit (SEL is newest first)
        int sel = 0, used = 0;
        for (int i = n - 1; i >= 0; i--) {
            ChatMessage m = VIEW[i];
            VIEW[i] = null;
            if (expireMs > 0 && now - m.time > expireMs) continue;
            if (used >= budget) continue;
            layout(tr, m, c, w, rev);
            int need = m.lines.length * rowH + gap;
            if (used + need > budget && sel > 0) {
                used = budget;      // full: older messages don't fit
                continue;
            }
            SEL[sel++] = m;
            used += need;
        }

        if (sel == 0) {
            String status = StreamChat.configured() ? StreamChat.statusLine() : "Set up: /ds chat twitch <channel>";
            if (!status.equals(hintFor)) {
                hintFor = status;
                hint = fit(tr, status, w - 16);
            }
            str(ctx, tr, hint, 8, top + 4, LABEL, true);
            if (!StreamChat.configured()) str(ctx, tr, "or /ds chat youtube <link>", 8, top + 16, LABEL, true);
            return;
        }

        int y = c.newestAtTop ? top + 2 : h - 4 - Math.min(used, budget) + 1;
        long fadeMs = c.fadeSeconds * 1000L;
        for (int k = 0; k < sel; k++) {
            ChatMessage m = c.newestAtTop ? SEL[k] : SEL[sel - 1 - k];
            int lines = m.lines.length;
            int maxLines = Math.max(1, (budget - gap) / rowH);
            if (lines > maxLines) lines = maxLines;     // a single message taller than the whole panel is clipped
            long age = now - m.time;
            int alpha = fadeMs == 0 ? 0xFF : (age > fadeMs * 3 / 2 ? 0x66 : (age > fadeMs ? 0xAA : 0xFF));
            float af = alpha / 255f;

            int bh = lines * rowH - c.spacing + 2;
            if (c.bubbles) {
                Ui.roundRect(ctx, 4, y - 1, w - 8, bh, Ui.scaleAlpha(m.highlight ? 0x44FFC107 : 0x22FFFFFF, af));
            }
            if (m.highlight) ctx.fill(4, y - 1, 6, y - 1 + bh, Ui.scaleAlpha(0xFFFFC107, af));

            int x = 8;
            if (c.platformMarker) {
                ctx.fill(x, y + 2, x + 4, y + 6, Ui.withAlpha(m.platform == ChatMessage.YOUTUBE ? YOUTUBE : TWITCH, alpha));
                x += 7;
            }
            if (c.timestamps) {
                str(ctx, tr, m.stamp, x, y, Ui.withAlpha(LABEL, alpha), c.textShadow);
                x += m.stampWidth + 4;
            }
            if (c.badges) {
                for (int bit : BADGE_BITS) {
                    if ((m.badges & bit) == 0) continue;
                    ctx.fill(x, y + 1, x + 5, y + 6, Ui.withAlpha(badgeColor(m, bit), alpha));
                    ctx.fill(x, y + 1, x + 5, y + 2, Ui.withAlpha(0x55FFFFFF, alpha / 2));
                    x += 7;
                }
            }
            int nameColor = c.nameColors ? m.color : c.textColor;
            str(ctx, tr, m.nameShown, m.nameX, y, Ui.withAlpha(nameColor, alpha), c.textShadow);
            str(ctx, tr, ": ", m.nameX + m.nameWidth, y, Ui.withAlpha(LABEL, alpha), c.textShadow);

            int textColor = Ui.withAlpha(c.textColor, alpha);
            for (int li = 0; li < lines; li++) {
                int lx = li == 0 ? m.textX : m.nameX;
                str(ctx, tr, m.lines[li], lx, y + li * rowH, textColor, c.textShadow);
            }
            y += lines * rowH + gap;
        }
        for (int k = 0; k < sel; k++) SEL[k] = null;
    }
}
