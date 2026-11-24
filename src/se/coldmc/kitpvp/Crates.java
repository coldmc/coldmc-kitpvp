package se.coldmc.kitpvp;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/** crates.yml: what a crate can roll, and how its keys are earned. A roll is decided and given at once; the animation is only a show. */
public final class Crates {
    public static final class Entry {
        String type;        // coins | new-kit | upgrade | kit
        double amount;      // coins
        String kit;         // kit:<id>
        double weight = 1;
    }

    public static final class Crate {
        public String id, name = "", icon = "CHEST";
        public List<String> description = new ArrayList<>();
        public int multiOpen = 10, rollSteps = 28;
        public int everyKills, duelWinKeys;
        public final Map<Integer, Integer> streakKeys = new TreeMap<>();
        public final List<Entry> entries = new ArrayList<>();
        public final List<String> exclude = new ArrayList<>();
        public double fallbackCoins = 50, priceBias = 1.0;
        public boolean respectPermission = true, respectRequirements = false;
    }

    /** What one roll gave. Already applied to the player when you hold one of these. */
    public static final class Result {
        public String type;     // coins | new-kit | upgrade
        public double coins;
        public Kit kit;
        public int tier;
    }

    private final KitPvP plugin;
    public final Map<String, Crate> crates = new LinkedHashMap<>();

    public Crates(KitPvP plugin) { this.plugin = plugin; }

    public Crate crate(String id) { return id == null ? null : crates.get(id.toLowerCase(Locale.ROOT)); }

