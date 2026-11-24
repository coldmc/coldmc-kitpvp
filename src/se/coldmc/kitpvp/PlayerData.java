package se.coldmc.kitpvp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** One player's KitPvP record: coins, owned tiers, and statistics both overall and per kit. */
public final class PlayerData {
    public final UUID uuid;
    public String name = "?";
    public double coins;
    public final Map<String, Double> stats = new TreeMap<>();
    public final Map<String, Map<String, Double>> kitStats = new TreeMap<>();
    public final Map<String, Integer> tiers = new TreeMap<>();
    public final Map<String, Long> cooldowns = new HashMap<>();   // kit -> last equip, not saved
    public final Map<String, Integer> keys = new TreeMap<>();               // crate id -> keys held
    public String activeChallenge, activePeriod;                           // the handicap taken on, and the day/week/month it belongs to
    public final Map<String, Double> since = new TreeMap<>();              // statistics gained since it was taken on
    public final Map<String, String> challengeDone = new TreeMap<>();      // challenge id -> period it was last completed in
    public final java.util.Set<String> achievements = new java.util.TreeSet<>();   // "<id>:<step>"
    public boolean dirty;
    /** A ColdPlayersPlus bot: its record is never saved and never on a leaderboard. */
    public transient boolean bot;

    public PlayerData(UUID uuid) { this.uuid = uuid; }

    public double stat(String s) { return stats.getOrDefault(s, 0d); }
    public double kitStat(String kit, String s) {
        Map<String, Double> m = kitStats.get(kit);
        return m == null ? 0 : m.getOrDefault(s, 0d);
    }
    /** Adds to the overall statistic and, when a kit is given, to that kit's own count. */
    public void add(String stat, double n, String kit) {
        stats.merge(stat, n, Double::sum);
        if (kit != null) kitStats.computeIfAbsent(kit, k -> new TreeMap<>()).merge(stat, n, Double::sum);
        if (activeChallenge != null && n > 0) since.merge(stat, n, Double::sum);
        dirty = true;
    }
    public void set(String stat, double n, String kit) {
        if (kit == null) stats.put(stat, n); else kitStats.computeIfAbsent(kit, k -> new TreeMap<>()).put(stat, n);
        dirty = true;
    }
    public double kd() { double d = stat("deaths"); return d == 0 ? stat("kills") : Math.round(stat("kills") / d * 100) / 100.0; }

    public static PlayerData load(File f, double startCoins) {
        UUID id = UUID.fromString(f.getName().replaceFirst("\\.yml$", ""));
        PlayerData d = new PlayerData(id);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        d.name = y.getString("Name", "?");
        d.coins = y.getDouble("Coins", startCoins);
        ConfigurationSection s = y.getConfigurationSection("Stats");
        if (s != null) for (String k : s.getKeys(false)) d.stats.put(k, s.getDouble(k));
        ConfigurationSection t = y.getConfigurationSection("Tiers");
        if (t != null) for (String k : t.getKeys(false)) d.tiers.put(k, t.getInt(k));
        ConfigurationSection ks = y.getConfigurationSection("Kit-Stats");
        if (ks != null) for (String kit : ks.getKeys(false)) {
            ConfigurationSection one = ks.getConfigurationSection(kit);
            if (one == null) continue;
            Map<String, Double> m = new TreeMap<>();
            for (String k : one.getKeys(false)) m.put(k, one.getDouble(k));
            d.kitStats.put(kit, m);
        }
        ConfigurationSection kk = y.getConfigurationSection("Keys");
        if (kk != null) for (String k : kk.getKeys(false)) d.keys.put(k, kk.getInt(k));
        d.activeChallenge = y.getString("Challenge.Active");
        d.activePeriod = y.getString("Challenge.Period");
        ConfigurationSection cs = y.getConfigurationSection("Challenge.Since");
        if (cs != null) for (String k : cs.getKeys(false)) d.since.put(k, cs.getDouble(k));
        ConfigurationSection cd = y.getConfigurationSection("Challenge.Done");
        if (cd != null) for (String k : cd.getKeys(false)) d.challengeDone.put(k, cd.getString(k));
        d.achievements.addAll(y.getStringList("Achievements"));
        return d;
    }

    public void save(File f) throws java.io.IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("Name", name);
        y.set("Coins", coins);
        stats.forEach((k, v) -> y.set("Stats." + k, v));
        tiers.forEach((k, v) -> y.set("Tiers." + k, v));
        kitStats.forEach((kit, m) -> m.forEach((k, v) -> y.set("Kit-Stats." + kit + "." + k, v)));
        keys.forEach((k, v) -> y.set("Keys." + k, v));
        if (activeChallenge != null) {
            y.set("Challenge.Active", activeChallenge);
            y.set("Challenge.Period", activePeriod);
            since.forEach((k, v) -> y.set("Challenge.Since." + k, v));
        }
        challengeDone.forEach((k, v) -> y.set("Challenge.Done." + k, v));
        if (!achievements.isEmpty()) y.set("Achievements", new java.util.ArrayList<>(achievements));
        y.save(f);
        dirty = false;
    }
}
