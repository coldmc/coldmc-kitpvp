package se.coldmc.kitpvp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs what kits attach to events. A kit, a tier or an ability lists actions per trigger:
 *   skript:<function>   (or just the function name) - a Skript function, see abilities.yml
 *   console:<command>   player:<command>   message:<text>   target-message:<text>
 *   effect:<type>:<level>:<seconds>   target-effect:...   sound:<name>   heal:<hearts>
 *   damage:<n> | damage:+<n> | damage:*<n>   cancel   allow   chance:<percent>
 */
public final class Scripts {
    public static final String M_DAMAGE = "kitpvp_damage", M_CANCEL = "kitpvp_cancel", M_TARGET = "kitpvp_target",
            M_TRIGGER = "kitpvp_trigger", M_TIER = "kitpvp_tier", M_KIT = "kitpvp_kit";

    public static final class Ability {
        public String id, name, trigger;
        public Material item;
        public String projectile;
        public double cooldown;
        public Boolean cancel;
        public boolean silent;
        public List<String> run = new ArrayList<>();
        public List<String> description = new ArrayList<>();
        public ConfigurationSection settings;      // the ability's own section: the built-in abilities read their numbers here
    }

    /** What the scripts decided about the event that triggered them. */
    public static final class Result {
        public boolean cancel;
        public double damage;
        public boolean ran;
    }

    private final KitPvP plugin;
    public final Map<String, Ability> abilities = new LinkedHashMap<>();
    private final Set<String> warned = new LinkedHashSet<>();

    public Scripts(KitPvP plugin) { this.plugin = plugin; }

