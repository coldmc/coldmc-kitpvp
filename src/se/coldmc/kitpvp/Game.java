package se.coldmc.kitpvp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The rules of KitPvP: who is in, what they wear, what a kill is worth. */
public final class Game {
    private final KitPvP plugin;
    public final Map<UUID, Session> sessions = new HashMap<>();
    public final Map<UUID, PlayerData> data = new HashMap<>();
    public final Map<String, Arena> arenas = new LinkedHashMap<>();
    public final Map<String, Kit> kits = new LinkedHashMap<>();
    public final Map<UUID, UUID> profileOf = new HashMap<>();
    /** target -> [challenger, expiry millis] */
    public final Map<UUID, Object[]> duelRequests = new HashMap<>();
    private final Set<String> arenaWorlds = new HashSet<>();

    public Game(KitPvP plugin) { this.plugin = plugin; }

    private FileConfiguration cfg() { return plugin.getConfig(); }
    private Lang lang() { return plugin.lang; }

    // ================================================================ files

    public File kitFile(String id) { return new File(plugin.getDataFolder(), "kits/" + id + ".yml"); }
    private File playerFile(UUID id) { return new File(plugin.getDataFolder(), "players/" + id + ".yml"); }
    private File snapshotFile(UUID id) { return new File(plugin.getDataFolder(), "sessions/" + id + ".yml"); }

    public void loadAll() {
        kits.clear();
        Rarity.load(cfg().getConfigurationSection("Kits.Rarities"));
        File kd = new File(plugin.getDataFolder(), "kits");
        if (!kd.exists()) { kd.mkdirs(); plugin.saveResource("kits/Example.yml", false); }
        File[] fs = kd.listFiles((d, n) -> n.endsWith(".yml"));
        if (fs != null) {
            java.util.Arrays.sort(fs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File f : fs) {
                try { Kit k = Kit.load(f); kits.put(k.id.toLowerCase(Locale.ROOT), k); }
                catch (Exception e) { plugin.getLogger().warning("kits/" + f.getName() + ": " + e); }
            }
        }
        arenas.clear(); arenaWorlds.clear();
        File af = new File(plugin.getDataFolder(), "arenas.yml");
        if (!af.exists()) plugin.saveResource("arenas.yml", false);
        ConfigurationSection as = YamlConfiguration.loadConfiguration(af).getConfigurationSection("Arenas");
        if (as != null) for (String id : as.getKeys(false)) {
            ConfigurationSection s = as.getConfigurationSection(id);
            if (s == null) continue;
            Arena a = new Arena(id);
            a.type = "DUEL".equalsIgnoreCase(s.getString("Type")) ? Arena.Type.DUEL : Arena.Type.FFA;
            a.enabled = s.getBoolean("Enabled", true);
            a.safeRadius = s.getDouble("Safe-Radius", 0);
            a.spawns.addAll(s.getStringList("Spawns"));
            a.queue = s.getString("Queue", "pvp").toLowerCase(Locale.ROOT);
            if (s.isList("Kit")) a.kits.addAll(s.getStringList("Kit")); else if (s.isString("Kit")) a.kits.add(s.getString("Kit"));
            a.waterLoses = s.getBoolean("Water-Loses", false);
            a.fallDepth = s.getDouble("Fall-Depth", 6);
            a.noDamage = s.getBoolean("No-Damage", false);
            arenas.put(id.toLowerCase(Locale.ROOT), a);
            if (a.worldName() != null) arenaWorlds.add(a.worldName());
        }

        File pd = new File(plugin.getDataFolder(), "players");
        pd.mkdirs();
        File[] ps = pd.listFiles((d, n) -> n.endsWith(".yml"));
        if (ps != null) for (File f : ps) {
            try {
                PlayerData d = PlayerData.load(f, cfg().getDouble("Economy.Starting-Coins"));
                PlayerData live = data.get(d.uuid);
                if (live == null) data.put(d.uuid, d);
            } catch (Exception e) { plugin.getLogger().warning("players/" + f.getName() + ": " + e); }
        }
        // Sessions keep pointing at the reloaded kit of the same name.
        for (Session s : sessions.values()) if (s.kit != null) {
            Kit k = kit(s.kit.id);
            if (k != null) s.kit = k;
        }
    }

    /** Reports every icon and item that will not build - once, here, not when a player picks the kit.
     *  Called after the server has finished starting, because Nexo and MMOItems load their items late. */
    public void validateKits() {
        for (Kit k : kits.values()) {
            if (Items.parse(k.icon) == null) plugin.getLogger().warning("Kit " + k.id + ": the icon does not build: " + k.icon);
            for (Map.Entry<Integer, Kit.Tier> t : k.tiers.entrySet())
                for (Map.Entry<Integer, Object> it : t.getValue().items.entrySet())
                    if (!Items.isEmptyMarker(it.getValue()) && Items.parse(it.getValue()) == null) plugin.getLogger().warning("Kit " + k.id + " tier " + t.getKey() + " slot " + it.getKey() + ": item does not build: " + it.getValue());
            for (String a : k.abilities) if (plugin.scripts.ability(a) == null) plugin.getLogger().warning("Kit " + k.id + ": no ability '" + a + "' in abilities.yml");
        }
    }

    public void saveArenas() {
        YamlConfiguration y = new YamlConfiguration();
        for (Arena a : arenas.values()) {
            String p = "Arenas." + a.id + ".";
            y.set(p + "Type", a.type.name());
            y.set(p + "Enabled", a.enabled);
            y.set(p + "Safe-Radius", a.safeRadius);
            y.set(p + "Spawns", a.spawns);
            if (a.type == Arena.Type.DUEL) {
                y.set(p + "Queue", a.queue);
                if (!a.kits.isEmpty()) y.set(p + "Kit", a.kits.size() == 1 ? a.kits.get(0) : a.kits);
                if (a.waterLoses) { y.set(p + "Water-Loses", true); y.set(p + "Fall-Depth", a.fallDepth); }
                if (a.noDamage) y.set(p + "No-Damage", true);
            }
        }
        try { y.save(new File(plugin.getDataFolder(), "arenas.yml")); } catch (Exception e) { plugin.getLogger().warning("arenas.yml: " + e); }
        arenaWorlds.clear();
        for (Arena a : arenas.values()) if (a.worldName() != null) arenaWorlds.add(a.worldName());
    }

