package se.coldmc.kitpvp;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Items in kit files are plain text. Either one line:
 *   DIAMOND_SWORD enchant:sharpness:2 model:nexo:sword_katana unbreakable
 *   nexo:iridium_helmet            (a real Nexo item)      mmoitems:SWORD:CUTLASS
 *   ARROW*64                       SPLASH_POTION potion:healing
 * or a map with the same keys (item, amount, name, lore, enchants, model, armor-model, color,
 * unbreakable, glow, ability, potion). Anything the text form cannot hold is kept exactly
 * in a "data" key, so an item built in game with any plugin still round-trips.
 */
public final class Items {
    public static NamespacedKey ABILITY, LOBBY;
    private Items() {}

    public static void init(KitPvP plugin) {
        ABILITY = new NamespacedKey(plugin, "ability");
        LOBBY = new NamespacedKey(plugin, "lobby");
    }

    // ---------------------------------------------------------------- parse

    /** A kit item line of "EMPTY" (or "AIR") reserves the slot: nothing is put there and Soup-Fill skips it. */
    public static boolean isEmptyMarker(Object spec) {
        return spec instanceof String s && (s.trim().equalsIgnoreCase("EMPTY") || s.trim().equalsIgnoreCase("AIR"));
    }

    public static ItemStack parse(Object spec) {
        try {
            if (spec == null) return null;
            if (spec instanceof ItemStack i) return i.clone();
            if (spec instanceof ConfigurationSection cs) spec = cs.getValues(false);
            if (spec instanceof Map<?, ?> m) return fromMap(m);
            return fromLine(String.valueOf(spec).trim());
        } catch (Exception e) {
            Bukkit.getLogger().warning("[KitPvP] bad item '" + spec + "': " + e);
            return null;
        }
    }

    private static ItemStack fromLine(String line) {
        if (line.isEmpty()) return null;
        String[] t = line.split("\\s+");
        Map<String, Object> m = new LinkedHashMap<>();
        String id = t[0];
        int star = id.lastIndexOf('*');
        if (star > 0) { m.put("amount", Integer.parseInt(id.substring(star + 1))); id = id.substring(0, star); }
        m.put("item", id);
        Map<String, Object> ench = new LinkedHashMap<>();
        for (int i = 1; i < t.length; i++) {
            String k = t[i], v = "";
            int c = k.indexOf(':');
            if (c > 0) { v = k.substring(c + 1); k = k.substring(0, c); }
            switch (k.toLowerCase(Locale.ROOT)) {
                case "enchant" -> { int d = v.lastIndexOf(':'); ench.put(v.substring(0, d), Integer.parseInt(v.substring(d + 1))); }
                case "unbreakable", "glow" -> m.put(k.toLowerCase(Locale.ROOT), true);
                default -> m.put(k.toLowerCase(Locale.ROOT), v);
            }
        }
        if (!ench.isEmpty()) m.put("enchants", ench);
        return fromMap(m);
    }

