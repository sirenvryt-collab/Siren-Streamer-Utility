package com.donutstreamer;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Editing UI that lives on the chat screen: click an element to select it, drag it to move it, drag the little
 * square on its bottom-right corner to resize just that element, and use the panel that appears for size,
 * background, colour, on/off and reset. The "HUD editor" button (top right) holds the global switches.
 * Purely local; it only changes where, how big and how a number is drawn.
 */
public final class HudEditor {
    private HudEditor() {}

    /** checked == null means a plain action row (no checkbox). */
    private record Row(String label, Supplier<Boolean> checked, Runnable action) {}

    private static final List<Row> ROWS = buildRows();

    private static Screen registeredFor = null;
    private static boolean panelOpen = false;
    private static boolean prevDown = false;
    private static Widgets.W dragging = null;
    private static Widgets.W selected = null;
    private static boolean resizing = false;
    private static final int[] SWATCHES = {0xFFFFD700, 0xFF55FF55, 0xFFFF5555, 0xFF55FFFF, 0xFF5599FF, 0xFFB07CFF, 0xFFFF7EDB, 0xFFFFA040};
    private static final int INSP_W = 214, INSP_H = 80;
    private static int dragDX, dragDY;
    private static int labelW = -1;
    private static final int WHITE_TICK = 0xFFFFFFFF;

    private static Session session() {
        return DonutStreamerClient.session;
    }

