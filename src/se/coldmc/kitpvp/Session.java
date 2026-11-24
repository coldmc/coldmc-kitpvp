package se.coldmc.kitpvp;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A player who is inside KitPvP right now. */
public final class Session {
    public final Player player;
    public final PlayerData data;
    public Arena arena;          // the FFA arena they joined
    public Kit kit;
    public int tier;
    public boolean pickedThisLife;
    public long taggedUntil, protectedUntil;
    public UUID lastAttacker;
    public Duel duel;
    public final Map<String, Long> abilityReady = new HashMap<>();
    public double damageDealtThisLife, damageTakenThisLife;
    public int extractSeconds;   // seconds stood on the extraction block

    public Session(Player player, PlayerData data, Arena arena) { this.player = player; this.data = data; this.arena = arena; }

    public boolean inCombat() { return System.currentTimeMillis() < taggedUntil; }
    public boolean spawnProtected() { return System.currentTimeMillis() < protectedUntil; }
    public String kitId() { return kit == null ? null : kit.id; }

    /** One running 1v1. */
    public static final class Duel {
        public final Arena arena;
        public final Session a, b;
        public long startsAt;
        public boolean over;
        public Duel(Arena arena, Session a, Session b) { this.arena = arena; this.a = a; this.b = b; }
        public Session other(Session s) { return s == a ? b : a; }
        public boolean started() { return System.currentTimeMillis() >= startsAt; }
    }
}
