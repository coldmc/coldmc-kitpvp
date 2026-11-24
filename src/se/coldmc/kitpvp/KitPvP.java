package se.coldmc.kitpvp;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** KitPvP for ColdMC. Kits are files, abilities are Skript functions, messages are lang.yml. */
public final class KitPvP extends JavaPlugin {
    public Lang lang;
    public Game game;
    public Scripts scripts;
    public Menus menus;
    public Crates crates;
    public Challenges challenges;
    public Extras extras;
    public Natives natives;
    /** Null when MythicLib is not installed. */
    MmoStats mmo;
    private boolean started;

    @Override
    public void onEnable() {
        Items.init(this);
        lang = new Lang();
        game = new Game(this);
        scripts = new Scripts(this);
        natives = new Natives(this);
        getServer().getPluginManager().registerEvents(natives, this);
        crates = new Crates(this);
        challenges = new Challenges(this);
        reloadEverything();
        menus = new Menus(this);
        getServer().getPluginManager().registerEvents(new Listeners(this), this);
        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getPluginManager().registerEvents(new Extras(this), this);
        Commands c = new Commands(this);
        getCommand("kitpvp").setExecutor(c);
        getCommand("kitpvp").setTabCompleter(c);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) new Papi(this).register();
        if (Bukkit.getPluginManager().isPluginEnabled("MMOProfiles")) getServer().getPluginManager().registerEvents(new ProfilesHook(this), this);
        installAbilityScript();
        started = false;
        Bukkit.getScheduler().runTaskLater(this, () -> { started = true; game.validateKits(); }, 100L);   // first ticks = server fully up
        Bukkit.getScheduler().runTaskTimer(this, game::tick, 20L, 20L);
        if (Bukkit.getPluginManager().isPluginEnabled("MythicLib")) {
            mmo = new MmoStats(this);
            // MMOCore and MMOItems put stats back whenever they recalculate: take them off again every tick
            Bukkit.getScheduler().runTaskTimer(this, () -> { for (Session s : game.sessions.values()) mmo.sweep(s.player); }, 1L, 1L);
        }
        long auto = Math.max(1, getConfig().getLong("Storage.Autosave-Minutes")) * 1200L;
        Bukkit.getScheduler().runTaskTimer(this, () -> game.saveDirty(false), auto, auto);
        getLogger().info(game.kits.size() + " kits, " + game.arenas.size() + " arenas, " + scripts.abilities.size() + " abilities.");
    }

    /** config.yml, lang.yml, abilities.yml, arenas.yml and every kit file. Options a newer jar adds are filled in. */
    public void reloadEverything() {
        saveDefaultConfig();
        reloadConfig();
        YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8));
        boolean added = false;
        for (String k : def.getKeys(true)) if (!def.isConfigurationSection(k) && !getConfig().contains(k)) { getConfig().set(k, def.get(k)); added = true; }
        if (added) saveConfig();
        lang.load(this);
        scripts.load();
        game.loadAll();
        crates.load();
        challenges.load();
        if (started) game.validateKits();
    }

    /** The old server's abilities are built in (Natives); this file holds the helpers and examples for new ones. The file is put in Skript's folder once; after that it is the server's to edit. */
    private void installAbilityScript() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Skript")) { getLogger().warning("Skript is not installed: abilities that run Skript functions will do nothing."); return; }
        java.io.File f = new java.io.File(getDataFolder().getParentFile(), "Skript/scripts/kitpvp_abilities.sk");
        if (f.exists()) return;
        try (java.io.InputStream in = getResource("kitpvp_abilities.sk")) {
            f.getParentFile().mkdirs();
            java.nio.file.Files.copy(in, f.toPath());
            getLogger().info("Wrote " + f + " - the stock abilities. Edit it freely; /sk reload kitpvp_abilities applies it.");
        } catch (Exception e) { getLogger().warning("Could not write kitpvp_abilities.sk: " + e); }
    }

    @Override
    public void onDisable() {
        if (game == null) return;
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) if (game.session(p) != null) game.leave(p, true, true);
        game.saveDirty(true);
        natives.cleanup();
    }
}