    public void load() {
        abilities.clear(); warned.clear();
        File f = new File(plugin.getDataFolder(), "abilities.yml");
        if (!f.exists()) plugin.saveResource("abilities.yml", false);
        ConfigurationSection root = YamlConfiguration.loadConfiguration(f).getConfigurationSection("Abilities");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Ability a = new Ability();
            a.id = id;
            a.name = s.getString("Name", id);
            a.trigger = s.getString("Trigger", "right_click").toLowerCase(Locale.ROOT).replace('-', '_');
            String it = s.getString("Item", "");
            if (!it.isEmpty()) { a.item = Material.matchMaterial(it); if (a.item == null) plugin.getLogger().warning("abilities.yml " + id + ": unknown Item " + it); }
            a.projectile = s.getString("Projectile", "").toUpperCase(Locale.ROOT);
            a.cooldown = s.getDouble("Cooldown", 0);
            a.cancel = s.contains("Cancel") ? s.getBoolean("Cancel") : null;
            a.silent = s.getBoolean("Silent", false);
            a.description = s.getStringList("Description");
            a.settings = s;
            // No Run: one of the built-in abilities if the id is one, otherwise the Skript function kpability_<id>.
            a.run = s.isList("Run") ? s.getStringList("Run") : new ArrayList<>(List.of(Natives.has(id) ? "native:" + id : "skript:" + defaultFunction(id)));
            abilities.put(id.toLowerCase(Locale.ROOT), a);
        }
    }

    /** kpability_<id>, lower case, anything that is not a letter or digit becomes _. */
    public static String defaultFunction(String id) { return "kpability_" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_"); }

    public Ability ability(String id) { return id == null ? null : abilities.get(id.toLowerCase(Locale.ROOT)); }

    /** Registers or replaces an ability at runtime (/kp admin ability ..., usually from a Skript "on load"). */
    public void register(Ability a) { abilities.put(a.id.toLowerCase(Locale.ROOT), a); }

    public Result fire(Session s, String trigger, Entity target, Location loc, double damage, boolean cancelByDefault, String projectileType) {
        Result r = new Result();
        r.cancel = cancelByDefault; r.damage = damage;
        if (s == null || s.kit == null) return r;
        Player p = s.player;
        Kit.Tier tier = s.kit.tier(s.tier);
        List<List<String>> runs = new ArrayList<>();
        String key = "on-" + trigger.replace('_', '-');
        if (s.kit.scripts.containsKey(key)) runs.add(s.kit.scripts.get(key));
        if (tier != null && tier.scripts.containsKey(key)) runs.add(tier.scripts.get(key));

        Set<String> ids = new LinkedHashSet<>(s.kit.abilities);
        if (tier != null) ids.addAll(tier.abilities);
        ItemStack hand = p.getInventory().getItemInMainHand();
        String bound = Items.abilityOf(hand);
        long now = System.currentTimeMillis();
        List<Ability> ready = new ArrayList<>();
        for (String id : ids) addIfReady(s, ability(id), trigger, hand, projectileType, false, now, ready, id);
        if (bound != null && !ids.contains(bound)) addIfReady(s, ability(bound), trigger, hand, projectileType, true, now, ready, bound);
        if (runs.isEmpty() && ready.isEmpty()) return r;

        p.setMetadata(M_DAMAGE, new FixedMetadataValue(plugin, damage));
        p.setMetadata(M_CANCEL, new FixedMetadataValue(plugin, cancelByDefault));
        p.setMetadata(M_TRIGGER, new FixedMetadataValue(plugin, trigger));
        p.setMetadata(M_TIER, new FixedMetadataValue(plugin, s.tier));
        p.setMetadata(M_KIT, new FixedMetadataValue(plugin, s.kit.id));
        if (target != null) p.setMetadata(M_TARGET, new FixedMetadataValue(plugin, target));
        try {
            for (List<String> actions : runs) run(s, actions, target, loc, trigger, null);
            for (Ability a : ready) {
                if (a.cancel != null) p.setMetadata(M_CANCEL, new FixedMetadataValue(plugin, a.cancel));
                if (a.cooldown > 0) s.abilityReady.put(a.id, now + (long) (a.cooldown * 1000));
                plugin.natives.countLater = false;
                run(s, a.run, target, loc, trigger, a);
                // Only a use that did something counts (the owner, 2026-11-14: no farming achievements by spamming):
                // a failed use hands its cooldown back, and a shot is counted when it hits.
                if (a.cooldown > 0 && s.abilityReady.containsKey(a.id) && !plugin.natives.countLater) s.data.add("abilities_used", 1, s.kitId());
            }
            r.ran = true;
            for (MetadataValue v : p.getMetadata(M_DAMAGE)) if (v.getOwningPlugin() == plugin) r.damage = v.asDouble();
            for (MetadataValue v : p.getMetadata(M_CANCEL)) if (v.getOwningPlugin() == plugin) r.cancel = v.asBoolean();
        } finally {
            for (String m : new String[]{M_DAMAGE, M_CANCEL, M_TRIGGER, M_TIER, M_KIT, M_TARGET}) p.removeMetadata(m, plugin);
        }
        return r;
    }

    /** Runs one ability now, whatever its trigger, item and cooldown (/kp admin use). */
    public boolean use(Session s, String id, Entity target) {
        Ability a = ability(id);
        if (a == null || s.kit == null) return false;
        run(s, a.run, target, target != null ? target.getLocation() : s.player.getLocation(), a.trigger, a);
        return true;
    }

    private void addIfReady(Session s, Ability a, String trigger, ItemStack hand, String projectileType, boolean boundToItem,
                            long now, List<Ability> out, String id) {
        if (a == null) { if (warned.add(id)) plugin.getLogger().warning("Kit " + s.kit.id + " names ability '" + id + "' which is not in abilities.yml"); return; }
        if (!a.trigger.equals(trigger)) return;
        if (a.item != null && !boundToItem && projectileType == null && hand.getType() != a.item) return;
        if (!a.projectile.isEmpty() && projectileType != null && !a.projectile.equals(projectileType)) return;
        long readyAt = s.abilityReady.getOrDefault(a.id, 0L);
        if (now < readyAt) {
            if (!a.silent) plugin.lang.actionBar(s.player, "Ability.Cooldown", "ability", a.name, "seconds", String.valueOf((readyAt - now + 999) / 1000));
            return;
        }
        out.add(a);
    }

    private void run(Session s, List<String> actions, Entity target, Location loc, String trigger, Ability ability) {
        Player p = s.player;
        for (String raw : actions) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String kind = "skript", arg = line;
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).matches("[a-zA-Z-]+")) { kind = line.substring(0, c).toLowerCase(Locale.ROOT); arg = line.substring(c + 1).trim(); }
            else if (line.equalsIgnoreCase("cancel") || line.equalsIgnoreCase("allow")) { kind = line.toLowerCase(Locale.ROOT); arg = ""; }
            arg = arg.replace("%player%", p.getName()).replace("%target%", target == null ? p.getName() : target.getName())
                    .replace("%kit%", s.kit.id).replace("%tier%", String.valueOf(s.tier)).replace("%arena%", s.arena.id);
            try {
                switch (kind) {
                    case "skript" -> {
                        if (!SkriptBridge.call(plugin, arg, p, target, loc, s, trigger, ability == null ? "" : ability.id) && warned.add("fn:" + arg))
                            plugin.getLogger().warning("Skript function '" + arg + "' was not found (kit " + s.kit.id + ", trigger " + trigger + ")");
                    }
                    case "native" -> {
                        // nothing happened (no target ...): no cooldown, and the rest of the list is skipped
                        if (!plugin.natives.run(arg, s, ability, target, loc)) { if (ability != null) s.abilityReady.remove(ability.id); return; }
                    }
                    case "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), arg);
                    case "player" -> p.performCommand(arg);
                    case "message" -> p.sendMessage(Lang.color(arg));
                    case "target-message" -> { if (target != null) target.sendMessage(Lang.color(arg)); }
                    case "effect" -> effect(p, arg);
                    case "target-effect" -> { if (target instanceof LivingEntity le) effect(le, arg); }
                    case "sound" -> {
                        Sound snd = Registry.SOUNDS.get(NamespacedKey.fromString(arg.toLowerCase(Locale.ROOT)));
                        if (snd != null) p.getWorld().playSound(p.getLocation(), snd, 1f, 1f);
                    }
                    case "heal" -> p.setHealth(Math.min(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), p.getHealth() + Double.parseDouble(arg) * 2));
                    case "cancel" -> p.setMetadata(M_CANCEL, new FixedMetadataValue(plugin, true));
                    case "allow" -> p.setMetadata(M_CANCEL, new FixedMetadataValue(plugin, false));
                    case "damage" -> {
                        double cur = p.getMetadata(M_DAMAGE).isEmpty() ? 0 : p.getMetadata(M_DAMAGE).get(0).asDouble();
                        double n = Double.parseDouble(arg.replaceFirst("^[+*]", ""));
                        p.setMetadata(M_DAMAGE, new FixedMetadataValue(plugin, arg.startsWith("+") ? cur + n : arg.startsWith("*") ? cur * n : n));
                    }
                    case "chance" -> { if (ThreadLocalRandom.current().nextDouble(100) >= Double.parseDouble(arg.replace("%", ""))) return; }
                    default -> { if (warned.add("kind:" + kind)) plugin.getLogger().warning("Unknown script action '" + raw + "'"); }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Script action '" + raw + "' failed: " + e);
            }
        }
    }

    /** "speed:2:10" = Speed II for 10 seconds. No seconds = for as long as the kit is worn. */
    public static PotionEffect parseEffect(String spec) {
        String[] p = spec.trim().split("\\s*:\\s*");
        PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(p[0].toLowerCase(Locale.ROOT)));
        if (t == null) return null;
        int amp = Math.max(0, (p.length > 1 ? Integer.parseInt(p[1]) : 1) - 1);
        int dur = p.length > 2 ? (int) (Double.parseDouble(p[2]) * 20) : PotionEffect.INFINITE_DURATION;
        return new PotionEffect(t, dur, amp, true, false, true);
    }
    private static void effect(LivingEntity le, String spec) { PotionEffect e = parseEffect(spec); if (e != null) le.addPotionEffect(e); }
}
