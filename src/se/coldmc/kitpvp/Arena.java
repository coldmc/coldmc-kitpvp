package se.coldmc.kitpvp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** A place to fight. FFA arenas are joined; DUEL arenas host one 1v1 at a time. */
public final class Arena {
    public enum Type { FFA, DUEL }
    public final String id;
    public Type type = Type.FFA;
    public boolean enabled = true;
    public double safeRadius = 0;
    /** "world, x, y, z, yaw, pitch" - kept as text so a world that loads late still resolves. */
    public final List<String> spawns = new ArrayList<>();
    public boolean busy;   // a duel is running here
    /** DUEL only: which queue this arena serves (Duels.Queues key). Challenge duels use "pvp" arenas. */
    public String queue = "pvp";
    /** DUEL only: the kit each spawn's player is given instead of their own (one entry = both). Empty = their own kit. */
    public final List<String> kits = new ArrayList<>();
    /** Sumo: touching water, or falling Fall-Depth below the spawns, loses the duel. */
    public boolean waterLoses;
    public double fallDepth = 6;
    /** Sumo: hits push but do no damage. */
    public boolean noDamage;

    public Arena(String id) { this.id = id; }

    public static String toText(Location l) {
        return l.getWorld().getName() + ", " + r(l.getX()) + ", " + r(l.getY()) + ", " + r(l.getZ()) + ", " + r(l.getYaw()) + ", " + r(l.getPitch());
    }
    private static String r(double d) { return String.valueOf(Math.round(d * 100) / 100.0); }

    public static Location toLocation(String s) {
        try {
            String[] p = s.split("\\s*,\\s*");
            World w = Bukkit.getWorld(p[0]);
            if (w == null) return null;
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    p.length > 4 ? Float.parseFloat(p[4]) : 0, p.length > 5 ? Float.parseFloat(p[5]) : 0);
        } catch (Exception e) { return null; }
    }

    public Location spawn(int i) { return spawns.isEmpty() ? null : toLocation(spawns.get(Math.floorMod(i, spawns.size()))); }
    public Location randomSpawn() { return spawns.isEmpty() ? null : spawn(ThreadLocalRandom.current().nextInt(spawns.size())); }
    public String kitFor(int spawnIndex) { return kits.isEmpty() ? null : kits.get(Math.min(spawnIndex, kits.size() - 1)); }
    public double lowestSpawnY() {
        double y = Double.MAX_VALUE;
        for (String s : spawns) try { y = Math.min(y, Double.parseDouble(s.split("\\s*,\\s*")[2])); } catch (Exception ignored) {}
        return y == Double.MAX_VALUE ? -64 : y;
    }
    public String worldName() { return spawns.isEmpty() ? null : spawns.get(0).split("\\s*,\\s*")[0]; }

    public boolean inSafeZone(Location l) {
        if (safeRadius <= 0) return false;
        for (String s : spawns) {
            Location sp = toLocation(s);
            if (sp != null && sp.getWorld().equals(l.getWorld()) && sp.distanceSquared(l) <= safeRadius * safeRadius) return true;
        }
        return false;
    }
}
