package se.coldmc.kitpvp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * challenges.yml: handicaps a player takes on by choice (half a hotbar, half health ...) to earn a reward,
 * renewed daily, weekly or monthly. achievements.yml: one-time rewards for reaching a statistic.
 */
public final class Challenges {
    public static final class Modifier {
        public String id, name = "";
        public List<String> description = new ArrayList<>();
        public double maxHealth = 1, damageDealt = 1, damageTaken = 1;
        public int hotbarSlots = 9;
        public boolean noSoup;
        public List<String> effects = new ArrayList<>();
    }

    public static final class Challenge {
        public String id, name = "", icon = "PAPER", period = "daily", stat = "kills";
        public double goal = 1;
        public List<String> description = new ArrayList<>(), modifiers = new ArrayList<>(), rewards = new ArrayList<>();
    }

    public static final class Achievement {
        public String id, name = "", nameOne = null, icon = "DIAMOND", stat = "kills";
        /** The name for one step: Name-One for 1 (so "1 duel won", not "1 duels won"), else Name. */
        public String stepName(int amount) { return (amount == 1 && nameOne != null ? nameOne : name).replace("%amount%", String.valueOf(amount)); }
        public final TreeMap<Integer, List<String>> steps = new TreeMap<>();
    }

    private final KitPvP plugin;
    private NamespacedKey healthKey;
    public final Map<String, Modifier> modifiers = new LinkedHashMap<>();
    public final Map<String, Challenge> challenges = new LinkedHashMap<>();
    public final Map<String, Achievement> achievements = new LinkedHashMap<>();
    private final Map<String, Integer> rotation = new LinkedHashMap<>();

    public Challenges(KitPvP plugin) { this.plugin = plugin; healthKey = new NamespacedKey(plugin, "challenge_health"); }