    public void saveKit(Kit k) {
        try { k.save(kitFile(k.id)); } catch (Exception e) { plugin.getLogger().warning("kits/" + k.id + ".yml: " + e); }
        kits.put(k.id.toLowerCase(Locale.ROOT), k);
    }

    /** A removed kit's file is moved to kits-removed/, never deleted. */
    public void removeKit(Kit k) {
        kits.remove(k.id.toLowerCase(Locale.ROOT));
        File dir = new File(plugin.getDataFolder(), "kits-removed");
        dir.mkdirs();
        File f = kitFile(k.id);
        if (f.exists()) f.renameTo(new File(dir, k.id + "-" + System.currentTimeMillis() + ".yml"));
    }

    public void saveDirty(boolean all) {
        for (PlayerData d : data.values()) if (d.dirty && !d.bot) {
            try { d.save(playerFile(d.uuid)); } catch (Exception e) { plugin.getLogger().warning("players/" + d.uuid + ": " + e); }
        }
    }

    public Kit kit(String id) { return id == null ? null : kits.get(id.toLowerCase(Locale.ROOT)); }
    public Arena arena(String id) { return id == null ? null : arenas.get(id.toLowerCase(Locale.ROOT)); }
    public Session session(Player p) { return p == null ? null : sessions.get(p.getUniqueId()); }
    public boolean isArenaWorld(String world) { return arenaWorlds.contains(world); }

    public PlayerData data(Player p) {
        PlayerData d = data.computeIfAbsent(p.getUniqueId(), id -> {
            PlayerData n = new PlayerData(id);
            n.coins = cfg().getDouble("Economy.Starting-Coins");
            n.dirty = true;
            return n;
        });
        if (!p.getName().equals(d.name)) { d.name = p.getName(); d.dirty = true; }
        d.bot = isBot(p);
        return d;
    }

    // ================================================================ bots

    /** ColdPlayersPlus marks every bot it runs with this metadata (PvpBots.BOT_META). */
    public static boolean isBot(Player p) { return p != null && p.hasMetadata("cpp_bot"); }

    /**
     * Whether a fight between these two may change statistics, coins, keys, streaks and the
     * leaderboards. A fight with a bot on either side does not, unless Bots.Affect-Stats is true.
     */
    public boolean counts(Session a, Session b) {
        boolean bot = (a != null && (a.data.bot || isBot(a.player))) || (b != null && (b.data.bot || isBot(b.player)));
        return !bot || cfg().getBoolean("Bots.Affect-Stats", false);
    }

    public PlayerData dataByName(String name) {
        for (PlayerData d : data.values()) if (d.name.equalsIgnoreCase(name)) return d;
        return null;
    }

    // ================================================================ money