    private static ItemStack fromMap(Map<?, ?> m) {
        Object data = m.get("data");
        if (data != null) return ItemStack.deserializeBytes(Base64.getDecoder().decode(String.valueOf(data)));
        ItemStack it = base(String.valueOf(m.get("item")));
        if (it == null) return null;
        Object am = m.get("amount");
        int amount = am == null ? 1 : Math.max(1, Integer.parseInt(String.valueOf(am)));
        ItemMeta meta = it.getItemMeta();
        // More than the item normally stacks to (64 snowballs): the stack itself is made that big.
        if (meta != null && amount > it.getMaxStackSize()) { meta.setMaxStackSize(Math.min(99, amount)); it.setItemMeta(meta); meta = it.getItemMeta(); }
        if (am != null) it.setAmount(Math.min(99, amount));
        if (meta == null) return it;
        if (m.get("name") != null) meta.displayName(Lang.color(String.valueOf(m.get("name"))));
        if (m.get("lore") instanceof List<?> l) {
            List<Component> lore = new ArrayList<>();
            for (Object o : l) lore.add(Lang.color(String.valueOf(o)));
            meta.lore(lore);
        }
        Object en = m.get("enchants");
        if (en instanceof ConfigurationSection cs) en = cs.getValues(false);
        if (en instanceof Map<?, ?> em) for (Map.Entry<?, ?> e : em.entrySet()) {
            Enchantment x = enchant(String.valueOf(e.getKey()));
            if (x != null) meta.addEnchant(x, Integer.parseInt(String.valueOf(e.getValue())), true);
            else Bukkit.getLogger().warning("[KitPvP] unknown enchantment '" + e.getKey() + "'");
        }
        if (m.get("model") != null) meta.setItemModel(key(String.valueOf(m.get("model"))));
        if (m.get("armor-model") != null) {
            EquippableComponent eq = meta.getEquippable();
            EquipmentSlot slot = it.getType().getEquipmentSlot();
            eq.setSlot(slot == EquipmentSlot.HAND ? EquipmentSlot.HEAD : slot);
            eq.setModel(key(String.valueOf(m.get("armor-model"))));
            meta.setEquippable(eq);
        }
        if (m.get("color") != null) {
            Color c = Color.fromRGB(Integer.parseInt(String.valueOf(m.get("color")).replace("#", ""), 16));
            if (meta instanceof LeatherArmorMeta lm) lm.setColor(c);
            else if (meta instanceof PotionMeta pm) pm.setColor(c);
        }
        if (m.get("potion") != null && meta instanceof PotionMeta pm) {
            try { pm.setBasePotionType(PotionType.valueOf(String.valueOf(m.get("potion")).toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException e) { Bukkit.getLogger().warning("[KitPvP] unknown potion '" + m.get("potion") + "'"); }
        }
        if (truthy(m.get("unbreakable"))) meta.setUnbreakable(true);
        if (m.get("damage") != null && meta instanceof org.bukkit.inventory.meta.Damageable dm)
            try { dm.setDamage(Integer.parseInt(String.valueOf(m.get("damage")).trim())); } catch (NumberFormatException ignored) {}
        if (truthy(m.get("glow"))) meta.setEnchantmentGlintOverride(true);
        if (m.get("ability") != null) meta.getPersistentDataContainer().set(ABILITY, PersistentDataType.STRING, String.valueOf(m.get("ability")));
        it.setItemMeta(meta);
        return it;
    }

    private static boolean truthy(Object o) { return o != null && !"false".equalsIgnoreCase(String.valueOf(o)); }

    private static NamespacedKey key(String s) {
        NamespacedKey k = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
        if (k == null) throw new IllegalArgumentException("bad key " + s);
        return k;
    }

    public static Enchantment enchant(String name) {
        NamespacedKey k = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        return k == null ? null : Registry.ENCHANTMENT.get(k);
    }

    /** The plain item an id names: a material, nexo:<id> or mmoitems:<TYPE>:<ID>. */
    public static ItemStack base(String id) {
        String low = id.toLowerCase(Locale.ROOT);
        if (low.startsWith("nexo:")) {
            ItemStack n = Hooks.nexoItem(id.substring(5));
            if (n == null) Bukkit.getLogger().warning("[KitPvP] no Nexo item '" + id.substring(5) + "'");
            return n;
        }
        if (low.startsWith("mmoitems:")) {
            String[] p = id.split(":");
            ItemStack n = p.length == 3 ? Hooks.mmoItem(p[1], p[2]) : null;
            if (n == null) Bukkit.getLogger().warning("[KitPvP] no MMOItems item '" + id + "'");
            return n;
        }
        Material mat = Material.matchMaterial(id);
        if (mat == null || !mat.isItem()) { Bukkit.getLogger().warning("[KitPvP] unknown item '" + id + "'"); return null; }
        return new ItemStack(mat);
    }

    // ---------------------------------------------------------------- serialize

    /** The shortest text form that rebuilds exactly this item. */
    public static Object serialize(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        String nexo = Hooks.nexoId(it);
        String id = nexo != null ? "nexo:" + nexo : it.getType().name();
        ItemStack base = base(id);
        if (base == null) { base = new ItemStack(it.getType()); id = it.getType().name(); }
        m.put("item", id);
        if (it.getAmount() != 1) m.put("amount", it.getAmount());
        ItemMeta meta = it.getItemMeta(), bm = base.getItemMeta();
        if (meta != null && bm != null) {
            if (meta.hasDisplayName() && !meta.displayName().equals(bm.displayName())) m.put("name", Lang.plain(meta.displayName()));
            if (meta.hasLore() && !meta.lore().equals(bm.lore())) {
                List<String> l = new ArrayList<>();
                for (Component c : meta.lore()) l.add(Lang.plain(c));
                m.put("lore", l);
            }
            Map<String, Object> ench = new LinkedHashMap<>();
            for (Map.Entry<Enchantment, Integer> e : meta.getEnchants().entrySet()) {
                if (bm.getEnchantLevel(e.getKey()) != e.getValue()) {
                    NamespacedKey k = e.getKey().getKey();
                    ench.put(k.getNamespace().equals("minecraft") ? k.getKey() : k.toString(), e.getValue());
                }
            }
            if (!ench.isEmpty()) m.put("enchants", ench);
            if (meta.hasItemModel() && !meta.getItemModel().equals(bm.hasItemModel() ? bm.getItemModel() : null)) m.put("model", meta.getItemModel().toString());
            if (meta.hasEquippable() && meta.getEquippable().getModel() != null
                    && !(bm.hasEquippable() && meta.getEquippable().getModel().equals(bm.getEquippable().getModel())))
                m.put("armor-model", meta.getEquippable().getModel().toString());
            if (meta instanceof LeatherArmorMeta lm && !lm.getColor().equals(((LeatherArmorMeta) bm).getColor()))
                m.put("color", String.format("#%06X", lm.getColor().asRGB()));
            if (meta instanceof PotionMeta pm) {
                if (pm.getBasePotionType() != null && pm.getBasePotionType() != ((PotionMeta) bm).getBasePotionType())
                    m.put("potion", pm.getBasePotionType().name().toLowerCase(Locale.ROOT));
                if (pm.hasColor()) m.put("color", String.format("#%06X", pm.getColor().asRGB()));
            }
            if (meta.isUnbreakable() && !bm.isUnbreakable()) m.put("unbreakable", true);
            if (meta instanceof org.bukkit.inventory.meta.Damageable dm && dm.hasDamage() && dm.getDamage() > 0) m.put("damage", dm.getDamage());
            if (meta.hasEnchantmentGlintOverride() && Boolean.TRUE.equals(meta.getEnchantmentGlintOverride()) && !bm.hasEnchantmentGlintOverride()) m.put("glow", true);
            String ab = meta.getPersistentDataContainer().get(ABILITY, PersistentDataType.STRING);
            if (ab != null) m.put("ability", ab);
        }
        ItemStack back = parse(m);
        if (back == null || !back.isSimilar(it)) {       // something the text form cannot hold
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("item", id);
            raw.put("data", Base64.getEncoder().encodeToString(it.serializeAsBytes()));
            return raw;
        }
        return oneLine(m);
    }

    /** A map becomes one line when it has nothing that needs spaces (name, lore). */
    private static Object oneLine(Map<String, Object> m) {
        if (m.containsKey("name") || m.containsKey("lore")) return m;
        StringBuilder sb = new StringBuilder(String.valueOf(m.get("item")));
        if (m.containsKey("amount")) sb.append('*').append(m.get("amount"));
        for (Map.Entry<String, Object> e : m.entrySet()) {
            switch (e.getKey()) {
                case "item", "amount" -> {}
                case "enchants" -> { for (Map.Entry<?, ?> x : ((Map<?, ?>) e.getValue()).entrySet()) sb.append(" enchant:").append(x.getKey()).append(':').append(x.getValue()); }
                case "unbreakable", "glow" -> sb.append(' ').append(e.getKey());
                default -> sb.append(' ').append(e.getKey()).append(':').append(e.getValue());
            }
        }
        return sb.toString();
    }

    public static String abilityOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(ABILITY, PersistentDataType.STRING);
    }

    public static String lobbyActionOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(LOBBY, PersistentDataType.STRING);
    }
}
