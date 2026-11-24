package se.coldmc.kitpvp;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/** Optional plugins. Each call goes through a holder class so a missing plugin is never loaded. */
public final class Hooks {
    private Hooks() {}
    private static boolean has(String name) { return Bukkit.getPluginManager().isPluginEnabled(name); }

    public static ItemStack nexoItem(String id) {
        if (!has("Nexo")) return null;
        try { return NexoH.item(id); } catch (Throwable t) { return null; }
    }
    public static String nexoId(ItemStack it) {
        if (!has("Nexo")) return null;
        try { return NexoH.id(it); } catch (Throwable t) { return null; }
    }
    public static ItemStack mmoItem(String type, String id) {
        if (!has("MMOItems")) return null;
        try { return MmoH.item(type, id); } catch (Throwable t) { return null; }
    }

    public static String papi(org.bukkit.entity.Player p, String s) {
        if (!has("PlaceholderAPI") || s.indexOf('%') < 0) return s;
        try { return PapiH.set(p, s); } catch (Throwable t) { return s; }
    }
    /** Vault balance change; returns false when Vault has no economy. */
    public static boolean vaultGive(org.bukkit.OfflinePlayer p, double amount) {
        if (!has("Vault")) return false;
        try { return VaultH.give(p, amount); } catch (Throwable t) { return false; }
    }
    public static double vaultBalance(org.bukkit.OfflinePlayer p) {
        if (!has("Vault")) return 0;
        try { return VaultH.balance(p); } catch (Throwable t) { return 0; }
    }

    private static final class PapiH {
        static String set(org.bukkit.entity.Player p, String s) { return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, s); }
    }
    private static final class VaultH {
        static net.milkbowl.vault.economy.Economy eco() {
            var r = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            return r == null ? null : r.getProvider();
        }
        static boolean give(org.bukkit.OfflinePlayer p, double amount) {
            var e = eco();
            if (e == null) return false;
            return (amount >= 0 ? e.depositPlayer(p, amount) : e.withdrawPlayer(p, -amount)).transactionSuccess();
        }
        static double balance(org.bukkit.OfflinePlayer p) { var e = eco(); return e == null ? 0 : e.getBalance(p); }
    }
    private static final class NexoH {
        static ItemStack item(String id) {
            com.nexomc.nexo.items.ItemBuilder b = com.nexomc.nexo.api.NexoItems.itemFromId(id);
            return b == null ? null : b.build().clone();
        }
        static String id(ItemStack it) { return com.nexomc.nexo.api.NexoItems.idFromItem(it); }
    }
    private static final class MmoH {
        static ItemStack item(String type, String id) {
            net.Indyuce.mmoitems.api.Type t = net.Indyuce.mmoitems.MMOItems.plugin.getTypes().get(type.toUpperCase());
            return t == null ? null : net.Indyuce.mmoitems.MMOItems.plugin.getItem(t, id.toUpperCase());
        }
    }
}
