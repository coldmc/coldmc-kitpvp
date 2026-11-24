package se.coldmc.kitpvp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Every text a player or admin sees comes from lang.yml. Nothing is hard-coded. */
public final class Lang {
    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.builder()
            .character('&').hexColors().hexCharacter('#').build();
    private YamlConfiguration cfg = new YamlConfiguration();

    public void load(KitPvP plugin) {
        File f = new File(plugin.getDataFolder(), "lang.yml");
        if (!f.exists()) plugin.saveResource("lang.yml", false);
        cfg = YamlConfiguration.loadConfiguration(f);
        // Keys added by a newer jar are filled in from the bundled file, so a message is never missing.
        YamlConfiguration def = YamlConfiguration.loadConfiguration(
                new InputStreamReader(plugin.getResource("lang.yml"), StandardCharsets.UTF_8));
        boolean added = false;
        for (String k : def.getKeys(true)) {
            if (!def.isConfigurationSection(k) && !cfg.contains(k)) { cfg.set(k, def.get(k)); added = true; }
        }
        if (added) try { cfg.save(f); } catch (Exception e) { plugin.getLogger().warning("lang.yml: " + e.getMessage()); }
    }

    private static String fill(String s, Object... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("%" + kv[i] + "%", String.valueOf(kv[i + 1]));
        return s;
    }

    public boolean has(String key) { return cfg.isString(key); }

    /** The raw text of a key with placeholders filled, no prefix. */
    public String raw(String key, Object... kv) {
        String s = cfg.getString(key);
        return fill(s == null ? key : s, kv);
    }

    public List<String> rawList(String key, Object... kv) {
        List<String> out = new ArrayList<>();
        if (cfg.isList(key)) for (String s : cfg.getStringList(key)) out.add(fill(s, kv));
        else if (cfg.isString(key)) for (String s : cfg.getString(key).split("\n")) out.add(fill(s, kv));
        return out;
    }

    public Component text(String key, Object... kv) { return color(raw(key, kv)); }

    public List<Component> lore(String key, Object... kv) {
        List<Component> out = new ArrayList<>();
        for (String s : rawList(key, kv)) out.add(color(s));
        return out;
    }

    /** Chat message with the prefix. A list sends one line each, without the prefix. */
    public void send(CommandSender to, String key, Object... kv) {
        if (to == null) return;
        if (cfg.isList(key)) { for (String s : rawList(key, kv)) to.sendMessage(color(s)); return; }
        String s = raw(key, kv);
        if (s.isEmpty()) return;
        if (s.startsWith("!")) { to.sendMessage(color(s.substring(1))); return; }   // "!" = no prefix
        to.sendMessage(color(cfg.getString("Prefix", "") + s));
    }

    public void actionBar(Player p, String key, Object... kv) {
        String s = raw(key, kv);
        if (!s.isEmpty()) p.sendActionBar(color(s));
    }

    public static Component color(String s) {
        return AMP.deserialize(s == null ? "" : s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static String plain(Component c) { return c == null ? "" : AMP.serialize(c); }
}