    public void load() {
        crates.clear();
        File f = new File(plugin.getDataFolder(), "crates.yml");
        if (!f.exists()) plugin.saveResource("crates.yml", false);
        ConfigurationSection root = YamlConfiguration.loadConfiguration(f).getConfigurationSection("Crates");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Crate c = new Crate();
            c.id = id;
            c.name = s.getString("Name", id);
            c.icon = s.getString("Icon", "CHEST");
            c.description = s.getStringList("Description");
            c.multiOpen = Math.max(1, s.getInt("Multi-Open", 10));
            c.rollSteps = Math.max(5, s.getInt("Roll-Steps", 28));
            c.everyKills = s.getInt("Keys.Every-Kills", 0);
            c.duelWinKeys = s.getInt("Keys.Duel-Win", 0);
            ConfigurationSection st = s.getConfigurationSection("Keys.Streaks");
            if (st != null) for (String k : st.getKeys(false)) try { c.streakKeys.put(Integer.parseInt(k), st.getInt(k)); } catch (NumberFormatException ignored) {}
            c.exclude.addAll(s.getStringList("Exclude-Kits"));
            c.fallbackCoins = s.getDouble("Fallback-Coins", 50);
            c.priceBias = s.getDouble("Price-Bias", 1.0);
            c.respectPermission = s.getBoolean("Respect-Permission", true);
            c.respectRequirements = s.getBoolean("Respect-Requirements", false);
            for (String line : s.getStringList("Rewards")) {
                Entry e = parse(line);
                if (e == null) plugin.getLogger().warning("crates.yml " + id + ": cannot read reward '" + line + "'");
                else c.entries.add(e);
            }
            crates.put(id.toLowerCase(Locale.ROOT), c);
        }
    }

    /** "coins:25 weight:40" | "new-kit weight:20" | "upgrade weight:20" | "kit:Poop weight:1" */
    private Entry parse(String line) {
        Entry e = new Entry();
        for (String tok : line.trim().split("\\s+")) {
            String low = tok.toLowerCase(Locale.ROOT);
            try {
                if (low.startsWith("weight:")) e.weight = Double.parseDouble(low.substring(7));
                else if (low.startsWith("coins:")) { e.type = "coins"; e.amount = Double.parseDouble(low.substring(6)); }
                else if (low.startsWith("kit:")) { e.type = "kit"; e.kit = tok.substring(4); }
                else if (low.equals("new-kit") || low.equals("upgrade")) e.type = low;
                else return null;
            } catch (NumberFormatException ex) { return null; }
        }
        return e.type == null || e.weight <= 0 ? null : e;
    }

    // ---------------------------------------------------------------- keys

    public int keys(PlayerData d, Crate c) { return d.keys.getOrDefault(c.id, 0); }

    public void giveKeys(PlayerData d, Crate c, int n) {
        d.keys.put(c.id, Math.max(0, keys(d, c) + n));
        d.dirty = true;
    }

    /** Called for every kill: keys for every Nth win and for reaching a streak. */
    public void onKill(Session killer, double kills, double streak) {
        for (Crate c : crates.values()) {
            int n = 0;
            if (c.everyKills > 0 && kills > 0 && ((long) kills) % c.everyKills == 0) n++;
            n += c.streakKeys.getOrDefault((int) streak, 0);
            earned(killer, c, n);
        }
    }

    public void onDuelWin(Session winner) {
        for (Crate c : crates.values()) earned(winner, c, c.duelWinKeys);
    }

    private void earned(Session s, Crate c, int n) {
        if (n <= 0 || s.data.bot) return;
        giveKeys(s.data, c, n);
        plugin.lang.send(s.player, "Crates.Key-Earned", "amount", n, "crate", c.name, "keys", keys(s.data, c));
    }

    // ---------------------------------------------------------------- rolling

    private boolean allowed(Player p, PlayerData d, Crate c, Kit k, Kit.Tier t) {
        if (!k.enabled || c.exclude.stream().anyMatch(k.id::equalsIgnoreCase)) return false;
        if (c.respectPermission && !k.permission.isEmpty() && (p == null || !p.hasPermission(k.permission))) return false;
        if (c.respectRequirements) for (Game.Requirement r : plugin.game.requirements(p, d, k, t)) if (!r.met()) return false;
        return true;
    }

    /** Every kit tier this entry could give this player right now. */
    private List<Kit> candidates(Player p, PlayerData d, Crate c, Entry e) {
        List<Kit> out = new ArrayList<>();
        for (Kit k : plugin.game.kits.values()) {
            if (k.hidden) continue;
            int have = plugin.game.owned(d, k);
            Integer next = k.tiers.higherKey(have);
            if (next == null) continue;
            boolean fits = switch (e.type) {
                case "new-kit" -> have == 0;
                case "upgrade" -> have > 0;
                case "kit" -> k.id.equalsIgnoreCase(e.kit);
                default -> false;
            };
            if (fits && allowed(p, d, c, k, k.tiers.get(next))) out.add(k);
        }
        return out;
    }

    private Result pick(Player p, PlayerData d, Crate c) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Entry> live = new ArrayList<>();
        Map<Entry, List<Kit>> pools = new LinkedHashMap<>();
        double total = 0;
        for (Entry e : c.entries) {
            if (!e.type.equals("coins")) {
                List<Kit> pool = candidates(p, d, c, e);
                if (pool.isEmpty()) continue;       // nothing left of this kind: its chance goes to the others
                pools.put(e, pool);
            }
            live.add(e); total += e.weight;
        }
        Result r = new Result();
        if (live.isEmpty()) { r.type = "coins"; r.coins = c.fallbackCoins; return r; }
        double x = rnd.nextDouble(total);
        Entry chosen = live.get(live.size() - 1);
        for (Entry e : live) { x -= e.weight; if (x < 0) { chosen = e; break; } }
        if (chosen.type.equals("coins")) { r.type = "coins"; r.coins = chosen.amount; return r; }
        // Among the kits, a cheaper tier is likelier (Price-Bias 0 = all equal).
        List<Kit> pool = pools.get(chosen);
        double[] w = new double[pool.size()];
        double sum = 0;
        for (int i = 0; i < w.length; i++) {
            Kit k = pool.get(i);
            double cost = Math.max(1, k.tiers.get(k.tiers.higherKey(plugin.game.owned(d, k))).cost);
            sum += w[i] = 1.0 / Math.pow(cost, c.priceBias);
        }
        double y = rnd.nextDouble(sum);
        Kit kit = pool.get(pool.size() - 1);
        for (int i = 0; i < w.length; i++) { y -= w[i]; if (y < 0) { kit = pool.get(i); break; } }
        int have = plugin.game.owned(d, kit);
        r.kit = kit; r.tier = kit.tiers.higherKey(have); r.type = have == 0 ? "new-kit" : "upgrade";
        return r;
    }

    /** Spends one key and gives the reward. Null when there is no key. */
    public Result open(Player p, PlayerData d, Crate c) {
        if (keys(d, c) <= 0) return null;
        giveKeys(d, c, -1);
        Result r = pick(p, d, c);
        if (r.kit != null) d.tiers.put(r.kit.id, r.tier); else plugin.game.give(d, r.coins);
        d.add("crates_opened", 1, null);
        d.dirty = true;
        return r;
    }

    /** A look-alike for the reel: something this crate could show, given to nobody. */
    public ItemStack decoy(Crate c) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (!c.entries.isEmpty()) {
            Entry e = c.entries.get(rnd.nextInt(c.entries.size()));
            if (e.type.equals("coins")) { Result r = new Result(); r.type = "coins"; r.coins = e.amount; return display(r); }
        }
        List<Kit> all = new ArrayList<>();
        for (Kit k : plugin.game.kits.values()) if (k.enabled && !k.hidden && c.exclude.stream().noneMatch(k.id::equalsIgnoreCase)) all.add(k);
        if (all.isEmpty()) return new ItemStack(Material.GOLD_NUGGET);
        Result r = new Result();
        r.kit = all.get(rnd.nextInt(all.size())); r.tier = r.kit.tiers.firstKey(); r.type = "new-kit";
        return display(r);
    }

    public ItemStack display(Result r) {
        Lang lang = plugin.lang;
        ItemStack it;
        if (r.kit == null) {
            Material m = Material.matchMaterial(plugin.getConfig().getString("Menus.Items.Coins", "GOLD_NUGGET"));
            it = new ItemStack(m == null ? Material.GOLD_NUGGET : m);
        } else {
            it = Items.parse(r.kit.icon);
            if (it == null) it = new ItemStack(Material.IRON_SWORD);
        }
        var meta = it.getItemMeta();
        meta.displayName(Lang.color(text(r)));
        meta.lore(lang.lore("Crates.Reward-Lore." + r.type));
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(meta);
        return it;
    }

    /** "250 coins" / the kit's tier name, in lang.yml's words. */
    public String text(Result r) {
        return r.kit == null ? plugin.lang.raw("Crates.Reward.coins", "coins", Game.num(r.coins))
                : plugin.lang.raw("Crates.Reward." + r.type, "kit", r.kit.tierName(r.tier), "tier", r.tier);
    }
}
