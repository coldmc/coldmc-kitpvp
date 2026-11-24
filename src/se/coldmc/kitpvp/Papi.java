package se.coldmc.kitpvp;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * %kitpvp_in_game% %kitpvp_kit% %kitpvp_tier% %kitpvp_arena% %kitpvp_combat% %kitpvp_coins% %kitpvp_kd%
 * %kitpvp_stat_<stat>%   %kitpvp_kitstat_<Kit>_<stat>%   %kitpvp_owned_<Kit>%
 */
public final class Papi extends PlaceholderExpansion {
    private final KitPvP plugin;
    public Papi(KitPvP plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "kitpvp"; }
    @Override public String getAuthor() { return "Flake"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer op, String params) {
        if (op == null) return "";
        PlayerData d = plugin.game.data.get(op.getUniqueId());
        Session s = plugin.game.sessions.get(op.getUniqueId());
        String low = params.toLowerCase(Locale.ROOT);
        switch (low) {
            case "in_game": return String.valueOf(s != null);
            case "kit": return s == null || s.kit == null ? "" : s.kit.id;
            case "kit_name": return s == null || s.kit == null ? "" : s.kit.tierName(s.tier);
            case "tier": return s == null ? "0" : String.valueOf(s.tier);
            case "arena": return s == null ? "" : (s.duel != null ? s.duel.arena.id : s.arena.id);
            case "combat": return String.valueOf(s != null && s.inCombat());
            case "in_duel": return String.valueOf(s != null && s.duel != null);
            case "coins": return d == null ? "0" : Game.num(plugin.game.balance(d));
            case "kd": return d == null ? "0" : Game.num(d.kd());
            default: break;
        }
        if (d == null) return "0";
        if (low.startsWith("keys_")) { Crates.Crate c = plugin.crates.crate(params.substring(5)); return c == null ? "0" : String.valueOf(plugin.crates.keys(d, c)); }
        if (low.equals("challenge")) { Challenges.Challenge c = plugin.challenges.active(d); return c == null ? "" : c.id; }
        if (low.equals("challenge_progress")) { Challenges.Challenge c = plugin.challenges.active(d); return c == null ? "0" : Game.num(plugin.challenges.progress(d, c)); }
        if (low.equals("achievements")) return String.valueOf(d.achievements.size());
        if (low.startsWith("stat_")) return Game.num(d.stat(low.substring(5)));
        if (low.startsWith("owned_")) { Kit k = plugin.game.kit(params.substring(6)); return k == null ? "0" : String.valueOf(plugin.game.owned(d, k)); }
        if (low.startsWith("kitstat_")) {
            String rest = params.substring(8);
            for (Kit k : plugin.game.kits.values())
                if (rest.toLowerCase(Locale.ROOT).startsWith(k.id.toLowerCase(Locale.ROOT) + "_")) return Game.num(d.kitStat(k.id, rest.substring(k.id.length() + 1).toLowerCase(Locale.ROOT)));
            return "0";
        }
        return null;
    }
}