    private static List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Show HUD", () -> session().hudVisible, () -> session().hudVisible = !session().hudVisible));
        rows.add(new Row("Turn ALL elements on", null, () -> {
            for (Widgets.W w : Widgets.ALL) session().cfg(w).on = true;
        }));
        rows.add(new Row("Turn ALL elements off", null, () -> {
            for (Widgets.W w : Widgets.ALL) session().cfg(w).on = false;
        }));
        for (Widgets.W w : Widgets.ALL) {
            rows.add(new Row(w.label, () -> session().cfg(w).on, () -> {
                Session.WidgetCfg c = session().cfg(w);
                c.on = !c.on;
            }));
        }
        rows.add(new Row("Tag doubled payments", () -> session().doubleTag, () -> session().doubleTag = !session().doubleTag));
        rows.add(new Row("Live as % of balance", () -> session().percentMode, () -> session().percentMode = !session().percentMode));
        rows.add(new Row("Count sells in profit", () -> session().includeSells, () -> {
            session().includeSells = !session().includeSells;
            session().recompute();
        }));
        rows.add(new Row("Count auction bids in tracker", () -> session().trackAuctionPayments,
                () -> session().trackAuctionPayments = !session().trackAuctionPayments));
        rows.add(new Row("Auctions on any server (testing)", () -> session().auctionAnyServer,
                () -> session().auctionAnyServer = !session().auctionAnyServer));
        rows.add(new Row("Stream mode (hide F3 coords)", () -> session().streamMode, () -> session().streamMode = !session().streamMode));
        rows.add(new Row("Hide coords in chat (stream mode)", () -> session().streamHideChatCoords,
                () -> session().streamHideChatCoords = !session().streamHideChatCoords));
        rows.add(new Row("Own nametag in F5", () -> session().selfNametag, () -> session().selfNametag = !session().selfNametag));
        rows.add(new Row("Sirens watermark", () -> session().watermark, () -> session().watermark = !session().watermark));
        rows.add(new Row("Animations (pulses, equalizer)", () -> session().animations, () -> session().animations = !session().animations));
        rows.add(new Row("Glowing card edges", () -> session().cardGlow, () -> session().cardGlow = !session().cardGlow));
        rows.add(new Row("Card shadows", () -> session().cardShadow, () -> session().cardShadow = !session().cardShadow));
        rows.add(new Row("Full amounts (45,000,000)", () -> session().moneyFull, () -> session().moneyFull = !session().moneyFull));
        rows.add(new Row("Sounds", () -> session().sound, () -> session().sound = !session().sound));
        rows.add(new Row("Show player names", () -> session().showNames, () -> session().showNames = !session().showNames));
        rows.add(new Row("Reset HUD layout", null, () -> {
            session().widgets.clear();
            session().scale = 1.0f;
            selected = null;
        }));
        return List.copyOf(rows);
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) return;
            prevDown = leftDown(client);
            dragging = null;
            resizing = false;
            if (registeredFor == screen) return;   // AFTER_INIT also fires on window resize
            registeredFor = screen;
            ScreenEvents.afterRender(screen).register((scr, ctx, mx, my, delta) -> render(client, ctx, mx, my));
            ScreenEvents.remove(screen).register(scr -> {
                if (dragging != null || resizing) {
                    dragging = null;
                    resizing = false;
                    session().save();
                }
                if (registeredFor == scr) registeredFor = null;
            });
        });
    }

    private static boolean leftDown(MinecraftClient mc) {
        return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void outline(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static void render(MinecraftClient mc, DrawContext ctx, int mx, int my) {
        Session s = session();
        TextRenderer tr = mc.textRenderer;
        int sw = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();

        boolean down = leftDown(mc);
        boolean pressed = down && !prevDown;
        boolean released = !down && prevDown;
        prevDown = down;

        // ---- widgets: draw ghosts for ones with no data yet, then outlines ----
        Widgets.W hovered = null;
        if (s.hudVisible) {
            for (Widgets.W w : Widgets.ALL) {
                Session.WidgetCfg c = s.cfg(w);
                if (!c.on) continue;
                if (dragging != w && !(resizing && selected == w)) HudRenderer.fit(c, w, s, mc, tr);
                if (!Widgets.available(w, s)) HudRenderer.drawAt(ctx, mc, s, w, c);
                float sc = s.scaleOf(w);
                int bw = Math.round(Widgets.width(w, s, tr) * sc);
                int bh = Math.round(Widgets.height(w) * sc);
                boolean over = inside(mx, my, c.x, c.y, bw, bh);
                if (over) hovered = w;
                boolean sel = selected == w;
                outline(ctx, c.x - 1, c.y - 1, bw + 2, bh + 2,
                        sel ? 0xFF55FFFF : (dragging == w ? 0xFFFFFF55 : (over ? 0xFFFFFFFF : 0x88FFFFFF)));
                if (sel) {
                    int hx = c.x + bw - 4, hy = c.y + bh - 4;
                    boolean overHandle = inside(mx, my, hx - 2, hy - 2, 10, 10);
                    ctx.fill(hx, hy, hx + 7, hy + 7, resizing || overHandle ? 0xFFFFFFFF : 0xFF55FFFF);
                    ctx.fill(hx + 1, hy + 1, hx + 6, hy + 6, 0xFF0D1117);
                    ctx.fill(hx + 2, hy + 2, hx + 5, hy + 5, resizing || overHandle ? 0xFFFFFFFF : 0xFF55FFFF);
                }
            }
        }

        // ---- editor button ----
        String btn = "HUD editor " + (panelOpen ? "\u25B2" : "\u25BC");
        int btnW = tr.getWidth(btn) + 8, btnH = 12;
        int btnX = sw - btnW - 4, btnY = 4;
        boolean overBtn = inside(mx, my, btnX, btnY, btnW, btnH);

        // ---- panel geometry (rows flow into columns so everything fits on screen) ----
        int rowH = 11;
        int panelY = btnY + btnH + 2;
        int colW = 0, perCol = 1, panelW = 0, panelH = 0, panelX = 0;
        boolean overPanel = false;
        if (panelOpen) {
            if (labelW < 0) {
                for (Row r : ROWS) labelW = Math.max(labelW, tr.getWidth(r.label()) + 12);
            }
            colW = labelW + 10;
            perCol = Math.max(1, (screenH - panelY - 12) / rowH);
            int cols = (ROWS.size() + perCol - 1) / perCol;
            panelW = cols * colW;
            panelH = Math.min(ROWS.size(), perCol) * rowH + 6;
            panelX = sw - panelW - 4;
            overPanel = inside(mx, my, panelX, panelY, panelW, panelH);
        }

        // ---- inspector geometry (top centre, nudged left of the global panel) ----
        int inspX = Math.max(4, Math.min(sw / 2 - INSP_W / 2, sw - INSP_W - 4));
        if (panelOpen) inspX = Math.max(4, Math.min(inspX, panelX - INSP_W - 6));
        int inspY = 4;
        boolean inspVisible = selected != null;
        boolean overInsp = inspVisible && inside(mx, my, inspX, inspY, INSP_W, INSP_H);

        boolean consumed = false;
        if (pressed) {
            if (overBtn) {
                panelOpen = !panelOpen;
                consumed = true;
            } else if (overPanel) {
                int col = (mx - panelX) / colW;
                int rowIdx = (my - panelY - 3) / rowH;
                int idx = col * perCol + rowIdx;
                if (rowIdx >= 0 && rowIdx < perCol && idx >= 0 && idx < ROWS.size()) {
                    ROWS.get(idx).action().run();
                    s.save();
                }
                consumed = true;
            } else if (overInsp) {
                consumed = true;   // the controls below act on this click
            }
        }

        // ---- resize handle, then dragging ----
        if (pressed && !consumed && selected != null && s.cfg(selected).on) {
            Session.WidgetCfg c = s.cfg(selected);
            float sc = s.scaleOf(selected);
            int bw = Math.round(Widgets.width(selected, s, tr) * sc);
            int bh = Math.round(Widgets.height(selected) * sc);
            if (inside(mx, my, c.x + bw - 6, c.y + bh - 6, 10, 10)) {
                resizing = true;
                consumed = true;
            }
        }
        if (pressed && !consumed) {
            if (hovered != null) {
                Session.WidgetCfg c = s.cfg(hovered);
                selected = hovered;
                dragging = hovered;
                dragDX = mx - c.x;
                dragDY = my - c.y;
            } else {
                selected = null;
            }
        }
        if (resizing && selected != null) {
            if (down) {
                Session.WidgetCfg c = s.cfg(selected);
                float baseW = Math.max(8, Widgets.width(selected, s, tr));
                float total = (mx - c.x) / baseW;
                float own = Math.round((total / s.scale) * 20f) / 20f;      // snaps to 0.05
                c.scale = Math.max(0.4f, Math.min(4.0f, own));
            }
            if (released) {
                resizing = false;
                s.save();
            }
        } else if (dragging != null) {
            if (down) {
                Session.WidgetCfg c = s.cfg(dragging);
                float sc = s.scaleOf(dragging);
                int bw = Math.round(Widgets.width(dragging, s, tr) * sc);
                int bh = Math.round(Widgets.height(dragging) * sc);
                int maxX = Math.max(0, sw - bw);
                int maxY = Math.max(0, screenH - bh);
                c.x = Math.max(0, Math.min(maxX, mx - dragDX));
                c.y = Math.max(0, Math.min(maxY, my - dragDY));
            }
            if (released) {
                dragging = null;
                s.save();
            }
        }

        // ---- draw button + panel on top ----
        Ui.box(ctx, btnX, btnY, btnW, btnH, overBtn ? 0xF0303844 : 0xF0141A22, overBtn ? 0xFFFFE066 : Widgets.GOLD);
        ctx.fill(btnX + 2, btnY + 1, btnX + btnW - 2, btnY + 2, 0x18FFFFFF);
        ctx.drawTextWithShadow(tr, btn, btnX + 4, btnY + 2, Widgets.GOLD);

        if (panelOpen) {
            ctx.fill(panelX + 2, panelY + panelH, panelX + panelW + 1, panelY + panelH + 2, 0x55000000);   // shadow
            ctx.fill(panelX + panelW, panelY + 2, panelX + panelW + 2, panelY + panelH + 2, 0x55000000);
            Ui.box(ctx, panelX, panelY, panelW, panelH, 0xF20D1117, 0xFF30363D);
            ctx.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 2, 0x20FFFFFF);
            for (int i = 0; i < ROWS.size(); i++) {
                Row r = ROWS.get(i);
                int rx = panelX + (i / perCol) * colW;
                int ry = panelY + 3 + (i % perCol) * rowH;
                if (inside(mx, my, rx, ry, colW, rowH)) Ui.roundRect(ctx, rx + 1, ry, colW - 2, rowH, 0x40FFFFFF);
                boolean plain = r.checked() == null;
                boolean on = !plain && Boolean.TRUE.equals(r.checked().get());
                int textX = rx + 5;
                if (!plain) {
                    // little switch: green fill with a white tick when on, hollow when off
                    Ui.roundRect(ctx, rx + 4, ry + 2, 7, 7, on ? 0xFF2EA043 : 0xFF30363D);
                    if (on) {
                        ctx.fill(rx + 5, ry + 5, rx + 6, ry + 6, WHITE_TICK);
                        ctx.fill(rx + 6, ry + 6, rx + 7, ry + 7, WHITE_TICK);
                        ctx.fill(rx + 7, ry + 5, rx + 8, ry + 6, WHITE_TICK);
                        ctx.fill(rx + 8, ry + 4, rx + 9, ry + 5, WHITE_TICK);
                    } else {
                        ctx.fill(rx + 5, ry + 3, rx + 10, ry + 4, 0x18FFFFFF);
                    }
                    textX = rx + 15;
                }
                int color = plain ? Widgets.GOLD : (on ? Widgets.WHITE : Widgets.GRAY);
                ctx.drawTextWithShadow(tr, r.label(), textX, ry + 1, color);
            }
        }

        if (inspVisible) drawInspector(ctx, tr, s, selected, inspX, inspY, mx, my, pressed && overInsp);
    }

    // ================= inspector (select an element to see it) =================

    /** Small rounded button; returns true when clicked this frame. */
    private static boolean button(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h, String label,
                                  int mx, int my, boolean click, int textColor) {
        boolean over = inside(mx, my, x, y, w, h);
        Ui.roundRect(ctx, x, y, w, h, over ? 0xFF3A4352 : 0xFF232A35);
        ctx.fill(x + 1, y, x + w - 1, y + 1, 0x20FFFFFF);
        ctx.drawTextWithShadow(tr, label, x + (w - tr.getWidth(label)) / 2, y + (h - 8) / 2, textColor);
        return click && over;
    }

    private static void drawInspector(DrawContext ctx, TextRenderer tr, Session s, Widgets.W w, int x, int y,
                                      int mx, int my, boolean click) {
        Session.WidgetCfg c = s.cfg(w);
        ctx.fill(x + 2, y + INSP_H, x + INSP_W + 1, y + INSP_H + 2, 0x55000000);
        ctx.fill(x + INSP_W, y + 2, x + INSP_W + 2, y + INSP_H + 2, 0x55000000);
        Ui.box(ctx, x, y, INSP_W, INSP_H, 0xF40D1117, 0xFF55FFFF);
        ctx.fill(x + 1, y + 1, x + INSP_W - 1, y + 2, 0x24FFFFFF);

        // title + on/off
        ctx.drawTextWithShadow(tr, w.label, x + 6, y + 5, Widgets.GOLD);
        if (button(ctx, tr, x + INSP_W - 34, y + 3, 28, 11, c.on ? "ON" : "OFF", mx, my, click,
                c.on ? Widgets.GREEN : Widgets.GRAY)) {
            c.on = !c.on;
            s.save();
        }

        // size row
        int ry = y + 19;
        ctx.drawTextWithShadow(tr, "Size", x + 6, ry + 2, Widgets.GRAY);
        if (button(ctx, tr, x + 56, ry, 14, 12, "-", mx, my, click, Widgets.WHITE)) {
            c.scale = Math.max(0.4f, Math.round((c.scale - 0.1f) * 20f) / 20f);
            s.save();
        }
        String sizeText = String.format(java.util.Locale.ROOT, "%.2fx", c.scale);
        ctx.drawTextWithShadow(tr, sizeText, x + 74 + (40 - tr.getWidth(sizeText)) / 2, ry + 2, Widgets.WHITE);
        if (button(ctx, tr, x + 118, ry, 14, 12, "+", mx, my, click, Widgets.WHITE)) {
            c.scale = Math.min(4.0f, Math.round((c.scale + 0.1f) * 20f) / 20f);
            s.save();
        }
        if (button(ctx, tr, x + 138, ry, 30, 12, "1x", mx, my, click, Widgets.AQUA)) {
            c.scale = 1.0f;
            s.save();
        }

        // background row
        ry = y + 34;
        ctx.drawTextWithShadow(tr, "Back", x + 6, ry + 2, Widgets.GRAY);
        if (button(ctx, tr, x + 56, ry, 14, 12, "-", mx, my, click, Widgets.WHITE)) {
            c.bg = Math.max(0.1f, Math.round((c.bg - 0.1f) * 10f) / 10f);
            s.save();
        }
        String bgText = Math.round(c.bg * 100) + "%";
        ctx.drawTextWithShadow(tr, bgText, x + 74 + (40 - tr.getWidth(bgText)) / 2, ry + 2, Widgets.WHITE);
        if (button(ctx, tr, x + 118, ry, 14, 12, "+", mx, my, click, Widgets.WHITE)) {
            c.bg = Math.min(1.0f, Math.round((c.bg + 0.1f) * 10f) / 10f);
            s.save();
        }
        if (button(ctx, tr, x + 138, ry, 30, 12, "100%", mx, my, click, Widgets.AQUA)) {
            c.bg = 1.0f;
            s.save();
        }

        // colour row: "A" = automatic, then swatches
        ry = y + 49;
        ctx.drawTextWithShadow(tr, "Color", x + 6, ry + 2, Widgets.GRAY);
        int sx = x + 44;
        boolean auto = c.accent == 0;
        Ui.roundRect(ctx, sx, ry, 12, 12, auto ? 0xFFFFFFFF : 0xFF3A4352);
        Ui.roundRect(ctx, sx + 1, ry + 1, 10, 10, 0xFF232A35);
        ctx.drawTextWithShadow(tr, "A", sx + 3, ry + 2, auto ? Widgets.WHITE : Widgets.GRAY);
        if (inside(mx, my, sx, ry, 12, 12) && click) {
            c.accent = 0;
            s.save();
        }
        for (int i = 0; i < SWATCHES.length; i++) {
            int cx = sx + 15 + i * 14;
            boolean on = c.accent == SWATCHES[i];
            Ui.roundRect(ctx, cx, ry, 12, 12, on ? 0xFFFFFFFF : 0xFF3A4352);
            Ui.roundRect(ctx, cx + 1, ry + 1, 10, 10, SWATCHES[i]);
            if (inside(mx, my, cx, ry, 12, 12) && click) {
                c.accent = SWATCHES[i];
                s.save();
            }
        }

        // buttons
        ry = y + 65;
        if (button(ctx, tr, x + 6, ry, 80, 12, "Reset element", mx, my, click, Widgets.GOLD)) {
            Widgets.W target = w;
            int[] p = Widgets.defaultPos(target);
            c.x = p[0];
            c.y = p[1];
            c.scale = 1.0f;
            c.bg = 1.0f;
            c.accent = 0;
            s.save();
        }
        if (button(ctx, tr, x + 92, ry, 44, 12, "Deselect", mx, my, click, Widgets.GRAY)) {
            selected = null;
        }
        ctx.drawTextWithShadow(tr, "drag the corner to resize", x + 142, ry + 2, 0xFF6E7681);
    }
}
