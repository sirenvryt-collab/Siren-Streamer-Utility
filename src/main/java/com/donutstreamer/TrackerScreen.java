package com.donutstreamer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only tracker window (open with /ds): live stats, charts and a per-player table with rakeback. */
public final class TrackerScreen extends Screen {
    private static final int PANEL = 0xF00D1117;
    private static final int CARD = 0xFF161B22;
    private static final int INSET = 0xFF0A0E13;
    private static final int BORDER = 0xFF30363D;

    public TrackerScreen() {
        super(Text.literal("Donut Streamer Tracker"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static void outline(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void card(DrawContext ctx, int x, int y, int w, String label, String value, int color) {
        ctx.fill(x + 1, y + 26, x + w, y + 27, 0x55000000);                      // soft shadow
        Ui.roundRect(ctx, x, y, w, 26, CARD);
        Ui.vGradient(ctx, x + 1, y + 2, w - 2, 23, 0x0CFFFFFF, 0x00FFFFFF);       // glassy top
        ctx.fill(x + 1, y, x + w - 1, y + 2, color);                              // accent strip
        ctx.fill(x + 2, y + 2, x + w - 2, y + 3, Ui.withAlpha(color, 0x30));      // glow under the strip
        ctx.drawTextWithShadow(this.textRenderer, label, x + 5, y + 6, Ui.LABEL);
        ctx.drawTextWithShadow(this.textRenderer, value, x + 5, y + 16, color);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        Session s = DonutStreamerClient.session;

        int pw = Math.min(this.width - 20, 400);
        int ph = Math.min(this.height - 20, 290);
        int px = (this.width - pw) / 2;
        int py = (this.height - ph) / 2;
        int x = px + 8;
        int iw = pw - 16;

        ctx.fill(px + 2, py + ph, px + pw + 1, py + ph + 2, 0x66000000);   // soft shadow
        ctx.fill(px + pw, py + 2, px + pw + 2, py + ph + 2, 0x66000000);
        Ui.box(ctx, px, py, pw, ph, PANEL, BORDER);

        // ---- title bar ----
        Ui.vGradient(ctx, px + 1, py + 1, pw - 2, 18, 0xFF1E2530, 0xFF141A22);   // title bar
        ctx.fill(px + 1, py + 19, px + pw - 1, py + 20, Widgets.GOLD);
        ctx.fill(px + 1, py + 20, px + pw - 1, py + 21, 0x30FFD700);              // glow under the gold line
        int dot = 90 + (int) (165 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 350.0)));
        ctx.fill(x, py + 7, x + 4, py + 11, Ui.withAlpha(Widgets.RED, dot));       // live dot
        ctx.drawTextWithShadow(this.textRenderer, "Donut Streamer", x + 9, py + 6, Widgets.GOLD);
        int netColor = s.net > 0 ? Widgets.GREEN : (s.net < 0 ? Widgets.RED : Widgets.WHITE);
        String liveText = "LIVE  " + Money.signed(s.net);
        ctx.drawTextWithShadow(this.textRenderer, liveText, px + pw - 8 - this.textRenderer.getWidth(liveText),
                py + 6, netColor);

        // ---- stat cards ----
        int y = py + 26;
        int gap = 4;
        int cw = (iw - gap * 3) / 4;
        String liveValue = Money.signed(s.net);
        if (s.startBalance > 0) {
            liveValue += String.format(Locale.ROOT, " (%+.1f%%)", 100.0 * s.net / s.startBalance);
        }
        card(ctx, x, y, cw, "LIVE", liveValue, netColor);
        card(ctx, x + (cw + gap), y, cw, "RECORD", s.wins + "W - " + s.losses + "L", Widgets.WHITE);
        card(ctx, x + (cw + gap) * 2, y, cw, "WIN RATE", s.winRate() + "%",
                s.wins >= s.losses ? Widgets.GREEN : Widgets.RED);
        card(ctx, x + (cw + gap) * 3, y, cw, "STREAK  " + s.time(), s.streakText(),
                s.streak > 0 ? Widgets.GREEN : (s.streak < 0 ? Widgets.RED : Widgets.GRAY));
        y += 32;

        // ---- charts ----
        int lw = iw * 62 / 100;
        int rw = iw - lw - gap;
        int rx = x + lw + gap;
        ctx.drawTextWithShadow(this.textRenderer, "Profit over time", x, y, Widgets.GRAY);
        ctx.drawTextWithShadow(this.textRenderer, "Last 30 results", rx, y, Widgets.GRAY);
        y += 11;
        int chartH = 52;
        Ui.box(ctx, x, y, lw, chartH, INSET, BORDER);
        Widgets.lineChart(ctx, x + 3, y + 3, lw - 6, chartH - 6, s.cumulative());
        Ui.box(ctx, rx, y, rw, chartH, INSET, BORDER);
        Widgets.resultBars(ctx, rx + 3, y + 3, rw - 6, chartH - 18, s, 30);
        Widgets.splitBar(ctx, rx + 3, y + chartH - 10, rw - 6, 6, s.wins, s.losses);
        y += chartH + 6;

        // ---- stat chips (wrap to next line when full) ----
        List<String[]> chips = new ArrayList<>();
        List<Integer> chipColors = new ArrayList<>();
        addChip(chips, chipColors, "Received", Money.format(s.received), Widgets.GREEN);
        addChip(chips, chipColors, "Sent", Money.format(s.sent), Widgets.RED);
        addChip(chips, chipColors, "Sold", Money.format(s.sold) + (s.includeSells ? "" : " (not counted)"), Widgets.AQUA);
        if (s.spent > 0) addChip(chips, chipColors, "Bought", Money.format(s.spent), Widgets.RED);
        addChip(chips, chipColors, "Gambles", String.valueOf(s.total()), Widgets.WHITE);
        addChip(chips, chipColors, "Avg win", Money.format(s.received / Math.max(1, s.wins)), Widgets.GREEN);
        addChip(chips, chipColors, "Avg loss", Money.format(s.sent / Math.max(1, s.losses)), Widgets.RED);
        addChip(chips, chipColors, "Peak", Money.signed(s.peak), Widgets.GREEN);
        addChip(chips, chipColors, "Low", Money.signed(s.low), Widgets.RED);
        addChip(chips, chipColors, "Best", "W" + s.bestWinStreak, Widgets.GREEN);
        addChip(chips, chipColors, "Worst", "L" + s.worstLossStreak, Widgets.RED);
        if (s.elapsedMs >= 60_000 && s.total() > 0) {
            addChip(chips, chipColors, "Per hour",
                    Money.signed(Math.round(s.net * 3_600_000.0 / s.elapsedMs)), netColor);
        }
        int cx = x;
        for (int i = 0; i < chips.size(); i++) {
            String label = chips.get(i)[0] + " ";
            String value = chips.get(i)[1];
            int tw = this.textRenderer.getWidth(label + value);
            if (cx + tw > px + pw - 8 && cx > x) {
                cx = x;
                y += 11;
            }
            ctx.drawTextWithShadow(this.textRenderer, label, cx, y, Widgets.GRAY);
            ctx.drawTextWithShadow(this.textRenderer, value, cx + this.textRenderer.getWidth(label), y, chipColors.get(i));
            cx += tw + 10;
        }
        y += 16;

        // ---- player table ----
        boolean rake = s.rakebackPct > 0;
        int cName = x + 4;
        int cIn = x + iw * 28 / 100;
        int cOut = x + iw * 46 / 100;
        int cNet = x + iw * 64 / 100;
        int cRake = x + iw * 82 / 100;
        Ui.roundRect(ctx, x, y - 2, iw, 12, CARD);
        ctx.fill(x + 1, y + 10, x + iw - 1, y + 11, 0x66FFD700);
        ctx.drawTextWithShadow(this.textRenderer, "Player", cName, y, Widgets.GOLD);
        ctx.drawTextWithShadow(this.textRenderer, "Paid you", cIn, y, Widgets.GOLD);
        ctx.drawTextWithShadow(this.textRenderer, "You paid", cOut, y, Widgets.GOLD);
        ctx.drawTextWithShadow(this.textRenderer, "Live", cNet, y, Widgets.GOLD);
        if (rake) ctx.drawTextWithShadow(this.textRenderer, "Owed", cRake, y, Widgets.GOLD);
        y += 13;

        List<Session.PlayerStat> players = s.playerStats();
        int maxRows = Math.max(0, (py + ph - 18 - y) / 10);
        if (players.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer, "No player payments yet.", cName, y, Widgets.GRAY);
        }
        for (int i = 0; i < players.size() && i < maxRows; i++) {
            Session.PlayerStat p = players.get(i);
            if (i % 2 == 0) Ui.roundRect(ctx, x, y - 1, iw, 10, 0x16FFFFFF);
            String name = s.namesVisible() ? p.name : "Player " + (i + 1);
            ctx.drawTextWithShadow(this.textRenderer, name, cName, y, Widgets.WHITE);
            ctx.drawTextWithShadow(this.textRenderer, Money.format(p.in), cIn, y, Widgets.GREEN);
            ctx.drawTextWithShadow(this.textRenderer, Money.format(p.out), cOut, y, Widgets.RED);
            ctx.drawTextWithShadow(this.textRenderer, Money.signed(p.net()), cNet,
                    y, p.net() >= 0 ? Widgets.GREEN : Widgets.RED);
            if (rake) ctx.drawTextWithShadow(this.textRenderer, Money.format(s.rakebackOwed(p)), cRake, y, Widgets.AQUA);
            y += 10;
        }

        ctx.drawTextWithShadow(this.textRenderer, "Esc to close  |  /ds help for commands",
                x, py + ph - 12, Widgets.GRAY);
    }

    private static void addChip(List<String[]> chips, List<Integer> colors, String label, String value, int color) {
        chips.add(new String[]{label, value});
        colors.add(color);
    }
}