    private boolean vault() { return cfg().getBoolean("Economy.Use-Vault"); }
    public double balance(PlayerData d) { return vault() ? Hooks.vaultBalance(Bukkit.getOfflinePlayer(d.uuid)) : d.coins; }
    public void give(PlayerData d, double amount) {
        if (amount == 0 || d.bot) return;   // a bot has no wallet
        if (vault()) { Hooks.vaultGive(Bukkit.getOfflinePlayer(d.uuid), amount); return; }
        d.coins = Math.max(0, d.coins + amount);
        d.dirty = true;
    }
    public static String num(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 100) / 100.0); }

    // ================================================================ join / leave

    public boolean join(Player p, Arena arena) {
        if (session(p) != null) { lang().send(p, "Join.Already"); return false; }
        if (arena == null || !arena.enabled || arena.type != Arena.Type.FFA) { lang().send(p, "Join.No-Arena"); return false; }
        Location spawn = arena.randomSpawn();
        if (spawn == null) { lang().send(p, "Join.No-Spawn", "arena", arena.id); return false; }
        saveSnapshot(p);
        Session s = new Session(p, data(p), arena);
        sessions.put(p.getUniqueId(), s);
        if (plugin.mmo != null) plugin.mmo.clear(p);   // kit stats only: no MMOCore/MMOItems stats inside
        strip(p);
        try { p.setGameMode(GameMode.valueOf(cfg().getString("Join.Game-Mode", "SURVIVAL").toUpperCase(Locale.ROOT))); } catch (IllegalArgumentException ignored) {}
        p.setAllowFlight(false); p.setFlying(false);
        if (!p.teleport(spawn)) {       // another plugin refused the teleport: hand everything back
            sessions.remove(p.getUniqueId());
            strip(p);
            if (plugin.mmo != null) plugin.mmo.restore(p);
            restoreSnapshot(p, false);
            lang().send(p, "Join.No-Spawn", "arena", arena.id);
            return false;
        }
        lang().send(p, "Join.Joined", "arena", arena.id);
        startLife(s, false);
        plugin.scripts.fire(s, "join", null, p.getLocation(), 0, false, null);
        return true;
    }

    /** Ends the session and gives the player back exactly what they came in with. */
    public void leave(Player p, boolean teleportBack, boolean quiet) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        leaveQueue(p, true);
        if (s.duel != null && !s.duel.over) endDuel(s.duel, s.duel.other(s), s);
        if (s.kit != null) plugin.scripts.fire(s, "unequip", null, p.getLocation(), 0, false, null);
        plugin.scripts.fire(s, "leave", null, p.getLocation(), 0, false, null);
        sessions.remove(p.getUniqueId());
        strip(p);
        if (plugin.mmo != null) plugin.mmo.restore(p);   // before the health comes back, so max health is right again
        restoreSnapshot(p, teleportBack);
        if (s.data.dirty && !s.data.bot) try { s.data.save(playerFile(s.data.uuid)); } catch (Exception e) { plugin.getLogger().warning("players: " + e); }
        if (!quiet) lang().send(p, "Leave.Left");
    }

    private void strip(Player p) {
        plugin.natives.clear(p); plugin.challenges.clear(p);
        // Close menus only. Closing the player's OWN inventory view empties its crafting grid
        // into the inventory, which clear() then wipes - that took the ItemJoin Respawn/Leave
        // buttons on every death (the owner, 2026-11-13: "when I die I dont have the itemjoin items").
        org.bukkit.event.inventory.InventoryType open = p.getOpenInventory().getType();
        if (open != org.bukkit.event.inventory.InventoryType.CRAFTING && open != org.bukkit.event.inventory.InventoryType.CREATIVE) p.closeInventory();
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.getInventory().setItemInOffHand(null);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setFireTicks(0);
        p.setFallDistance(0);
        double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
        if (!p.isDead()) p.setHealth(max);
        p.setFoodLevel(cfg().getBoolean("Combat.Hunger") ? 20 : foodLevel());
        p.setSaturation(cfg().getBoolean("Combat.Natural-Regen") ? 5 : 0);
    }

    /** The food level held in the arena worlds when Combat.Hunger is off (Combat.Food-Level). */
    public int foodLevel() { return Math.max(20, cfg().getInt("Combat.Food-Level", 22)); }

    // ---------------------------------------------------------------- snapshot (written to disk: a crash must not eat an inventory)

    private void saveSnapshot(Player p) {
        YamlConfiguration y = new YamlConfiguration();
        PlayerInventory inv = p.getInventory();
        y.set("Inventory", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(inv.getContents())));
        y.set("Health", p.getHealth());
        y.set("Food", p.getFoodLevel());
        y.set("Saturation", (double) p.getSaturation());
        y.set("Exp", (double) p.getExp());
        y.set("Level", p.getLevel());
        y.set("Game-Mode", p.getGameMode().name());
        y.set("Allow-Flight", p.getAllowFlight());
        y.set("Flying", p.isFlying());
        y.set("Location", Arena.toText(p.getLocation()));
        List<String> fx = new ArrayList<>();
        for (PotionEffect e : p.getActivePotionEffects()) fx.add(e.getType().getKey().getKey() + ":" + e.getAmplifier() + ":" + e.getDuration());
        y.set("Effects", fx);
        UUID profile = profileOf.get(p.getUniqueId());
        if (profile != null) y.set("Profile", profile.toString());
        File f = snapshotFile(p.getUniqueId());
        f.getParentFile().mkdirs();
        try { y.save(f); } catch (Exception e) { plugin.getLogger().severe("Could not write the inventory snapshot of " + p.getName() + ": " + e); }
    }

    private void restoreSnapshot(Player p, boolean teleport) {
        File f = snapshotFile(p.getUniqueId());
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        try {
            ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(y.getString("Inventory", "")));
            p.getInventory().setContents(items);
        } catch (Exception e) { plugin.getLogger().severe("Could not restore the inventory of " + p.getName() + " from " + f + ": " + e); return; }
        try { p.setGameMode(GameMode.valueOf(y.getString("Game-Mode", "SURVIVAL"))); } catch (IllegalArgumentException ignored) {}
        p.setAllowFlight(y.getBoolean("Allow-Flight"));
        if (p.getAllowFlight()) p.setFlying(y.getBoolean("Flying"));
        p.setLevel(y.getInt("Level"));
        p.setExp((float) y.getDouble("Exp"));
        p.setFoodLevel(y.getInt("Food", 20));
        p.setSaturation((float) y.getDouble("Saturation", 5));
        for (String s : y.getStringList("Effects")) {
            String[] a = s.split(":");
            org.bukkit.potion.PotionEffectType t = org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(a[0]));
            if (t != null) p.addPotionEffect(new PotionEffect(t, Integer.parseInt(a[2]), Integer.parseInt(a[1])));
        }
        if (!p.isDead()) p.setHealth(Math.max(1, Math.min(p.getAttribute(Attribute.MAX_HEALTH).getValue(), y.getDouble("Health", 20))));
        if (teleport) {
            // Leave.Spawn (the server spawn) wins over the spot the player walked in from
            // (the owner, 2026-11-13: "when leaving kitpvp you should get moved back to spawn").
            Location l = Arena.toLocation(cfg().getString("Leave.Spawn", ""));
            if (l == null) l = Arena.toLocation(y.getString("Location", ""));
            if (l != null) p.teleport(l);
        }
        f.delete();
    }

    /** A snapshot left behind means the server stopped while the player was inside. Give it back. */
    public void recoverAfterCrash(Player p, UUID profile) {
        if (session(p) != null) return;
        File f = snapshotFile(p.getUniqueId());
        if (!f.exists()) return;
        String saved = YamlConfiguration.loadConfiguration(f).getString("Profile");
        if (saved != null && profile != null && !saved.equals(profile.toString())) return;   // belongs to another profile
        strip(p);
        restoreSnapshot(p, true);
        lang().send(p, "Leave.Recovered");
        plugin.getLogger().info("Restored the pre-KitPvP inventory of " + p.getName() + " after an unclean stop.");
    }

    // ================================================================ lives and kits

    /** Fresh at the arena spawn: no kit, or the default kit. */
    public void startLife(Session s, boolean teleport) {
        Player p = s.player;
        if (s.kit != null) plugin.scripts.fire(s, "unequip", null, p.getLocation(), 0, false, null);
        strip(p);
        s.kit = null; s.tier = 0; s.pickedThisLife = false; s.taggedUntil = 0; s.lastAttacker = null;
        s.damageDealtThisLife = 0; s.damageTakenThisLife = 0;
        s.protectedUntil = System.currentTimeMillis() + (long) (cfg().getDouble("Combat.Spawn-Protection-Seconds") * 1000);
        if (teleport) {
            Location l = s.arena.randomSpawn();
            // In the middle of a death the player still has no health and the server refuses to move them:
            // that left the dead standing where they fell. Try now, and again once the death is over.
            if (l != null && (p.isDead() || !p.teleport(l))) Bukkit.getScheduler().runTask(plugin, () -> {
                if (sessions.get(p.getUniqueId()) != s || !p.isOnline()) return;
                if (!p.isDead()) p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
                p.setFireTicks(0); p.setFallDistance(0);
                p.teleport(l);
            });
        }
        Kit def = kit(cfg().getString("Join.Default-Kit", ""));
        if (def != null && def.enabled && owned(s.data, def) > 0) { equip(s, def, owned(s.data, def), true); s.pickedThisLife = false; }
        else giveLobbyItems(p);
        for (String fx : cfg().getStringList("Combat.Effects-On-Respawn")) {
            PotionEffect e = Scripts.parseEffect(fx);
            if (e != null) p.addPotionEffect(e);
        }
    }

    private void giveLobbyItems(Player p) {
        ConfigurationSection ls = cfg().getConfigurationSection("Join.Lobby-Items");
        if (ls == null) return;
        for (String key : ls.getKeys(false)) {
            ItemStack it = Items.parse(ls.getString(key + ".Item", "NETHER_STAR"));
            if (it == null) continue;
            ItemMeta m = it.getItemMeta();
            m.displayName(lang().text("Lobby-Items." + key + ".Name"));
            m.lore(lang().lore("Lobby-Items." + key + ".Lore"));
            m.getPersistentDataContainer().set(Items.LOBBY, PersistentDataType.STRING, ls.getString(key + ".Action", key));
            it.setItemMeta(m);
            p.getInventory().setItem(ls.getInt(key + ".Slot", 0), it);
        }
    }

    /** The highest tier of a kit this player holds. A kit whose first tier is free is always held. */
    public int owned(PlayerData d, Kit k) {
        int t = d.tiers.getOrDefault(k.id, 0);
        if (t == 0 && !k.tiers.isEmpty() && k.tiers.firstEntry().getValue().cost <= 0) t = k.tiers.firstKey();
        return Math.min(t, k.maxTier());
    }

    /** A player asking for a kit. Returns false with the reason already sent. */
    public boolean select(Player p, Kit k) {
        Session s = session(p);
        if (s == null) { lang().send(p, "General.Not-In-Game"); return false; }
        if (k == null || !k.enabled || k.hidden) { lang().send(p, "Kits.Not-Found"); return false; }
        if (!k.permission.isEmpty() && !p.hasPermission(k.permission)) { lang().send(p, "Kits.No-Permission", "kit", k.name); return false; }
        int tier = owned(s.data, k);
        if (tier <= 0) { lang().send(p, "Kits.Not-Owned", "kit", k.name); return false; }
        // In a PvP duel's kit-choice window any owned kit may be taken, ignoring the kit-change rule.
        if (s.duel != null && !s.duel.started() && s.duel.arena.kits.isEmpty()) { equip(s, k, tier, false); return true; }
        if (s.duel != null) { lang().send(p, "Kits.Not-Now"); return false; }
        if (s.inCombat()) { lang().send(p, "Combat.Command-Blocked"); return false; }
        String mode = cfg().getString("Kits.Change", "once-per-life").toLowerCase(Locale.ROOT);
        boolean safe = s.arena.inSafeZone(p.getLocation());
        if (mode.equals("safe-zone") && !safe && s.pickedThisLife) { lang().send(p, "Kits.Only-In-Safe-Zone"); return false; }
        if (mode.equals("once-per-life") && s.pickedThisLife && !safe) { lang().send(p, "Kits.Once-Per-Life"); return false; }
        if (k.cooldown > 0 && !p.hasPermission("kitpvp.bypass.cooldown")) {
            long left = s.data.cooldowns.getOrDefault(k.id, 0L) + k.cooldown * 1000L - System.currentTimeMillis();
            if (left > 0) { lang().send(p, "Kits.Cooldown", "seconds", (left + 999) / 1000); return false; }
        }
        equip(s, k, tier, false);
        return true;
    }

    public void equip(Session s, Kit k, int tierNo, boolean quiet) {
        Player p = s.player;
        Kit.Tier tier = k.tier(tierNo);
        if (tier == null) return;
        if (s.kit != null) plugin.scripts.fire(s, "unequip", null, p.getLocation(), 0, false, null);
        PlayerInventory inv = p.getInventory();
        inv.clear(); inv.setArmorContents(null); inv.setItemInOffHand(null);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        // "EMPTY" keeps a slot free: Soup-Fill skips it (Ghost's empty hand, the owner 2026-11-13).
        java.util.Set<Integer> keepEmpty = new java.util.HashSet<>();
        for (Map.Entry<Integer, Object> e : tier.items.entrySet()) {
            if (Items.isEmptyMarker(e.getValue())) { keepEmpty.add(e.getKey()); continue; }
            ItemStack it = Items.parse(e.getValue());
            if (it != null) inv.setItem(e.getKey(), it);
        }
        if (k.soupFill && cfg().getBoolean("Soup.Enabled")) {
            ItemStack soup = Items.parse(cfg().getString("Soup.Item", "MUSHROOM_STEW"));
            if (soup != null) for (int i = 0; i < 36; i++) if (inv.getItem(i) == null && !keepEmpty.contains(i)) inv.setItem(i, soup.clone());
        }
        for (String fx : tier.effects) {
            PotionEffect e = Scripts.parseEffect(fx);
            if (e != null) p.addPotionEffect(e); else plugin.getLogger().warning("Kit " + k.id + ": unknown effect " + fx);
        }
        p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
        s.kit = k; s.tier = tierNo; s.pickedThisLife = true;
        s.abilityReady.clear();
        s.data.cooldowns.put(k.id, System.currentTimeMillis());
        s.data.add("uses", 1, k.id);
        plugin.challenges.apply(s);
        if (!quiet) lang().send(p, "Kits.Equipped", "kit", k.tierName(tierNo), "tier", tierNo);
        plugin.scripts.fire(s, "equip", null, p.getLocation(), 0, false, null);
    }

    // ---------------------------------------------------------------- buying and requirements

    private static final Pattern REQ = Pattern.compile("^\\s*(\\S+)\\s*(>=|<=|==|=|>|<)\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");

    /** One requirement line of a tier, checked. Text after " | " is what the player reads. */
    public record Requirement(String text, boolean met, String progress) {}

    public List<Requirement> requirements(Player p, PlayerData d, Kit k, Kit.Tier t) {
        List<Requirement> out = new ArrayList<>();
        for (String line : t.requirements) {
            String expr = line, label = null;
            int bar = line.indexOf('|');
            if (bar >= 0) { expr = line.substring(0, bar); label = line.substring(bar + 1).trim(); }
            expr = expr.trim();
            if (expr.toLowerCase(Locale.ROOT).startsWith("permission ")) {
                String node = expr.substring(11).trim();
                out.add(new Requirement(label != null ? label : lang().raw("Requirements.Permission", "permission", node), p != null && p.hasPermission(node), ""));
                continue;
            }
            Matcher m = REQ.matcher(expr);
            if (!m.matches()) { out.add(new Requirement(line + " ?", false, "")); plugin.getLogger().warning("Kit " + k.id + ": cannot read requirement '" + line + "'"); continue; }
            double have = value(d, k, m.group(1)), want = Double.parseDouble(m.group(3));
            boolean ok = switch (m.group(2)) {
                case ">=" -> have >= want; case "<=" -> have <= want; case ">" -> have > want; case "<" -> have < want;
                default -> have == want;
            };
            String name = m.group(1);
            out.add(new Requirement(label != null ? label : lang().raw("Requirements.Stat", "stat", statName(name), "op", m.group(2), "value", num(want)), ok, num(have) + "/" + num(want)));
        }
        return out;
    }

    /** coins, <stat>, kit.<stat> (this kit), kit:<Kit>.<stat>, tier:<Kit> */
    public double value(PlayerData d, Kit k, String name) {
        String low = name.toLowerCase(Locale.ROOT);
        if (low.equals("coins")) return balance(d);
        if (low.equals("kd")) return d.kd();
        if (low.startsWith("tier:")) { Kit o = kit(name.substring(5)); return o == null ? 0 : owned(d, o); }
        if (low.startsWith("kit.")) return d.kitStat(k.id, low.substring(4));
        if (low.startsWith("kit:")) {
            int dot = name.indexOf('.');
            if (dot < 0) return 0;
            Kit o = kit(name.substring(4, dot));
            return d.kitStat(o == null ? name.substring(4, dot) : o.id, low.substring(dot + 1));
        }
        return d.stat(low);
    }

    public String statName(String stat) {
        String low = stat.toLowerCase(Locale.ROOT);
        String scope = "";
        if (low.startsWith("kit.")) { low = low.substring(4); scope = lang().raw("Stats.With-This-Kit"); }
        else if (low.startsWith("kit:")) { int dot = low.indexOf('.'); if (dot > 0) { scope = lang().raw("Stats.With-Kit", "kit", stat.substring(4, dot)); low = low.substring(dot + 1); } }
        String n = lang().raw("Stats.Names." + low);
        if (n.startsWith("Stats.Names.")) n = low.replace('_', ' ');
        return n + scope;
    }

    /** Buys the kit or its next tier. Returns false with the reason already sent. */
    public boolean buyNext(Player p, Kit k) {
        if (k.hidden) { lang().send(p, "Kits.Not-Found"); return false; }
        PlayerData d = data(p);
        int have = owned(d, k);
        Integer next = k.tiers.higherKey(have);
        if (next == null) { lang().send(p, "Kits.Max-Tier"); return false; }
        Kit.Tier t = k.tiers.get(next);
        if (!k.permission.isEmpty() && !p.hasPermission(k.permission)) { lang().send(p, "Kits.No-Permission", "kit", k.name); return false; }
        for (Requirement r : requirements(p, d, k, t)) if (!r.met()) { lang().send(p, "Kits.Requirement-Missing", "requirement", r.text(), "progress", r.progress()); return false; }
        if (balance(d) < t.cost) { lang().send(p, "Kits.No-Money", "cost", num(t.cost), "coins", num(balance(d))); return false; }
        give(d, -t.cost);
        d.tiers.put(k.id, next); d.dirty = true;
        lang().send(p, have == 0 ? "Kits.Bought" : "Kits.Upgraded", "kit", k.tierName(next), "tier", next, "cost", num(t.cost));
        Session s = session(p);
        if (s != null) plugin.scripts.fire(s, "upgrade", null, p.getLocation(), 0, false, null);
        return true;
    }

    // ================================================================ combat

    public void tag(Session s, Session by) {
        if (!s.inCombat()) lang().send(s.player, "Combat.Tagged");
        s.taggedUntil = System.currentTimeMillis() + cfg().getLong("Combat.Tag-Seconds") * 1000;
        if (by != null) s.lastAttacker = by.player.getUniqueId();
    }

    /** Everything a death means. The caller decides how the body gets back to the spawn. */
    public void died(Session victim, Session killer) {
        Player v = victim.player;
        String vKit = victim.kitId();
        // Bots.Affect-Stats (true since 2026-11-24): a bot counts like a player. The bot itself is never paid:
        // give() skips it, Crates and Challenges skip it, and its record is never saved.
        boolean counts = counts(victim, killer != null && killer != victim ? killer : null);
        if (counts) {
            victim.data.add("deaths", 1, vKit);
            victim.data.set("streak", 0, null);
            double penalty = cfg().getDouble("Economy.Death-Penalty");
            if (penalty > 0) give(victim.data, -penalty);
        }
        victim.taggedUntil = 0;
        if (victim.kit != null) plugin.scripts.fire(victim, "death", killer == null ? null : killer.player, v.getLocation(), 0, false, null);

        if (killer != null && killer != victim && !counts) {
            Player k = killer.player;
            lang().send(k, "Combat.Kill", "victim", v.getName(), "reward", "0", "streak", "0", "hearts", num(Math.ceil(k.getHealth()) / 2));
            lang().send(v, "Combat.Death", "attacker", k.getName(), "hearts", num(Math.ceil(k.getHealth()) / 2));
            if (killer.kit != null) plugin.scripts.fire(killer, "kill", v, v.getLocation(), 0, false, null);
        } else if (killer != null && killer != victim) {
            Player k = killer.player;
            String kKit = killer.kitId();
            PlayerData kd = killer.data;
            kd.add("kills", 1, kKit);
            double streak = kd.stat("streak") + 1;
            kd.set("streak", streak, null);
            if (streak > kd.stat("best_streak")) kd.set("best_streak", streak, null);
            if (kKit != null) {
                double ks = kd.kitStat(kKit, "streak") + 1;
                kd.set("streak", ks, kKit);
                if (ks > kd.kitStat(kKit, "best_streak")) kd.set("best_streak", ks, kKit);
            }
            if (vKit != null) victim.data.set("streak", 0, vKit);
            double max = k.getAttribute(Attribute.MAX_HEALTH).getValue();
            boolean close = k.getHealth() <= cfg().getDouble("Combat.Close-Battle-Hearts") * 2;
            boolean flawless = k.getHealth() >= max - 0.01;
            if (close) kd.add("close_kills", 1, kKit);
            if (flawless) kd.add("flawless_kills", 1, kKit);
            if (vKit != null) kd.add("kills_vs_" + vKit.toLowerCase(Locale.ROOT), 1, kKit);
            double reward = cfg().getDouble("Economy.Kill-Reward") + cfg().getDouble("Economy.Streak-Rewards." + (long) streak, 0);
            give(kd, reward);
            lang().send(k, close ? "Combat.Kill-Close" : "Combat.Kill", "victim", v.getName(), "reward", num(reward), "streak", num(streak), "hearts", num(Math.ceil(k.getHealth()) / 2));
            lang().send(v, "Combat.Death", "attacker", k.getName(), "hearts", num(Math.ceil(k.getHealth()) / 2));
            int every = cfg().getInt("Economy.Streak-Announce-Every");
            if (every > 0 && streak % every == 0) for (Session o : sessions.values()) lang().send(o.player, "Combat.Streak-Broadcast", "player", k.getName(), "streak", num(streak));
            if (every > 0 && streak % every == 0) kd.add("streaks_earned", 1, kKit);
            plugin.crates.onKill(killer, kd.stat("kills"), streak);
            if (killer.kit != null) plugin.scripts.fire(killer, "kill", v, v.getLocation(), 0, false, null);
        } else {
            lang().send(v, "Combat.Death-Alone");
        }
        if (victim.duel != null && !victim.duel.over) endDuel(victim.duel, victim.duel.other(victim), victim);
    }

    /** A death without a death screen: everything a death means, then straight back to the spawn. */
    public void kill(Session s, Session killer) {
        died(s, killer);
        if (sessions.get(s.player.getUniqueId()) == s) startLife(s, true);
        plugin.scripts.fire(s, "respawn", null, s.player.getLocation(), 0, false, null);
    }

    public Session killerOf(Session victim) {
        Player k = victim.player.getKiller();
        Session s = k == null ? null : session(k);
        if (s == null && victim.inCombat() && victim.lastAttacker != null) s = sessions.get(victim.lastAttacker);
        return s == victim ? null : s;
    }

    // ================================================================ duels

    public void duelRequest(Player p, Player target) {
        if (!cfg().getBoolean("Duels.Enabled")) { lang().send(p, "Duel.Disabled"); return; }
        Session a = session(p), b = session(target);
        if (a == null) { lang().send(p, "General.Not-In-Game"); return; }
        if (b == null || target == p) { lang().send(p, "Duel.Not-Available", "player", target == null ? "?" : target.getName()); return; }
        if (a.duel != null || b.duel != null) { lang().send(p, "Duel.Busy"); return; }
        if (a.inCombat()) { lang().send(p, "Combat.Command-Blocked"); return; }
        duelRequests.put(target.getUniqueId(), new Object[]{p.getUniqueId(), System.currentTimeMillis() + cfg().getLong("Duels.Request-Seconds") * 1000});
        lang().send(p, "Duel.Sent", "player", target.getName());
        lang().send(target, "Duel.Received", "player", p.getName(), "seconds", cfg().getLong("Duels.Request-Seconds"));
    }

    /** True when "from" has asked "p" for a duel and the request still stands. */
    public boolean hasDuelRequestFrom(Player p, Player from) {
        Object[] req = duelRequests.get(p.getUniqueId());
        return req != null && from.getUniqueId().equals(req[0]) && (long) req[1] >= System.currentTimeMillis();
    }

    public void duelAccept(Player p) {
        Object[] req = duelRequests.remove(p.getUniqueId());
        Session b = session(p);
        Session a = req == null ? null : sessions.get((UUID) req[0]);
        if (b == null || a == null || (long) req[1] < System.currentTimeMillis()) { lang().send(p, "Duel.No-Request"); return; }
        if (a.duel != null || b.duel != null || a.inCombat() || b.inCombat()) { lang().send(p, "Duel.Busy"); return; }
        Arena free = freeArena("pvp");
        if (free == null) { lang().send(p, "Duel.No-Arena"); lang().send(a.player, "Duel.No-Arena"); return; }
        startDuel(a, b, free);
    }

    /** A free, working DUEL arena for a queue, or null. */
    public Arena freeArena(String queue) {
        List<Arena> pool = new ArrayList<>(arenas.values());
        java.util.Collections.shuffle(pool);
        for (Arena ar : pool) if (ar.type == Arena.Type.DUEL && ar.enabled && !ar.busy && ar.queue.equalsIgnoreCase(queue)
                && ar.spawns.size() >= 2 && ar.spawn(0) != null && ar.spawn(1) != null) return ar;
        return null;
    }

    /** True when at least one enabled DUEL arena serves this queue (free or not). */
    public boolean queueHasArena(String queue) {
        for (Arena ar : arenas.values()) if (ar.type == Arena.Type.DUEL && ar.enabled && ar.queue.equalsIgnoreCase(queue)) return true;
        return false;
    }

    /** Puts two players into a duel on this arena: the arena's Kit if it has one, else their own. */
    public void startDuel(Session a, Session b, Arena free) {
        leaveQueue(a.player, true); leaveQueue(b.player, true);
        duelRequests.remove(a.player.getUniqueId()); duelRequests.remove(b.player.getUniqueId());
        free.busy = true;
        Session.Duel d = new Session.Duel(free, a, b);
        // PvP arenas (no forced Kit:) give a kit-choice window instead of the plain countdown (the owner, 2026-11-14):
        // both start with the starting kit (Duels.Kit) and may pick one of their own until the duel begins.
        boolean choose = free.kits.isEmpty() && cfg().getLong("Duels.Choose-Kit-Seconds", 10) > 0;
        long wait = choose ? cfg().getLong("Duels.Choose-Kit-Seconds", 10) : cfg().getLong("Duels.Countdown-Seconds");
        d.startsAt = System.currentTimeMillis() + wait * 1000;
        for (Session s : new Session[]{a, b}) {
            s.duel = d;
            Kit forced = kit(free.kitFor(s == a ? 0 : 1));
            if (forced != null && !forced.tiers.isEmpty()) equip(s, forced, forced.tiers.firstKey(), true);
            else if (s.kit == null || choose) {
                // Nobody without a kit can hit or be hit (Combat.No-Kit-Protection), and the
                // Challenge stick is only held without one - so a duel from the lobby needs a kit.
                // Duels.Kit at the tier the player owns, or its first tier (the owner, 2026-11-13: "duels doesnt work").
                Kit def = kit(cfg().getString("Duels.Kit", ""));
                if (def == null) def = kit(cfg().getString("Join.Default-Kit", ""));
                if (def != null && def.enabled && !def.tiers.isEmpty()) {
                    int t = owned(s.data, def);
                    equip(s, def, t > 0 ? t : def.tiers.firstKey(), true);
                }
            } else equip(s, s.kit, s.tier, true);
            s.player.teleport(free.spawn(s == a ? 0 : 1));
            s.protectedUntil = d.startsAt;
            lang().send(s.player, "Duel.Start", "player", d.other(s).player.getName(), "arena", free.id, "seconds", wait);
            if (choose) {
                lang().send(s.player, "Duel.Choose-Kit", "seconds", wait);
                Bukkit.getScheduler().runTask(plugin, () -> { if (s.duel == d && !d.started() && s.player.isOnline()) plugin.menus.openKits(s.player, "kits", 1); });
            }
        }
    }

    public void endDuel(Session.Duel d, Session winner, Session loser) {
        if (d.over) return;
        d.over = true;
        d.arena.busy = false;
        boolean counts = counts(winner, loser);
        double reward = counts ? cfg().getDouble("Economy.Duel-Win-Reward") : 0;
        if (counts) {
            winner.data.add("duel_wins", 1, winner.kitId());
            loser.data.add("duel_losses", 1, loser.kitId());
            give(winner.data, reward);
        }
        lang().send(winner.player, "Duel.Won", "player", loser.player.getName(), "reward", num(reward));
        if (counts) plugin.crates.onDuelWin(winner);
        lang().send(loser.player, "Duel.Lost", "player", winner.player.getName());
        winner.duel = null; loser.duel = null;
        Bukkit.getScheduler().runTaskLater(plugin, this::matchQueues, 20L);   // the arena is free again
        if (sessions.containsKey(winner.player.getUniqueId())) {
            plugin.scripts.fire(winner, "duel_win", loser.player, winner.player.getLocation(), 0, false, null);
            Bukkit.getScheduler().runTask(plugin, () -> { if (sessions.get(winner.player.getUniqueId()) == winner) startLife(winner, true); });
        }
    }

    // ================================================================ duel queues

    /** Queue id -> players waiting, in the order they joined (Duels.Queues in config.yml). */
    public final Map<String, java.util.LinkedHashSet<UUID>> queues = new HashMap<>();

    public java.util.List<String> queueIds() {
        ConfigurationSection q = cfg().getConfigurationSection("Duels.Queues");
        return q == null ? java.util.List.of("pvp") : new ArrayList<>(q.getKeys(false));
    }
    public String queueOf(Player p) {
        for (Map.Entry<String, java.util.LinkedHashSet<UUID>> e : queues.entrySet()) if (e.getValue().contains(p.getUniqueId())) return e.getKey();
        return null;
    }
    public int queueSize(String id) { java.util.LinkedHashSet<UUID> q = queues.get(id); return q == null ? 0 : q.size(); }
    public String queueName(String id) { return lang().raw("Menus.Duels.Queues." + id + ".Name"); }

    public void joinQueue(Player p, String id) {
        if (!cfg().getBoolean("Duels.Enabled")) { lang().send(p, "Duel.Disabled"); return; }
        Session s = session(p);
        if (s == null) { lang().send(p, "General.Not-In-Game"); return; }
        id = id.toLowerCase(Locale.ROOT);
        if (!queueIds().contains(id) || !queueHasArena(id)) { lang().send(p, "Queue.No-Arena", "queue", queueName(id)); return; }
        if (s.duel != null) { lang().send(p, "Duel.Busy"); return; }
        if (s.inCombat()) { lang().send(p, "Combat.Command-Blocked"); return; }
        if (id.equals(queueOf(p))) { leaveQueue(p, false); return; }   // clicking your own queue again leaves it
        leaveQueue(p, true);
        queues.computeIfAbsent(id, k -> new java.util.LinkedHashSet<>()).add(p.getUniqueId());
        lang().send(p, "Queue.Joined", "queue", queueName(id), "waiting", queueSize(id));
        matchQueues();
    }

    public void leaveQueue(Player p, boolean quiet) {
        String id = queueOf(p);
        if (id == null) return;
        queues.get(id).remove(p.getUniqueId());
        if (!quiet) lang().send(p, "Queue.Left", "queue", queueName(id));
    }

    /** Pairs the first two players of every queue while it has a free arena. */
    public void matchQueues() {
        for (Map.Entry<String, java.util.LinkedHashSet<UUID>> e : queues.entrySet()) {
            java.util.LinkedHashSet<UUID> q = e.getValue();
            q.removeIf(u -> { Session s = sessions.get(u); return s == null || s.duel != null; });
            while (q.size() >= 2) {
                Arena free = freeArena(e.getKey());
                if (free == null) break;
                java.util.Iterator<UUID> it = q.iterator();
                Session a = sessions.get(it.next()), b = sessions.get(it.next());
                if (a.inCombat() || b.inCombat()) break;   // tried again when the next player joins or a duel ends
                startDuel(a, b, free);
            }
        }
    }

    /** Sumo: water, or falling below the platform, loses. Called on every block change. */
    public void checkDuelFall(Session s, org.bukkit.Location to) {
        Session.Duel d = s.duel;
        if (d == null || d.over || !d.started() || !d.arena.waterLoses) return;
        org.bukkit.block.Block b = to.getBlock();
        boolean wet = b.getType() == Material.WATER || (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged());
        if (wet || to.getY() < d.arena.lowestSpawnY() - d.arena.fallDepth) kill(s, d.other(s));
    }

    // ================================================================ once a second

    public void tick() {
        long now = System.currentTimeMillis();
        duelRequests.values().removeIf(r -> (long) r[1] < now);
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = s.player;
            if (s.taggedUntil > 0) {
                if (now < s.taggedUntil) lang().actionBar(p, "Combat.Action-Bar", "seconds", (s.taggedUntil - now + 999) / 1000);
                else { s.taggedUntil = 0; lang().actionBar(p, "Combat.Untagged"); }
            }
            extraction(s);
            if (s.duel != null && !s.duel.started()) lang().actionBar(p, s.duel.arena.kits.isEmpty() && cfg().getLong("Duels.Choose-Kit-Seconds", 10) > 0
                    ? "Duel.Choose-Countdown" : "Duel.Countdown", "seconds", (s.duel.startsAt - now + 999) / 1000);
            // Sumo water/fall check once a second too: a player standing still in water fires no move event, and neither do bots.
            if (s.duel != null) checkDuelFall(s, p.getLocation());
            if (s.kit != null) plugin.scripts.fire(s, "tick", null, p.getLocation(), 0, false, null);
            plugin.challenges.tick(s);
        }
        if (!cfg().getBoolean("Combat.Hunger"))
            for (String w : arenaWorlds) {
                org.bukkit.World world = Bukkit.getWorld(w);
                if (world != null) for (Player p : world.getPlayers()) if (p.getFoodLevel() < foodLevel()) p.setFoodLevel(foodLevel());
            }
    }

    /** Extraction point (the owner, 2026-11-14): stand on Extraction.Block for Extraction.Seconds, out of combat,
     *  to go back to the spawn and pick a new kit without it counting as a death. Called once a second. */
    private void extraction(Session s) {
        Material ex = Material.matchMaterial(cfg().getString("Extraction.Block", "END_PORTAL_FRAME"));
        int need = cfg().getInt("Extraction.Seconds", 5);
        Player p = s.player;
        boolean on = ex != null && need > 0 && s.kit != null && s.duel == null
                && (p.getLocation().getBlock().getType() == ex || p.getLocation().clone().subtract(0, 0.2, 0).getBlock().getType() == ex);
        if (!on) { s.extractSeconds = 0; return; }
        if (s.inCombat()) { s.extractSeconds = 0; lang().actionBar(p, "Extraction.In-Combat"); return; }
        if (++s.extractSeconds >= need) {
            s.extractSeconds = 0;
            startLife(s, true);
            lang().send(p, "Extraction.Done");
        } else lang().actionBar(p, "Extraction.Countdown", "seconds", need - s.extractSeconds);
    }

    /** The best players by one statistic (kit = null for overall). */
    public List<PlayerData> top(String stat, String kit, int n) {
        List<PlayerData> l = new ArrayList<>(data.values());
        l.removeIf(d -> d.bot);
        Comparator<PlayerData> c = Comparator.comparingDouble(d -> stat.equals("coins") ? balance(d) : stat.equals("kd") ? d.kd() : kit == null ? d.stat(stat) : d.kitStat(kit, stat));
        l.sort(c.reversed());
        return l.subList(0, Math.min(n, l.size()));
    }

    public boolean isSoup(ItemStack it) {
        Material m = Material.matchMaterial(cfg().getString("Soup.Item", "MUSHROOM_STEW"));
        return it != null && m != null && it.getType() == m;
    }
}
