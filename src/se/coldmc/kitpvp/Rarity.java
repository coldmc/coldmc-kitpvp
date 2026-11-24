package se.coldmc.kitpvp;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kit rarities (config.yml Kits.Rarities). A kit's rarity comes from the price of tier 1 (or its
 * own Rarity: line); each upgrade then climbs the ladder by its step. The rarity sets the colour
 * of every kit and tier name, and the order of the kit menus. the owner, 2026-11-13.
 */
public final class Rarity {
    public record Level(String name, String color) {}

    private static boolean enabled = false;
    private static final List<Level> ladder = new ArrayList<>();
    private static final List<Double> bands = new ArrayList<>();
    private static final Map<Integer, Integer> steps = new HashMap<>();
    /** Leading colour codes on a written name: &a, &#rrggbb, <#rrggbb>. Formats such as &l stay. */
    private static final Pattern LEADING_COLOR = Pattern.compile("^(?:&[0-9a-fA-F]|&#[0-9a-fA-F]{6}|<#[0-9a-fA-F]{6}>)+");

    private Rarity() {}

    public static void load(ConfigurationSection c) {
        ladder.clear(); bands.clear(); steps.clear();
        enabled = c != null && c.getBoolean("Enabled", true);
        if (c == null) return;
        for (String s : c.getStringList("Ladder")) {
            int i = s.indexOf(':');
            ladder.add(i < 0 ? new Level(s, "&f") : new Level(s.substring(0, i).trim(), s.substring(i + 1).trim()));
        }
        for (Object o : c.getList("Price-Bands", List.of())) try { bands.add(Double.parseDouble(String.valueOf(o))); } catch (NumberFormatException ignored) {}
        ConfigurationSection st = c.getConfigurationSection("Upgrade-Steps");
        if (st != null) for (String k : st.getKeys(false)) try { steps.put(Integer.parseInt(k), st.getInt(k)); } catch (NumberFormatException ignored) {}
        if (ladder.isEmpty()) enabled = false;
    }

    public static boolean enabled() { return enabled; }

    public static int index(String name) {
        if (name == null || name.isBlank()) return -1;
        for (int i = 0; i < ladder.size(); i++) if (ladder.get(i).name().equalsIgnoreCase(name.trim())) return i;
        return -1;
    }

    /** The rarity of a price: the first band it fits under; above every band = the next rung. */
    public static int ofPrice(double cost) {
        for (int i = 0; i < bands.size(); i++) if (cost <= bands.get(i)) return Math.min(i, ladder.size() - 1);
        return Math.min(bands.size(), ladder.size() - 1);
    }

    /** The rarity of tier n of a kit, as a ladder index; -1 when rarities are off. */
    public static int of(Kit k, int n) {
        if (!enabled || k.tiers.isEmpty()) return -1;
        int r = k.rarity != null && index(k.rarity) >= 0 ? index(k.rarity) : ofPrice(k.tiers.firstEntry().getValue().cost);
        for (int key : k.tiers.keySet()) {
            if (key == k.tiers.firstKey()) continue;
            if (key > n) break;
            r += steps.getOrDefault(key, 0);
        }
        return Math.max(0, Math.min(r, ladder.size() - 1));
    }

    public static Level level(int i) { return i < 0 || i >= ladder.size() ? null : ladder.get(i); }

    /** The name with its written colour replaced by the rarity colour. */
    public static String paint(String name, int i) {
        Level l = level(i);
        return l == null ? name : l.color() + LEADING_COLOR.matcher(name == null ? "" : name).replaceFirst("");
    }

    public static String label(int i) {
        Level l = level(i);
        return l == null ? "" : l.color() + l.name();
    }

    public static String key(int i) { Level l = level(i); return l == null ? "" : l.name().toLowerCase(Locale.ROOT); }
}
