package se.coldmc.kitpvp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** One kit = one file in kits/. Tiers are its upgrades; tier 1 is the kit as bought. */
public final class Kit {
    public static final int BOOTS = 36, LEGGINGS = 37, CHESTPLATE = 38, HELMET = 39, OFFHAND = 40;
    private static final String[] ARMOR_KEYS = {"boots", "leggings", "chestplate", "helmet"};

    public final String id;
    public String name;
    public Object icon = "IRON_SWORD";
    public int slot = -1, page = 1;
    public String permission = "";
    /** Rarity: in the file - overrides the one tier 1's price gives (Kits.Rarities). */
    public String rarity = null;
    /** Filler: the glass behind this kit's buy and upgrade previews; null = Menus.Items.Preview-Filler. */
    public String filler = null;
    /** Hidden: true = never in a menu, shop, crate or /kp kit - only handed out by a duel arena's Kit: (Sumo, Gunfight). */
    public boolean hidden = false;
    public int cooldown = 0;
    public boolean enabled = true, soupFill = true;
    public List<String> description = new ArrayList<>();
    public List<String> abilities = new ArrayList<>();
    public Map<String, List<String>> scripts = new LinkedHashMap<>();
    public final TreeMap<Integer, Tier> tiers = new TreeMap<>();

    public static final class Tier {
        public String name = "";
        public double cost = 0;
        public List<String> requirements = new ArrayList<>();
        /** Inventory slot -> item text. 0-8 hotbar, 9-35 inventory, 36-39 armour, 40 offhand. */
        public final TreeMap<Integer, Object> items = new TreeMap<>();
        public List<String> effects = new ArrayList<>();
        public List<String> abilities = new ArrayList<>();
        public Map<String, List<String>> scripts = new LinkedHashMap<>();

        public Tier copy() {
            Tier t = new Tier();
            t.name = name; t.cost = cost; t.requirements = new ArrayList<>(requirements);
            t.items.putAll(items); t.effects = new ArrayList<>(effects); t.abilities = new ArrayList<>(abilities);
            scripts.forEach((k, v) -> t.scripts.put(k, new ArrayList<>(v)));
            return t;
        }
    }

    public Kit(String id) { this.id = id; this.name = "&a" + id; }

    public int maxTier() { return tiers.isEmpty() ? 0 : tiers.lastKey(); }
    public Tier tier(int n) { Tier t = tiers.get(n); return t != null ? t : (tiers.isEmpty() ? null : tiers.firstEntry().getValue()); }
    /** What this tier is called: its own name, or the kit's name. */
    public String tierName(int n) { return Rarity.paint(writtenName(n), Rarity.of(this, n)); }
    /** The name as written in the file, without the rarity colour. */
    public String writtenName(int n) { Tier t = tiers.get(n); return t != null && !t.name.isEmpty() ? t.name : name; }
    public int rarityOf(int n) { return Rarity.of(this, n); }
    /** Menu order: rarity, then price, then name. */
    public static final java.util.Comparator<Kit> BY_RARITY = java.util.Comparator
            .comparingInt((Kit k) -> k.rarityOf(k.tiers.isEmpty() ? 1 : k.tiers.firstKey()))
            .thenComparingDouble(k -> k.tiers.isEmpty() ? 0 : k.tiers.firstEntry().getValue().cost)
            .thenComparing(k -> k.id, String.CASE_INSENSITIVE_ORDER);

    // ---------------------------------------------------------------- yaml

