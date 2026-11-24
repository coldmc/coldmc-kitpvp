package se.coldmc.kitpvp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import io.lumine.mythic.lib.api.player.MMOPlayerData;
import io.lumine.mythic.lib.api.stat.StatInstance;
import io.lumine.mythic.lib.api.stat.modifier.StatModifier;
import io.lumine.mythic.lib.api.stat.modifier.TemporaryStatModifier;

/**
 * Kit PvP is fought on kit stats alone (the owner, 2026-11-24: "make so mmocore stats gets cleared out on
 * kitpvp join and gets back correctly when leaving or quitting"). Every MMO stat a player carries -
 * MMOCore class, attributes, skill tree and professions, MMOItems gear and accessories, buffs - is a
 * MythicLib StatModifier, and MythicLib turns their totals into the single vanilla attribute modifier
 * {@code mythiclib:main}. With no modifiers every stat sits at its default (attack damage 1, attack
 * speed 4, max health 20, speed 0.1, everything else 0): vanilla.
 *
 * <p>So on join every modifier is taken off and held here, and it is taken off again every tick while
 * the player is in Kit PvP, because MMOCore and MMOItems re-register theirs whenever they recalculate.
 * A re-registration replaces what is held for that stat and key, so the newest version comes back.
 * On leave (also on quit at LOWEST and on a profile switch, both of which end the session first) the
 * held modifiers are put back and MMOCore and MMOItems are asked to recalculate on top.
 *
 * <p>Temporary modifiers (timed buffs) are removed and not held: they would otherwise come back after
 * their time ran out and never expire. Nothing is written to disk: after a crash MMOCore and MMOItems
 * rebuild every modifier at the next login, as they do at every login.
 */
final class MmoStats {
    private static final class Held {
        final UUID profile;
        /** stat -> key -> the modifiers last seen under that key */
        final Map<String, Map<String, List<StatModifier>>> mods = new HashMap<>();
        Held(UUID profile) { this.profile = profile; }
    }

    private final KitPvP plugin;
    private final Map<UUID, Held> held = new HashMap<>();
    private final boolean available;

    MmoStats(KitPvP plugin) {
        this.plugin = plugin;
        boolean ok;
        try { Class.forName("io.lumine.mythic.lib.api.player.MMOPlayerData"); ok = Bukkit.getPluginManager().isPluginEnabled("MythicLib"); }
        catch (Throwable t) { ok = false; }
        available = ok;
    }

    private boolean on() { return available && plugin.getConfig().getBoolean("Combat.Clear-MMO-Stats", true); }

    /** On join: take everything off. */
    void clear(Player p) {
        if (!on() || !MMOPlayerData.has(p)) return;
        MMOPlayerData d = MMOPlayerData.get(p);
        held.put(p.getUniqueId(), new Held(d.getProfileId()));
        sweep(p);
    }

    /** Every tick while in Kit PvP: whatever was registered since is taken off too, and held. */
    void sweep(Player p) {
        Held h = held.get(p.getUniqueId());
        if (h == null || !MMOPlayerData.has(p)) return;
        MMOPlayerData d = MMOPlayerData.get(p);
        if (h.profile != null && !h.profile.equals(d.getProfileId())) return; // another profile: not ours to touch
        for (StatInstance inst : d.getStatMap().getInstances()) {
            if (inst.isEmpty()) continue;
            Map<String, List<StatModifier>> fresh = null;
            for (StatModifier m : new ArrayList<>(inst.getModifiers())) {
                inst.removeModifier(m.getUniqueId());
                if (m instanceof TemporaryStatModifier) continue;
                if (fresh == null) fresh = new HashMap<>();
                fresh.computeIfAbsent(m.getKey(), k -> new ArrayList<>()).add(m);
            }
            // everything one owner registered under a key arrives together: it replaces what was held for it
            if (fresh != null) h.mods.computeIfAbsent(inst.getStat(), k -> new HashMap<>()).putAll(fresh);
        }
    }

    /** On leave or quit: put it all back, then let MMOCore and MMOItems recalculate. Call before health is restored. */
    void restore(Player p) {
        Held h = held.remove(p.getUniqueId());
        if (h == null || !available || !MMOPlayerData.has(p)) return;
        MMOPlayerData d = MMOPlayerData.get(p);
        if (h.profile != null && !h.profile.equals(d.getProfileId())) return;
        for (Map.Entry<String, Map<String, List<StatModifier>>> st : h.mods.entrySet()) {
            StatInstance inst = d.getStatMap().getInstance(st.getKey());
            Set<String> present = new HashSet<>();
            for (StatModifier m : inst.getModifiers()) present.add(m.getKey());
            for (Map.Entry<String, List<StatModifier>> k : st.getValue().entrySet()) {
                if (present.contains(k.getKey())) continue; // its owner already put a newer one back
                for (StatModifier m : k.getValue()) inst.registerModifier(m);
            }
        }
        refresh(p);
    }

    boolean holding(Player p) { return held.containsKey(p.getUniqueId()); }

    /** MMOCore rebuilds class, attribute and profession stats; MMOItems re-reads the gear just given back. */
    private void refresh(Player p) {
        try {
            Class<?> pd = Class.forName("net.Indyuce.mmocore.api.player.PlayerData");
            Object data = pd.getMethod("get", org.bukkit.OfflinePlayer.class).invoke(null, p);
            Object stats = pd.getMethod("getStats").invoke(data);
            stats.getClass().getMethod("updateStats").invoke(stats);
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) { plugin.getLogger().warning("MMOCore stat refresh for " + p.getName() + ": " + t); }
        try {
            Class<?> pd = Class.forName("net.Indyuce.mmoitems.api.player.PlayerData");
            Object data = pd.getMethod("get", org.bukkit.OfflinePlayer.class).invoke(null, p);
            pd.getMethod("updateInventory").invoke(data);
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) { plugin.getLogger().warning("MMOItems stat refresh for " + p.getName() + ": " + t); }
    }
}
