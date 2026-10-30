package com.donutstreamer;

import java.util.Locale;

/** Card looks (HUD background colours) and a small colour parser shared by the commands. */
public final class Theme {
    private Theme() {}

    public record Preset(String id, String label, int top, int bottom) {}

    public static final Preset[] PRESETS = {
            new Preset("default", "Default dark", 0xD0151B24, 0xD00A0E14),
            new Preset("midnight", "Midnight blue", 0xD0121832, 0xD0080A1C),
            new Preset("glass", "Glass (see-through)", 0x88202A36, 0x88141B24),
            new Preset("ember", "Ember", 0xD02A1812, 0xD0140A06),
            new Preset("forest", "Forest", 0xD0122219, 0xD0081410),
            new Preset("royal", "Royal purple", 0xD0201A44, 0xD00E0824),
            new Preset("mono", "Charcoal", 0xD0262626, 0xD0121212),
            new Preset("black", "Pure black", 0xE0000000, 0xE0000000),
    };

    private static Preset cached = PRESETS[0];

    public static Preset find(String id) {
        if (id == null) return null;
        for (Preset p : PRESETS) {
            if (p.id.equalsIgnoreCase(id)) return p;
        }
        return null;
    }

    /** Current preset (cached; only looks up again when the setting changes). */
    public static Preset current() {
        Session s = DonutStreamerClient.session;
        if (s == null) return PRESETS[0];
        Preset c = cached;
        if (!c.id.equals(s.theme)) {
            Preset f = find(s.theme);
            cached = c = (f == null ? PRESETS[0] : f);
        }
        return c;
    }

    private static final String[][] NAMES = {
            {"white", "FFFFFF"}, {"gray", "AAAAAA"}, {"red", "FF5555"}, {"green", "55FF55"}, {"gold", "FFD700"},
            {"yellow", "FFE066"}, {"aqua", "55FFFF"}, {"blue", "5599FF"}, {"purple", "B07CFF"}, {"pink", "FF7EDB"},
            {"orange", "FFA040"}, {"lime", "A8FF3C"}, {"teal", "2DD4BF"}, {"spotify", "1DB954"}, {"twitch", "9146FF"},
            {"youtube", "FF3B3B"},
    };

    public static String[] colorNames() {
        String[] out = new String[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) out[i] = NAMES[i][0];
        return out;
    }

    /** Colour name or hex (#RRGGBB / RRGGBB) to ARGB. Returns null when it cannot be read. */
    public static Integer parseColor(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        for (String[] n : NAMES) {
            if (n[0].equals(s)) return 0xFF000000 | Integer.parseInt(n[1], 16);
        }
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return null;
        try {
            return 0xFF000000 | Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
