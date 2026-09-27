package elo.mainplugins.core.lang;

import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.PlaceholderService;
import elo.mainplugins.core.util.MoneyFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Implementacja {@link LangService} - warstwy: plik serwera (język), jar (język), plik serwera (en), jar (en). */
public final class LangManager implements LangService {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private static final String ENGLISH = "en";
    private static final List<String> BUNDLED = List.of("en", "pl");

    private final JavaPlugin core;
    private final PlaceholderService placeholders;
    private final Map<String, Plugin> owners = new LinkedHashMap<>();
    private final Map<String, MessageCatalog> catalogs = new HashMap<>();
    private String language;

    public LangManager(JavaPlugin core, PlaceholderService placeholders) {
        this.core = core;
        this.placeholders = placeholders;
        this.language = readLanguage();
        readCurrency();
    }

    /** Znaczek waluty w każdym tekście każdego pluginu: {currency} (albo po polsku {waluta}). */
    private void readCurrency() {
        MoneyFormat.ustawWalute(core.getConfig().getString("currency", "$"));
    }

    private String readLanguage() {
        String code = core.getConfig().getString("language", ENGLISH);
        return code == null || code.isBlank() ? ENGLISH : code.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String language() {
        return language;
    }

    @Override
    public void registerDefaults(Plugin owner) {
        for (String code : BUNDLED) {
            String path = "lang/" + code + ".yml";
            if (owner.getResource(path) != null && !new File(owner.getDataFolder(), path).exists()) {
                owner.saveResource(path, false);
            }
        }
        owners.put(owner.getName(), owner);
        catalogs.put(owner.getName(), build(owner));
    }

    private MessageCatalog build(Plugin owner) {
        List<Map<String, String>> layers = new ArrayList<>();
        layers.add(fromDisk(owner, language));
        layers.add(fromJar(owner, language));
        if (!language.equals(ENGLISH)) {
            layers.add(fromDisk(owner, ENGLISH));
            layers.add(fromJar(owner, ENGLISH));
        }
        return new MessageCatalog(layers, msg -> owner.getLogger().warning(msg));
    }

    private Map<String, String> fromDisk(Plugin owner, String code) {
        File file = new File(owner.getDataFolder(), "lang/" + code + ".yml");
        return file.exists() ? MessageCatalog.flatten(YamlConfiguration.loadConfiguration(file)) : Map.of();
    }

    private Map<String, String> fromJar(Plugin owner, String code) {
        InputStream in = owner.getResource("lang/" + code + ".yml");
        if (in == null) return Map.of();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return MessageCatalog.flatten(YamlConfiguration.loadConfiguration(reader));
        } catch (IOException e) {
            owner.getLogger().warning("Could not read bundled lang/" + code + ".yml: " + e.getMessage());
            return Map.of();
        }
    }

    private String text(Plugin owner, String key, Map<String, String> placeholdersMap) {
        MessageCatalog catalog = catalogs.get(owner.getName());
        if (catalog == null) {
            core.getLogger().warning(owner.getName() + " asked for message '" + key
                    + "' without calling LangService.registerDefaults first.");
            return key;
        }
        String currency = MoneyFormat.waluta();
        return catalog.resolve(key, placeholdersMap).replace("{currency}", currency).replace("{waluta}", currency);
    }

    @Override
    public Component msg(Plugin owner, String key, Map<String, String> placeholdersMap) {
        return SERIALIZER.deserialize(text(owner, key, placeholdersMap));
    }

    @Override
    public void send(CommandSender to, Plugin owner, String key, Map<String, String> placeholdersMap) {
        String text = text(owner, key, placeholdersMap);
        if (to instanceof Player player) text = placeholders.apply(player, text);
        to.sendMessage(SERIALIZER.deserialize(text));
    }

    @Override
    public void reload() {
        core.reloadConfig();
        language = readLanguage();
        readCurrency();
        for (Plugin owner : owners.values()) catalogs.put(owner.getName(), build(owner));
    }
}
