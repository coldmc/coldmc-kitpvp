package se.coldmc.kitpvp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Calls a Skript function by name. The function chooses what it wants by its parameter TYPES:
 *   player  = the kit's wearer            entity / living entity = the target (the wearer if none)
 *   number  = 1st: tier, 2nd: damage      text = 1st: kit id, 2nd: trigger, 3rd: ability id
 *   location = where it happened
 * So "function kpability_thor(p: player)" and "function x(p: player, victim: entity, tier: number)" both work.
 */
public final class SkriptBridge {
    private SkriptBridge() {}

    public static boolean call(KitPvP plugin, String fn, Player p, Entity target, Location loc, Session s, String trigger, String abilityId) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Skript")) return false;
        try { return Impl.call(fn, p, target, loc, s, trigger, abilityId); }
        catch (Throwable t) { plugin.getLogger().warning("Skript function " + fn + ": " + t); return true; }
    }

    private static final class Impl {
        static boolean call(String fn, Player p, Entity target, Location loc, Session s, String trigger, String abilityId) {
            ch.njol.skript.lang.function.Function<?> f = ch.njol.skript.lang.function.Functions.getGlobalFunction(fn);
            if (f == null) return false;
            ch.njol.skript.lang.function.Parameter<?>[] ps = f.getParameters();
            Object[][] args = new Object[ps.length][];
            int players = 0, numbers = 0, texts = 0;
            double damage = p.getMetadata(Scripts.M_DAMAGE).isEmpty() ? 0 : p.getMetadata(Scripts.M_DAMAGE).get(0).asDouble();
            for (int i = 0; i < ps.length; i++) {
                Class<?> c = ps[i].getType().getC();
                Object v = null;
                if (c == Player.class) v = players++ == 0 ? p : (target instanceof Player ? target : null);
                else if (c == Entity.class || c == org.bukkit.entity.LivingEntity.class) v = target != null ? target : p;
                else if (c != Object.class && c.isAssignableFrom(Player.class)) v = players++ == 0 ? p : target;
                else if (Number.class.isAssignableFrom(c) || c == Number.class) v = numbers++ == 0 ? (Number) (long) s.tier : (Number) damage;
                else if (c == String.class) { int n = texts++; v = n == 0 ? s.kit.id : n == 1 ? trigger : abilityId; }
                else if (c == Location.class) v = loc != null ? loc : p.getLocation();
                else if (c == Object.class) v = target != null ? target : p;
                args[i] = v == null ? new Object[0] : new Object[]{v};
            }
            f.execute(args);
            return true;
        }
    }
}
