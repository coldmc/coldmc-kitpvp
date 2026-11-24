package se.coldmc.kitpvp;

import fr.phoenixdevt.profiles.event.ProfileSelectedEvent;
import fr.phoenixdevt.profiles.event.ProfileUnloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/** MMOProfiles: remember which profile a player is in, and hand back a crash-orphaned inventory to that profile only. */
public final class ProfilesHook implements Listener {
    private final KitPvP plugin;
    public ProfilesHook(KitPvP plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSelected(ProfileSelectedEvent e) {
        Player p = e.getPlayer();
        UUID profile = e.getProfile().getUniqueId();
        plugin.game.profileOf.put(p.getUniqueId(), profile);
        // After MMOProfiles has put the profile's own data on the player.
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) plugin.game.recoverAfterCrash(p, profile); }, 20L);
    }

    /** A profile switch without logging out: leave KitPvP first so the profile saves the real inventory. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUnload(ProfileUnloadEvent e) {
        Player p = e.getPlayer();
        if (p != null && plugin.game.session(p) != null) plugin.game.leave(p, true, true);
    }
}
