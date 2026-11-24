package se.coldmc.kitpvp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The old PvP server's abilities, built in. abilities.yml reaches them with "native:<id>" (the default for these ids),
 * and their numbers (radius, seconds, damage ...) are read from the ability's own section there.
 * New abilities do not belong here: they are Skript functions.
 */
public final class Natives implements Listener {
    public static final Set<String> IDS = Set.of("kangaroo", "fisherman", "dracula", "dracula-bite", "hades", "thor", "stomper",
            "spiderman", "prisoner", "climber", "dragon", "suicidal", "phantom", "timelord", "burrower", "zen", "monk", "hulk",
            "rider", "summoner", "souper", "bane", "sunder", "centaur", "blinker", "switcher", "builder", "lapeace", "marriage");
    private static final String M_SHOT = "kitpvp_native_shot", M_OURS = "kitpvp_native";
    /** Set by a native that fires a shot: the use is counted when the shot hits, not now. */
    public boolean countLater;

    private final KitPvP plugin;
    private final Map<Location, BlockData> tempBlocks = new LinkedHashMap<>();
    private final Set<Entity> tempEntities = new HashSet<>();
    private final Map<UUID, Long> draculaUntil = new HashMap<>(), frozenUntil = new HashMap<>();
    private final Set<UUID> flying = new HashSet<>();
    private final Map<UUID, Long> peaceUntil = new HashMap<>();
    /** Players inside a Prisoner jail, until when. Nothing can hit them there: the lava is the punishment. */
    private final Map<UUID, Long> jailedUntil = new HashMap<>();
    private final Map<UUID, UUID> spouse = new HashMap<>();
    private final Map<UUID, Long> marriedUntil = new HashMap<>();

    /** La Peace (neither side of any fight) and Marriage (not each other): a hit that must not land. */
    public boolean blocksHit(Player attacker, Player victim) {
        long now = System.currentTimeMillis();
        if (peaceUntil.getOrDefault(attacker.getUniqueId(), 0L) > now || peaceUntil.getOrDefault(victim.getUniqueId(), 0L) > now) return true;
        if (victim.getUniqueId().equals(spouse.get(attacker.getUniqueId()))) {
            long until = marriedUntil.getOrDefault(attacker.getUniqueId(), 0L);
            if (until == 0 || until > now) return true;
            divorce(attacker.getUniqueId());
        }
        return false;
    }

    private void divorce(UUID a) {
        UUID b = spouse.remove(a);
        marriedUntil.remove(a);
        if (b != null && a.equals(spouse.get(b))) { spouse.remove(b); marriedUntil.remove(b); }
    }

    public Natives(KitPvP plugin) { this.plugin = plugin; }

    public static boolean has(String id) { return IDS.contains(id.toLowerCase(Locale.ROOT)); }

    // ---------------------------------------------------------------- helpers

    private double num(Scripts.Ability a, String key, double def) { return a == null || a.settings == null ? def : a.settings.getDouble(key, def); }
    private long ticks(Scripts.Ability a, String key, double defSeconds) { return Math.max(1, (long) (num(a, key, defSeconds) * 20)); }
    private void later(long ticks, Runnable r) { Bukkit.getScheduler().runTaskLater(plugin, r, ticks); }

    /** Who this player may hurt right now: others with a kit in the same fight. */
    private boolean enemy(Session s, Entity e) {
        if (!(e instanceof Player o) || o == s.player) return false;
        Session os = plugin.game.session(o);
        if (os == null || os.kit == null || os.spawnProtected() || o.isDead()) return false;
        return s.duel == null ? os.duel == null : os.duel == s.duel;
    }

    private List<Player> enemies(Session s, Location c, double radius) {
        List<Player> out = new ArrayList<>();
        for (Session os : plugin.game.sessions.values())
            if (enemy(s, os.player) && os.player.getWorld().equals(c.getWorld()) && os.player.getLocation().distanceSquared(c) <= radius * radius) out.add(os.player);
        return out;
    }

