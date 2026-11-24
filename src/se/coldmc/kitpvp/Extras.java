package se.coldmc.kitpvp;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Things that belong to the arena itself: launch pads (sponge) and soup refill stations (signs or marked blocks). */
public final class Extras implements Listener {
    private final KitPvP plugin;
    private final Map<UUID, Long> launchedAt = new HashMap<>();
    private final Map<UUID, Long> soupReadyAt = new HashMap<>();
    private final Set<String> stations = new HashSet<>();

    public Extras(KitPvP plugin) { this.plugin = plugin; plugin.extras = this; loadStations(); }

    // ---------------------------------------------------------------- launch pads

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo(), from = e.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ()) return;
        Player p = e.getPlayer();
        Session ss = plugin.game.session(p);
        if (ss != null) plugin.game.checkDuelFall(ss, to);
        if (ss == null || !plugin.getConfig().getBoolean("Launch-Pads.Enabled", true)) return;
        Block under = to.getBlock().getRelative(0, -1, 0);
        if (under.getType().isAir()) return;
        List<String> blocks = plugin.getConfig().getStringList("Launch-Pads.Blocks");
        if (blocks.stream().noneMatch(b -> b.equalsIgnoreCase(under.getType().name()))) return;
        long now = System.currentTimeMillis();
        if (now - launchedAt.getOrDefault(p.getUniqueId(), 0L) < 400) return;      // one launch per step, not one per tick
        launchedAt.put(p.getUniqueId(), now);
        Vector v = p.getLocation().getDirection().setY(0);
        if (v.lengthSquared() > 0) v.normalize().multiply(plugin.getConfig().getDouble("Launch-Pads.Forward", 0));
        v.setY(plugin.getConfig().getDouble("Launch-Pads.Up", 1.5));
        p.setVelocity(v);
        p.setFallDistance(0);
        String sound = plugin.getConfig().getString("Launch-Pads.Sound", "");
        if (!sound.isEmpty()) p.getWorld().playSound(p.getLocation(), sound.toLowerCase(Locale.ROOT),
                (float) plugin.getConfig().getDouble("Launch-Pads.Volume", 3), (float) plugin.getConfig().getDouble("Launch-Pads.Pitch", 1));
    }

    /** The landing after a launch does not hurt (Launch-Pads.No-Fall-Damage-Seconds). Runs before kit "fall" scripts see it. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        Long at = launchedAt.get(p.getUniqueId());
        double secs = plugin.getConfig().getDouble("Launch-Pads.No-Fall-Damage-Seconds", 8);
        if (at != null && plugin.game.session(p) != null && System.currentTimeMillis() - at < secs * 1000) {
            e.setCancelled(true);
            launchedAt.put(p.getUniqueId(), 0L);
        }
    }

    // ---------------------------------------------------------------- soup stations

    private static String key(Block b) { return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ(); }
    private File stationFile() { return new File(plugin.getDataFolder(), "stations.yml"); }

    public void loadStations() {
        stations.clear();
        stations.addAll(YamlConfiguration.loadConfiguration(stationFile()).getStringList("Soup-Stations"));
    }

    /** Marks or unmarks a block as a soup station. Returns true when it is one afterwards. */
    public boolean toggleStation(Block b) {
        String k = key(b);
        boolean now = !stations.remove(k);
        if (now) stations.add(k);
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Blocks that hand out soup when right-clicked: world,x,y,z", "Made with /kp admin station while looking at the block. Signs need no entry: see Soup-Stations in config.yml."));
        y.set("Soup-Stations", new java.util.ArrayList<>(new java.util.TreeSet<>(stations)));
        try { y.save(stationFile()); } catch (Exception e) { plugin.getLogger().warning("stations.yml: " + e); }
        return now;
    }

    private boolean isStation(Block b) {
        if (stations.contains(key(b))) return true;
        if (!(b.getState() instanceof Sign sign)) return false;
        List<String> want = plugin.getConfig().getStringList("Soup-Stations.Sign-Lines");
        for (Side side : Side.values()) for (var line : sign.getSide(side).lines()) {
            String text = PlainTextComponentSerializer.plainText().serialize(line).trim();
            if (!text.isEmpty() && want.stream().anyMatch(w -> w.equalsIgnoreCase(text))) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Session s = plugin.game.session(p);
        if (s == null || !plugin.getConfig().getBoolean("Soup-Stations.Enabled", true) || !isStation(e.getClickedBlock())) return;
        e.setCancelled(true);
        if (s.kit == null) return;
        long left = soupReadyAt.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
        if (left > 0) { plugin.lang.send(p, "Soup-Station.Cooldown", "seconds", (left + 999) / 1000); return; }
        Material soup = Material.matchMaterial(plugin.getConfig().getString("Soup.Item", "MUSHROOM_STEW"));
        if (soup == null) return;
        // The cooldown starts only if soup actually left the station.
        plugin.menus.openSoupStation(p, soup, taken -> {
            if (taken <= 0) return;
            soupReadyAt.put(p.getUniqueId(), System.currentTimeMillis() + (long) (plugin.getConfig().getDouble("Soup-Stations.Cooldown-Seconds", 10) * 1000));
            s.data.add("soups_refilled", taken, s.kitId());
        });
    }
}