    public void load() {
        modifiers.clear(); challenges.clear(); achievements.clear(); rotation.clear();
        File f = new File(plugin.getDataFolder(), "challenges.yml");
        if (!f.exists()) plugin.saveResource("challenges.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection ms = y.getConfigurationSection("Modifiers");
        if (ms != null) for (String id : ms.getKeys(false)) {
            ConfigurationSection s = ms.getConfigurationSection(id);
            Modifier m = new Modifier();
            m.id = id;
            m.name = s.getString("Name", id);
            m.description = s.getStringList("Description");
            m.maxHealth = s.getDouble("Max-Health", 1);
            m.damageDealt = s.getDouble("Damage-Dealt", 1);
            m.damageTaken = s.getDouble("Damage-Taken", 1);
            m.hotbarSlots = Math.max(1, Math.min(9, s.getInt("Hotbar-Slots", 9)));
            m.noSoup = s.getBoolean("No-Soup", false);
            m.effects = s.getStringList("Effects");
            modifiers.put(id.toLowerCase(Locale.ROOT), m);
        }
        ConfigurationSection rs = y.getConfigurationSection("Rotation");
        if (rs != null) for (String k : rs.getKeys(false)) rotation.put(k.toLowerCase(Locale.ROOT), rs.getInt(k));
        ConfigurationSection cs = y.getConfigurationSection("Challenges");
        if (cs != null) for (String id : cs.getKeys(false)) {
            ConfigurationSection s = cs.getConfigurationSection(id);
            Challenge c = new Challenge();
            c.id = id;
            c.name = s.getString("Name", id);
            c.icon = s.getString("Icon", "PAPER");
            c.period = s.getString("Period", "daily").toLowerCase(Locale.ROOT);
            c.description = s.getStringList("Description");
            c.modifiers = s.getStringList("Modifiers");
            c.rewards = s.getStringList("Rewards");
            String[] goal = s.getString("Goal", "kills >= 1").split(">=");
            c.stat = goal[0].trim().toLowerCase(Locale.ROOT);
            try { c.goal = Double.parseDouble(goal.length > 1 ? goal[1].trim() : "1"); } catch (NumberFormatException e) { c.goal = 1; }
            for (String m : c.modifiers) if (!modifiers.containsKey(m.toLowerCase(Locale.ROOT))) plugin.getLogger().warning("challenges.yml " + id + ": no modifier '" + m + "'");
            challenges.put(id.toLowerCase(Locale.ROOT), c);
        }

        File af = new File(plugin.getDataFolder(), "achievements.yml");
        if (!af.exists()) plugin.saveResource("achievements.yml", false);
        ConfigurationSection as = YamlConfiguration.loadConfiguration(af).getConfigurationSection("Achievements");
        if (as != null) for (String id : as.getKeys(false)) {
            ConfigurationSection s = as.getConfigurationSection(id);
            Achievement a = new Achievement();
            a.id = id;
            a.name = s.getString("Name", id);
            a.nameOne = s.getString("Name-One");
            a.icon = s.getString("Icon", "DIAMOND");
            a.stat = s.getString("Stat", id).toLowerCase(Locale.ROOT);
            ConfigurationSection st = s.getConfigurationSection("Steps");
            if (st != null) for (String k : st.getKeys(false)) try {
                // "100: 500" = 500 coins; or a list of reward lines
                a.steps.put(Integer.parseInt(k), st.isList(k) ? st.getStringList(k) : List.of("coins:" + st.getString(k)));
            } catch (NumberFormatException ignored) {}
            achievements.put(id.toLowerCase(Locale.ROOT), a);
        }
    }

    // ---------------------------------------------------------------- periods

    public static String periodKey(String period) {
        LocalDate d = LocalDate.now();
        return switch (period) {
            case "weekly" -> d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "monthly" -> d.getYear() + "-" + String.format("%02d", d.getMonthValue());
            case "once" -> "once";
            default -> d.toString();
        };
    }

    /** The challenges on offer right now. Rotation.<period>: N offers N of that period, the same N for everyone until it rolls over. */
    public List<Challenge> offered() {
        List<Challenge> out = new ArrayList<>();
        Map<String, List<Challenge>> by = new LinkedHashMap<>();
        for (Challenge c : challenges.values()) by.computeIfAbsent(c.period, k -> new ArrayList<>()).add(c);
        for (Map.Entry<String, List<Challenge>> e : by.entrySet()) {
            List<Challenge> l = new ArrayList<>(e.getValue());
            int n = rotation.getOrDefault(e.getKey(), 0);
            if (n > 0 && n < l.size()) {
                Collections.shuffle(l, new Random(periodKey(e.getKey()).hashCode()));
                l = l.subList(0, n);
            }
            out.addAll(l);
        }
        return out;
    }

    public boolean done(PlayerData d, Challenge c) { return periodKey(c.period).equals(d.challengeDone.get(c.id)); }

    public Challenge active(PlayerData d) {
        Challenge c = d.activeChallenge == null ? null : challenges.get(d.activeChallenge.toLowerCase(Locale.ROOT));
        if (c == null) return null;
        // Taken on in an earlier day/week/month, or no longer on offer: it has run out.
        if (!periodKey(c.period).equals(d.activePeriod)) { d.activeChallenge = null; d.since.clear(); d.dirty = true; return null; }
        return c;
    }

    public double progress(PlayerData d, Challenge c) { return d.since.getOrDefault(c.stat, 0d); }

    /** Takes a challenge on (or drops it when it is the active one). */
    public void toggle(Player p, PlayerData d, Challenge c) {
        Session s = plugin.game.session(p);
        if (s != null && s.inCombat()) { plugin.lang.send(p, "Combat.Command-Blocked"); return; }
        if (done(d, c)) { plugin.lang.send(p, "Challenges.Already-Done", "challenge", c.name); return; }
        boolean drop = c.id.equalsIgnoreCase(d.activeChallenge);
        d.activeChallenge = drop ? null : c.id;
        d.activePeriod = drop ? null : periodKey(c.period);
        d.since.clear();
        d.dirty = true;
        plugin.lang.send(p, drop ? "Challenges.Dropped" : "Challenges.Taken", "challenge", c.name, "goal", Game.num(c.goal), "stat", plugin.game.statName(c.stat));
        if (s != null && s.kit != null) { clear(p); plugin.game.equip(s, s.kit, s.tier, true); }     // the handicap starts (or ends) now
    }

    // ---------------------------------------------------------------- the handicap itself

    private List<Modifier> mods(Session s) {
        Challenge c = active(s.data);
        if (c == null) return List.of();
        List<Modifier> l = new ArrayList<>();
        for (String id : c.modifiers) { Modifier m = modifiers.get(id.toLowerCase(Locale.ROOT)); if (m != null) l.add(m); }
        return l;
    }

    public double dealt(Session s) { double x = 1; for (Modifier m : mods(s)) x *= m.damageDealt; return x; }
    public double taken(Session s) { double x = 1; for (Modifier m : mods(s)) x *= m.damageTaken; return x; }
    public boolean noSoup(Session s) { for (Modifier m : mods(s)) if (m.noSoup) return true; return false; }

    /** Called right after a kit is put on. */
    public void apply(Session s) {
        Player p = s.player;
        double health = 1; int slots = 9;
        for (Modifier m : mods(s)) {
            health *= m.maxHealth;
            slots = Math.min(slots, m.hotbarSlots);
            for (String fx : m.effects) { PotionEffect e = Scripts.parseEffect(fx); if (e != null) p.addPotionEffect(e); }
        }
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a != null) {
            a.removeModifier(healthKey);
            if (health != 1) a.addModifier(new AttributeModifier(healthKey, health - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            if (!p.isDead()) p.setHealth(Math.min(p.getHealth(), a.getValue()));
        }
        if (slots < 9) {
            PlayerInventory inv = p.getInventory();
            ItemStack lock = new ItemStack(Material.BARRIER);
            ItemMeta im = lock.getItemMeta();
            im.displayName(plugin.lang.text("Challenges.Locked-Slot"));
            im.getPersistentDataContainer().set(Items.LOBBY, PersistentDataType.STRING, "locked");
            lock.setItemMeta(im);
            for (int i = slots; i < 9; i++) {
                ItemStack was = inv.getItem(i);
                inv.setItem(i, lock.clone());
                // What the kit had there moves to the first free place; soup that does not fit is simply gone.
                if (was != null && !was.getType().isAir() && !plugin.game.isSoup(was)) {
                    int free = -1;
                    for (int j = 0; j < 36 && free < 0; j++) { ItemStack x = inv.getItem(j); if (x == null || x.getType().isAir() || (plugin.game.isSoup(x) && j >= slots)) free = j; }
                    if (free >= 0) inv.setItem(free, was);
                }
            }
        }
    }

    /** Takes the max-health change off. Everything else a handicap does lives in the inventory and effects, which a strip clears anyway. */
    public void clear(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a != null) a.removeModifier(healthKey);
    }

    // ---------------------------------------------------------------- once a second

    public void tick(Session s) {
        PlayerData d = s.data;
        if (d.bot || Game.isBot(s.player)) return;   // bots take no challenges and earn no achievements
        Challenge c = active(d);
        if (c != null && progress(d, c) >= c.goal) {
            d.challengeDone.put(c.id, periodKey(c.period));
            d.activeChallenge = null; d.activePeriod = null; d.since.clear();
            d.add("challenges_completed", 1, null);
            plugin.lang.send(s.player, "Challenges.Completed", "challenge", c.name);
            reward(s.player, d, c.rewards);
            if (s.kit != null) { clear(s.player); plugin.game.equip(s, s.kit, s.tier, true); }
        }
        for (Achievement a : achievements.values()) {
            double have = a.stat.equals("coins") ? plugin.game.balance(d) : d.stat(a.stat);
            for (Map.Entry<Integer, List<String>> st : a.steps.entrySet()) {
                if (have < st.getKey()) break;
                String key = a.id + ":" + st.getKey();
                if (!d.achievements.add(key)) continue;
                d.dirty = true;
                String name = a.stepName(st.getKey());
                plugin.lang.send(s.player, "Achievements.Unlocked", "achievement", name);
                if (plugin.getConfig().getBoolean("Achievements.Broadcast"))
                    for (Session o : plugin.game.sessions.values()) if (o != s) plugin.lang.send(o.player, "Achievements.Broadcast", "player", s.player.getName(), "achievement", name);
                reward(s.player, d, st.getValue());
            }
        }
    }

    /** Reward lines: coins:<n>  keys:<Crate>:<n>  kit:<Kit> (its next tier)  console:<command>  message:<text> */
    public void reward(Player p, PlayerData d, List<String> lines) {
        if (d.bot || Game.isBot(p)) return;
        for (String raw : lines) {
            String line = raw.replace("%player%", p.getName());
            String low = line.toLowerCase(Locale.ROOT);
            try {
                if (low.startsWith("coins:")) {
                    double n = Double.parseDouble(line.substring(6).trim());
                    plugin.game.give(d, n);
                    plugin.lang.send(p, "Rewards.Coins", "coins", Game.num(n));
                } else if (low.startsWith("keys:")) {
                    String[] a = line.substring(5).split(":");
                    Crates.Crate c = plugin.crates.crate(a[0].trim());
                    int n = a.length > 1 ? Integer.parseInt(a[1].trim()) : 1;
                    if (c != null) { plugin.crates.giveKeys(d, c, n); plugin.lang.send(p, "Rewards.Keys", "amount", n, "crate", c.name); }
                } else if (low.startsWith("kit:")) {
                    Kit k = plugin.game.kit(line.substring(4).trim());
                    if (k != null) {
                        Integer next = k.tiers.higherKey(plugin.game.owned(d, k));
                        if (next != null) { d.tiers.put(k.id, next); d.dirty = true; plugin.lang.send(p, "Rewards.Kit", "kit", k.tierName(next)); }
                    }
                } else if (low.startsWith("console:")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.substring(8).trim());
                else if (low.startsWith("message:")) p.sendMessage(Lang.color(line.substring(8).trim()));
            } catch (RuntimeException e) { plugin.getLogger().warning("reward '" + raw + "': " + e); }
        }
    }
}