    private static Map<String, List<String>> readScripts(ConfigurationSection s) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (s != null) for (String k : s.getKeys(false)) {
            List<String> l = s.isList(k) ? s.getStringList(k) : new ArrayList<>(List.of(String.valueOf(s.get(k))));
            out.put(k.toLowerCase(Locale.ROOT).replace('_', '-'), l);
        }
        return out;
    }

    private static Object raw(Object o) { return o instanceof ConfigurationSection cs ? deep(cs) : o; }
    private static Map<String, Object> deep(ConfigurationSection cs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : cs.getKeys(false)) m.put(k, raw(cs.get(k)));
        return m;
    }

    public static Kit load(File f) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Kit k = new Kit(f.getName().replaceFirst("\\.yml$", ""));
        k.name = y.getString("Name", k.name);
        if (y.contains("Icon")) k.icon = raw(y.get("Icon"));
        k.slot = y.isInt("Slot") ? y.getInt("Slot") : -1;
        k.page = Math.max(1, y.getInt("Page", 1));
        k.permission = y.getString("Permission", "");
        k.rarity = y.getString("Rarity", null);
        k.filler = y.getString("Filler", null);
        k.hidden = y.getBoolean("Hidden", false);
        k.cooldown = y.getInt("Cooldown", 0);
        k.enabled = y.getBoolean("Enabled", true);
        k.soupFill = y.getBoolean("Soup-Fill", true);
        k.description = y.getStringList("Description");
        k.abilities = y.getStringList("Abilities");
        k.scripts = readScripts(y.getConfigurationSection("Scripts"));
        ConfigurationSection ts = y.getConfigurationSection("Tiers");
        if (ts != null) for (String key : ts.getKeys(false)) {
            ConfigurationSection s = ts.getConfigurationSection(key);
            if (s == null) continue;
            Tier t = new Tier();
            t.name = s.getString("Name", "");
            t.cost = s.getDouble("Cost", 0);
            t.requirements = s.getStringList("Requirements");
            t.effects = s.getStringList("Effects");
            t.abilities = s.getStringList("Abilities");
            t.scripts = readScripts(s.getConfigurationSection("Scripts"));
            ConfigurationSection ar = s.getConfigurationSection("Armor");
            if (ar != null) for (int i = 0; i < 4; i++) for (String ak : ar.getKeys(false))
                if (ak.equalsIgnoreCase(ARMOR_KEYS[i])) t.items.put(BOOTS + i, raw(ar.get(ak)));
            ConfigurationSection it = s.getConfigurationSection("Items");
            if (it != null) for (String ik : it.getKeys(false)) {
                int slot;
                if (ik.equalsIgnoreCase("offhand")) slot = OFFHAND;
                else try { slot = Integer.parseInt(ik); } catch (NumberFormatException e) { continue; }
                if (slot >= 0 && slot <= 35 || slot == OFFHAND) t.items.put(slot, raw(it.get(ik)));
            } else if (s.isList("Items")) {            // a plain list fills the hotbar onwards
                int slot = 0;
                for (Object o : s.getList("Items")) t.items.put(slot++, o);
            }
            try { k.tiers.put(Integer.parseInt(key), t); } catch (NumberFormatException ignored) {}
        }
        if (k.tiers.isEmpty()) k.tiers.put(1, new Tier());
        return k;
    }

    public void save(File f) throws java.io.IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "KitPvP kit. Edit freely, then /kp admin reload (or use /kp admin kiteditor).",
                "Items: one line each -  MATERIAL[*amount] [enchant:<name>:<level>] [model:nexo:<id>]",
                "  [armor-model:nexo:<set>] [color:#RRGGBB] [potion:<type>] [ability:<id>] [unbreakable] [glow]",
                "  or  nexo:<id>  /  mmoitems:<TYPE>:<ID>  for a real custom item. Slots 0-8 are the hotbar.",
                "Soup-Fill: true fills every empty slot with soup. Scripts: on-<trigger>: [actions] (see abilities.yml).",
                "Filler: a material for the empty slots of this kit's buy and upgrade previews (default BLACK_STAINED_GLASS_PANE)."));
        y.set("Name", name);
        y.set("Enabled", enabled);
        y.set("Icon", icon);
        if (slot >= 0) y.set("Slot", slot);
        if (page > 1) y.set("Page", page);
        y.set("Permission", permission);
        if (rarity != null && !rarity.isBlank()) y.set("Rarity", rarity);
        if (filler != null && !filler.isBlank()) y.set("Filler", filler);
        if (hidden) y.set("Hidden", true);
        y.set("Cooldown", cooldown);
        y.set("Soup-Fill", soupFill);
        y.set("Description", description);
        y.set("Abilities", abilities);
        if (!scripts.isEmpty()) scripts.forEach((key, v) -> y.set("Scripts." + key, v));
        for (Map.Entry<Integer, Tier> e : tiers.entrySet()) {
            String p = "Tiers." + e.getKey() + ".";
            Tier t = e.getValue();
            if (!t.name.isEmpty()) y.set(p + "Name", t.name);
            y.set(p + "Cost", t.cost == Math.rint(t.cost) ? (Object) (long) t.cost : (Object) t.cost);
            if (!t.requirements.isEmpty()) y.set(p + "Requirements", t.requirements);
            for (int i = 3; i >= 0; i--) if (t.items.containsKey(BOOTS + i)) y.set(p + "Armor." + ARMOR_KEYS[i], t.items.get(BOOTS + i));
            for (Map.Entry<Integer, Object> it : t.items.entrySet()) {
                if (it.getKey() <= 35) y.set(p + "Items." + it.getKey(), it.getValue());
                else if (it.getKey() == OFFHAND) y.set(p + "Items.offhand", it.getValue());
            }
            if (!t.effects.isEmpty()) y.set(p + "Effects", t.effects);
            if (!t.abilities.isEmpty()) y.set(p + "Abilities", t.abilities);
            t.scripts.forEach((key, v) -> y.set(p + "Scripts." + key, v));
        }
        y.save(f);
    }
}