    /** The clicked player, or the one being looked at. */
    private Player aimed(Session s, Entity target, double range) {
        if (enemy(s, target)) return (Player) target;
        Player p = s.player;
        RayTraceResult r = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, 0.6, e -> enemy(s, e));
        return r != null && r.getHitEntity() instanceof Player t && p.hasLineOfSight(t) ? t : null;
    }

    private void hurt(Player attacker, LivingEntity victim, double damage) { if (damage > 0) victim.damage(damage, attacker); }

    private void tempBlock(Block b, Material m, long ticks) {
        if (!b.getType().isAir() && !b.isReplaceable()) return;
        Location l = b.getLocation();
        tempBlocks.putIfAbsent(l, b.getBlockData());
        b.setType(m, false);
        later(ticks, () -> restore(l));
    }
    private void restore(Location l) {
        BlockData was = tempBlocks.remove(l);
        if (was != null) l.getBlock().setBlockData(was, false);
    }

    private <T extends Entity> T temp(T e, long ticks) {
        tempEntities.add(e);
        e.setMetadata(M_OURS, new FixedMetadataValue(plugin, true));
        e.setPersistent(false);
        later(ticks, () -> { tempEntities.remove(e); if (e.isValid()) e.remove(); });
        return e;
    }

    /** Server stop or plugin unload: nothing of ours stays in the world. */
    public void cleanup() {
        for (Map.Entry<Location, BlockData> e : new ArrayList<>(tempBlocks.entrySet())) e.getKey().getBlock().setBlockData(e.getValue(), false);
        tempBlocks.clear();
        for (Entity e : tempEntities) if (e.isValid()) e.remove();
        tempEntities.clear();
    }

    private void sound(Location l, Sound s, float pitch) { l.getWorld().playSound(l, s, 1f, pitch); }

    // ---------------------------------------------------------------- the abilities

    /** false = nothing happened (no target ...): the cooldown is given back. */
    public boolean run(String id, Session s, Scripts.Ability a, Entity target, Location loc) {
        Player p = s.player;
        switch (id.toLowerCase(Locale.ROOT)) {
            case "kangaroo" -> {
                Vector d = p.getLocation().getDirection();
                if (p.isSneaking()) p.setVelocity(d.multiply(num(a, "Forward-Boost", 1.8)).setY(num(a, "Forward-Lift", 0.5)));
                else p.setVelocity(d.multiply(num(a, "Leap-Forward", 0.6)).setY(num(a, "Leap-Up", 1.2)));
                p.setFallDistance(0);
                sound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f);
            }
            case "fisherman" -> {
                if (!enemy(s, target)) return false;
                target.teleport(p.getLocation());
                sound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f);
            }
            case "dracula" -> {
                long t = ticks(a, "Lasts-For", 15);
                draculaUntil.put(p.getUniqueId(), System.currentTimeMillis() + t * 50);
                // The enemies' potion effects become yours for as long as your blood can take them.
                for (Player o : enemies(s, p.getLocation(), num(a, "Steal-Radius", 6)))
                    for (PotionEffect fx : new ArrayList<>(o.getActivePotionEffects())) {
                        o.removePotionEffect(fx.getType());
                        p.addPotionEffect(new PotionEffect(fx.getType(), (int) t, fx.getAmplifier(), true, false, true));
                    }
                sound(p.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.6f);
            }
            case "dracula-bite" -> {
                if (System.currentTimeMillis() > draculaUntil.getOrDefault(p.getUniqueId(), 0L) || !(target instanceof LivingEntity)) return false;
                double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                p.setHealth(Math.min(max, p.getHealth() + num(a, "Heal-Per-Hit", 1) * 2));
                target.getWorld().spawnParticle(Particle.BLOCK, target.getLocation().add(0, 1, 0), 12, 0.2, 0.3, 0.2, Material.REDSTONE_BLOCK.createBlockData());
            }
            case "hades" -> {
                List<Player> near = enemies(s, p.getLocation(), 30);
                for (int i = 0; i < (int) num(a, "Amount-Of-Dogs", 3); i++) {
                    Wolf w = temp(p.getWorld().spawn(p.getLocation(), Wolf.class), ticks(a, "Dogs-Last-For", 10));
                    w.setOwner(p); w.setAdult();
                    if (!near.isEmpty()) { w.setAngry(true); w.setTarget(near.get(i % near.size())); }
                }
                sound(p.getLocation(), Sound.ENTITY_WOLF_GROWL, 0.8f);
            }
            case "thor" -> {
                List<Player> hit = enemies(s, p.getLocation(), num(a, "Strike-Radius", 5));
                if (hit.isEmpty()) return false;
                for (Player o : hit) { o.getWorld().strikeLightningEffect(o.getLocation()); hurt(p, o, num(a, "Lightning-Damage", 4)); }
            }
            case "stomper" -> {
                double fall = 0;
                for (MetadataValue v : p.getMetadata(Scripts.M_DAMAGE)) if (v.getOwningPlugin() == plugin) fall = v.asDouble();
                double sneakCap = num(a, "Max-Damage-Dealt-When-Stomped-While-Shifting", 2);
                List<Player> hit = enemies(s, p.getLocation(), num(a, "Stomp-Radius", 5));
                for (Player o : hit) hurt(p, o, o.isSneaking() ? Math.min(fall, sneakCap) : fall);
                if (!hit.isEmpty()) sound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.2f);
                // last, because hurting someone runs other abilities and clears this value
                p.setMetadata(Scripts.M_DAMAGE, new FixedMetadataValue(plugin, Math.min(fall, num(a, "Max-Fall-Damage", 2))));
            }
            case "spiderman", "prisoner" -> {
                Snowball ball = p.launchProjectile(Snowball.class);
                ball.setMetadata(M_SHOT, new FixedMetadataValue(plugin, id.toLowerCase(Locale.ROOT) + ":" + (a == null ? id : a.id)));
                countLater = true;
            }
            case "climber" -> {
                // The arrow's landing place pulls you up, a chicken on your head slows the fall.
                Vector pull = loc.toVector().subtract(p.getLocation().toVector());
                if (pull.lengthSquared() < 4 || !loc.getWorld().equals(p.getWorld())) return false;
                double dist = Math.min(pull.length(), 40);
                p.setVelocity(pull.normalize().multiply(0.6 + dist * 0.07).setY(Math.max(0.6, pull.getY() * 0.12 + 0.6)));
                long t = ticks(a, "Time-Until-Chicken-Disappear", 5);
                Chicken c = temp(p.getWorld().spawn(p.getLocation(), Chicken.class), t);
                c.setInvulnerable(true);
                p.addPassenger(c);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, (int) t, 0, true, false, true));
                sound(p.getLocation(), Sound.ENTITY_CHICKEN_HURT, 1f);
            }
            case "dragon" -> {
                int bursts = Math.max(1, (int) num(a, "Amount-Of-Bursts", 3));
                for (int i = 0; i < bursts; i++) later(1 + i * 10L, () -> {
                    if (!p.isOnline() || plugin.game.session(p) != s || s.kit == null) return;
                    Location eye = p.getEyeLocation();
                    Vector dir = eye.getDirection();
                    double range = num(a, "Fire-Range", 10);
                    for (double d = 1; d <= range; d += 0.5)
                        p.getWorld().spawnParticle(Particle.FLAME, eye.clone().add(dir.clone().multiply(d)), 3, d * 0.06, d * 0.06, d * 0.06, 0.01);
                    sound(eye, Sound.ENTITY_BLAZE_SHOOT, 0.7f);
                    for (Player o : enemies(s, eye, range)) {
                        Vector to = o.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
                        if (to.lengthSquared() > 0.01 && to.normalize().dot(dir) < 0.8) continue;
                        o.setFireTicks((int) ticks(a, "Fire-Lasts-For", 5));
                        hurt(p, o, num(a, "Damage-Dealt", 4));
                    }
                });
            }
            case "suicidal" -> {
                Location at = p.getLocation();
                p.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
                sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.8f);
                double radius = num(a, "Radius", 5), dmg = num(a, "Damage", 16);
                for (Player o : enemies(s, at, radius)) hurt(p, o, dmg * (1 - o.getLocation().distance(at) / (radius * 1.5)));
                later(1, () -> { if (p.isOnline() && plugin.game.session(p) == s) plugin.game.kill(s, null); });
            }
            case "phantom" -> {
                if (p.getAllowFlight()) return false;
                p.setAllowFlight(true); p.setFlying(true);
                flying.add(p.getUniqueId());
                sound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1f);
                later(ticks(a, "Flight-Lasts-For", 5), () -> {
                    if (!flying.remove(p.getUniqueId()) || !p.isOnline()) return;
                    p.setFlying(false); p.setAllowFlight(false); p.setFallDistance(0);
                    if (plugin.game.session(p) == s) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0, true, false, true));
                });
            }
            case "timelord" -> {
                List<Player> hit = enemies(s, p.getLocation(), num(a, "Freeze-Radius", 7));
                if (hit.isEmpty()) return false;
                long t = ticks(a, "Freeze-Time", 10);
                for (Player o : hit) {
                    frozenUntil.put(o.getUniqueId(), System.currentTimeMillis() + t * 50);
                    o.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) t, 6, true, false, true));
                    o.setVelocity(new Vector());
                }
                sound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.4f);
            }
            case "burrower" -> {
                Block feet = p.getLocation().getBlock();
                long t = ticks(a, "Room-Lasts-For", 8);
                p.teleport(feet.getLocation().add(0.5, 0, 0.5).setDirection(p.getLocation().getDirection()));
                for (int x = -1; x <= 1; x++) for (int y = -1; y <= 2; y++) for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0 && (y == 0 || y == 1)) continue;      // the room itself
                    tempBlock(feet.getRelative(x, y, z), Material.BRICKS, t);
                }
                sound(p.getLocation(), Sound.BLOCK_STONE_PLACE, 0.8f);
            }
            case "zen" -> {
                Player best = null;
                double range = num(a, "Max-Range", 60), bd = range * range;
                for (Player o : enemies(s, p.getLocation(), range)) { double d = o.getLocation().distanceSquared(p.getLocation()); if (d <= bd) { bd = d; best = o; } }
                if (best == null) return false;
                p.teleport(best.getLocation());
                sound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f);
            }
            case "monk" -> {
                Player t = aimed(s, target, num(a, "Range", 5));
                if (t == null) return false;
                PlayerInventory inv = t.getInventory();
                int held = inv.getHeldItemSlot(), other = ThreadLocalRandom.current().nextInt(8);
                if (other >= held) other++;
                ItemStack x = inv.getItem(held);
                inv.setItem(held, inv.getItem(other)); inv.setItem(other, x);
                sound(t.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f);
            }
            case "hulk" -> {
                List<Player> hit = enemies(s, p.getLocation(), num(a, "Damage-Radius", 10));
                p.getWorld().spawnParticle(Particle.EXPLOSION, p.getLocation(), 6, 2, 0.3, 2);
                sound(p.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.6f);
                for (Player o : hit) {
                    hurt(p, o, num(a, "Damage-Dealt", 5));
                    Vector away = o.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                    if (away.lengthSquared() > 0.01) away.normalize();
                    o.setVelocity(away.multiply(num(a, "Throw", 1.4)).setY(num(a, "Throw-Up", 0.9)));
                }
            }
            case "rider" -> {
                Horse h = temp(p.getWorld().spawn(p.getLocation(), Horse.class), ticks(a, "Horse-Lasts-For", 10));
                h.setAdult(); h.setTamed(true); h.setOwner(p);
                h.getInventory().setSaddle(new ItemStack(Material.SADDLE));
                h.addPassenger(p);
            }
            case "summoner" -> {
                IronGolem g = temp(p.getWorld().spawn(p.getLocation(), IronGolem.class), ticks(a, "Golem-Lasts-For", 20));
                g.setPlayerCreated(true);
                Player best = null; double bd = Double.MAX_VALUE;
                for (Player o : enemies(s, p.getLocation(), 20)) { double d = o.getLocation().distanceSquared(p.getLocation()); if (d < bd) { bd = d; best = o; } }
                if (best != null) g.setTarget(best);
                g.setMetadata("kitpvp_owner", new FixedMetadataValue(plugin, p.getUniqueId().toString()));
            }
            case "souper" -> {
                // Soup from the backpack moves into the empty hotbar slots (bowls make room too).
                PlayerInventory inv = p.getInventory();
                int moved = 0;
                for (int hot = 0; hot < 9; hot++) {
                    ItemStack in = inv.getItem(hot);
                    if (in != null && !in.getType().isAir() && in.getType() != Material.BOWL) continue;
                    for (int back = 9; back < 36; back++) {
                        ItemStack it = inv.getItem(back);
                        if (!plugin.game.isSoup(it)) continue;
                        inv.setItem(hot, it); inv.setItem(back, null); moved++;
                        break;
                    }
                }
                if (moved == 0) return false;
                sound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f);
            }
            case "bane" -> {
                Player t = aimed(s, target, num(a, "Range", 12));
                if (t == null) return false;
                int d = (int) ticks(a, "Duration", 8);
                for (PotionEffectType fx : List.of(PotionEffectType.BLINDNESS, PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS))
                    t.addPotionEffect(new PotionEffect(fx, d, 0, true, false, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, d, 1, true, false, true));    // the curse binds the caster too
                sound(t.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 1.4f);
            }
            case "sunder" -> {
                Player t = aimed(s, target, num(a, "Range", 12));
                if (t == null) return false;
                double mine = p.getHealth(), theirs = t.getHealth();
                p.setHealth(Math.max(1, Math.min(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), theirs)));
                t.setHealth(Math.max(1, Math.min(t.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), mine)));
                sound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1.2f);
            }
            case "centaur" -> {
                Location at = p.getLocation();
                List<Player> hit = enemies(s, at, num(a, "Damage-Radius", 4));
                p.getWorld().spawnParticle(Particle.EXPLOSION, at, 4, 1.5, 0.3, 1.5);
                sound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f);
                double dmg = num(a, "Damage", 18);
                PotionEffect regen = new PotionEffect(PotionEffectType.REGENERATION, (int) ticks(a, "Regen-Duration", 5), Math.max(0, (int) num(a, "Regen-Level", 2) - 1), true, false, true);
                for (Player o : hit) { hurt(p, o, dmg); if (!o.isDead() && plugin.game.session(o) != null && plugin.game.session(o).kit != null) o.addPotionEffect(regen); }
                p.damage(num(a, "Self-Damage", dmg / 2));
            }
            case "blinker" -> {
                if (!enemy(s, target)) return false;
                p.teleport(target.getLocation().setDirection(p.getLocation().getDirection()));
                TNTPrimed tnt = p.getWorld().spawn(target.getLocation(), TNTPrimed.class);
                tnt.setSource(p); tnt.setFuseTicks((int) ticks(a, "Fuse", 1.5));
                tnt.setMetadata(M_OURS, new FixedMetadataValue(plugin, true));
                sound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f);
            }
            case "switcher", "lapeace" -> {
                if (!enemy(s, target)) return false;
                Location mine = p.getLocation(), theirs = target.getLocation();
                p.teleport(theirs); target.teleport(mine);
                sound(mine, Sound.ENTITY_ENDERMAN_TELEPORT, 1f); sound(theirs, Sound.ENTITY_ENDERMAN_TELEPORT, 1f);
                if (id.equalsIgnoreCase("lapeace")) {
                    // Both are out of every fight for a moment: they cannot hit, and cannot be hit.
                    long until = System.currentTimeMillis() + ticks(a, "Peace-Seconds", 3) * 50;
                    peaceUntil.put(p.getUniqueId(), until); peaceUntil.put(target.getUniqueId(), until);
                    for (Player x : List.of(p, (Player) target)) {
                        plugin.lang.actionBar(x, "Ability.Peace", "seconds", String.valueOf((long) num(a, "Peace-Seconds", 3)));
                        x.getWorld().spawnParticle(Particle.HEART, x.getLocation().add(0, 2, 0), 5, 0.3, 0.2, 0.3);
                    }
                }
            }
            case "marriage" -> {
                if (!enemy(s, target)) return false;
                Player t = (Player) target;
                divorce(p.getUniqueId()); divorce(t.getUniqueId());
                spouse.put(p.getUniqueId(), t.getUniqueId()); spouse.put(t.getUniqueId(), p.getUniqueId());
                double secs = num(a, "Lasts-For", 0);        // 0 = until one of the two dies or changes kit
                if (secs > 0) { long until = System.currentTimeMillis() + (long) (secs * 1000); marriedUntil.put(p.getUniqueId(), until); marriedUntil.put(t.getUniqueId(), until); }
                plugin.lang.send(p, "Ability.Married", "player", t.getName());
                plugin.lang.send(t, "Ability.Married", "player", p.getName());
                for (Player x : List.of(p, t)) { x.getWorld().spawnParticle(Particle.HEART, x.getLocation().add(0, 2, 0), 8, 0.4, 0.3, 0.4); sound(x.getLocation(), Sound.ENTITY_VILLAGER_CELEBRATE, 1f); }
            }
            case "builder" -> {
                Block b = loc.getBlock();
                Location l = b.getLocation();
                Material placed = b.getType();
                tempBlocks.putIfAbsent(l, Material.AIR.createBlockData());
                // The block is spent: nothing comes back when it vanishes (unless Give-Back: true).
                boolean back = a != null && a.settings != null && a.settings.getBoolean("Give-Back", false);
                later(ticks(a, "Blocks-Last-For", 3), () -> {
                    restore(l);
                    if (back && p.isOnline() && plugin.game.session(p) == s && s.kit != null && !placed.isAir()) p.getInventory().addItem(new ItemStack(placed));
                });
            }
            default -> { return false; }
        }
        return true;
    }

    // ---------------------------------------------------------------- what the abilities leave behind

    @EventHandler
    public void onShotLands(ProjectileHitEvent e) {
        if (!e.getEntity().hasMetadata(M_SHOT) || !(e.getEntity().getShooter() instanceof Player p)) return;
        Session s = plugin.game.session(p);
        String[] tag = e.getEntity().getMetadata(M_SHOT).get(0).asString().split(":", 2);
        Scripts.Ability a = plugin.scripts.ability(tag[1]);
        if (s == null || !enemy(s, e.getHitEntity())) { if (s != null && a != null) s.abilityReady.remove(a.id); return; }   // a miss costs nothing
        s.data.add("abilities_used", 1, s.kitId());   // a hit is a use that counts
        Player t = (Player) e.getHitEntity();
        Block feet = t.getLocation().getBlock();
        if (tag[0].equals("spiderman")) {
            long ticks = ticks(a, "Webs-Last-For", 10);
            for (int[] o : new int[][]{{0, 0, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}}) tempBlock(feet.getRelative(o[0], o[1], o[2]), Material.COBWEB, ticks);
        } else {
            long ticks = ticks(a, "Prison-Lasts-For", 6);
            t.teleport(feet.getLocation().add(0.5, 0, 0.5).setDirection(t.getLocation().getDirection()));
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 3; y++) for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0 && y >= 0 && y <= 2) continue;
                tempBlock(feet.getRelative(x, y, z), y == 1 ? Material.IRON_BARS : Material.OBSIDIAN, ticks);
            }
            jailedUntil.put(t.getUniqueId(), System.currentTimeMillis() + ticks * 50);
            // The lava stands at the prisoner's feet.
            if (a == null || a.settings == null || a.settings.getBoolean("Lava", true)) tempBlock(feet, Material.LAVA, ticks);
        }
    }

    /** The iron bars block every hit on the jailed player, melee and projectiles alike, so the lava
     *  is the only damage (the owner, 2026-11-13). Lava and fire are not entity damage, so they still hurt. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onJailedHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player t)) return;
        Long until = jailedUntil.get(t.getUniqueId());
        if (until == null) return;
        if (System.currentTimeMillis() > until) { jailedUntil.remove(t.getUniqueId()); return; }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozenMove(PlayerMoveEvent e) {
        Long until = frozenUntil.get(e.getPlayer().getUniqueId());
        if (until == null) return;
        if (System.currentTimeMillis() > until || plugin.game.session(e.getPlayer()) == null) { frozenUntil.remove(e.getPlayer().getUniqueId()); return; }
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() == to.getX() && from.getZ() == to.getZ() && to.getY() <= from.getY()) return;
        Location stay = from.clone();
        stay.setYaw(to.getYaw()); stay.setPitch(to.getPitch());
        if (to.getY() < from.getY()) stay.setY(to.getY());          // falling is allowed, walking and jumping are not
        e.setTo(stay);
    }

    /** Our explosions hurt players, never the arena. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (e.getEntity().hasMetadata(M_OURS)) e.blockList().clear();
    }

    /** A death or a kit change ends what was running on the player. */
    public void clear(Player p) {
        draculaUntil.remove(p.getUniqueId());
        peaceUntil.remove(p.getUniqueId());
        divorce(p.getUniqueId());
        frozenUntil.remove(p.getUniqueId());
        jailedUntil.remove(p.getUniqueId());
        for (Entity e : new ArrayList<>(p.getPassengers())) if (e.hasMetadata(M_OURS)) e.remove();
        if (flying.remove(p.getUniqueId())) { p.setFlying(false); p.setAllowFlight(false); }
    }
}
