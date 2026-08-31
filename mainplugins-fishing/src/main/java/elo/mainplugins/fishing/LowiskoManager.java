package elo.mainplugins.fishing;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dowolna liczba nazwanych stref łowisk - WŁASNY, całkowicie niezależny system w
 * mainplugins-fishing (świadomie zduplikowany mechanizm zaznaczania różdżką, patrz
 * ObszarManager w mainplugins-spawn - stamtąd NIE reużywany, bo ten plugin idzie na
 * sprzedaż i nie powinien wiedzieć nic o rybach, user 2026-08-31c). Zastępuje dawną flagę
 * ryby-dozwolone (/@obszar ryby, usunięta) - w odróżnieniu od niej każde łowisko ma teraz
 * WŁASNĄ nazwę i WŁASNĄ listę gatunków (patrz Lowisko, gatunkiDlaLowiska), więc różne
 * łowiska mogą łapać różne ryby z różnymi szansami, zamiast jednego wspólnego wyłącznika.
 *
 * lowiska.yml trzyma WSZYSTKO per łowisko w jednym miejscu - geometrię (rogi, zarządzane
 * przez różdżkę) I listę gatunków (ręcznie edytowalną w pliku) - dzięki temu "wszystko
 * związane z rybami" (user) faktycznie zostaje w jednym pliku jednego pluginu.
 */
public class LowiskoManager implements Listener {

    private final Plugin plugin;
    private final File plikLowisk;
    private final FileConfiguration configLowisk;
    private final NamespacedKey wandKey;

    private final Map<String, Lowisko> lowiska = new HashMap<>();
    private final Map<UUID, String> edytowaneLowisko = new HashMap<>();
    private final Map<UUID, BukkitTask> aktywnePodglady = new HashMap<>();

    public LowiskoManager(Plugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "lowisko_wand");

        this.plikLowisk = new File(plugin.getDataFolder(), "lowiska.yml");
        if (!plikLowisk.exists()) {
            plikLowisk.getParentFile().mkdirs();
            try { plikLowisk.createNewFile(); } catch (IOException ignored) {}
        }
        this.configLowisk = YamlConfiguration.loadConfiguration(plikLowisk);
        wczytaj();
    }

    // ==================================================== Wczytywanie/zapis ====

    private void wczytaj() {
        ConfigurationSection sekcja = configLowisk.getConfigurationSection("lowiska");
        if (sekcja == null) return;

        for (String nazwa : sekcja.getKeys(false)) {
            String path = "lowiska." + nazwa + ".";
            Lowisko lowisko = new Lowisko();

            if (configLowisk.contains(path + "world")) {
                World world = Bukkit.getWorld(configLowisk.getString(path + "world"));
                if (world != null) {
                    lowisko.rog1 = new Location(world, configLowisk.getInt(path + "x1"), configLowisk.getInt(path + "y1"), configLowisk.getInt(path + "z1"));
                    lowisko.rog2 = new Location(world, configLowisk.getInt(path + "x2"), configLowisk.getInt(path + "y2"), configLowisk.getInt(path + "z2"));
                }
            }

            ConfigurationSection gatunkiSekcja = configLowisk.getConfigurationSection(path + "gatunki");
            if (gatunkiSekcja != null) {
                for (String customId : gatunkiSekcja.getKeys(false)) {
                    lowisko.gatunki.put(customId, gatunkiSekcja.getInt(customId, 1));
                }
            }

            lowiska.put(nazwa, lowisko);
        }
    }

    private void zapisz() {
        configLowisk.set("lowiska", null); // czyścimy stare wpisy, żeby usunięte łowiska nie zostawały w pliku
        for (Map.Entry<String, Lowisko> wpis : lowiska.entrySet()) {
            String path = "lowiska." + wpis.getKey() + ".";
            Lowisko lowisko = wpis.getValue();

            if (lowisko.maObaRogi()) {
                configLowisk.set(path + "world", lowisko.rog1.getWorld().getName());
                configLowisk.set(path + "x1", lowisko.rog1.getBlockX());
                configLowisk.set(path + "y1", lowisko.rog1.getBlockY());
                configLowisk.set(path + "z1", lowisko.rog1.getBlockZ());
                configLowisk.set(path + "x2", lowisko.rog2.getBlockX());
                configLowisk.set(path + "y2", lowisko.rog2.getBlockY());
                configLowisk.set(path + "z2", lowisko.rog2.getBlockZ());
            }
            for (Map.Entry<String, Integer> gatunek : lowisko.gatunki.entrySet()) {
                configLowisk.set(path + "gatunki." + gatunek.getKey(), gatunek.getValue());
            }
        }

        try {
            configLowisk.save(plikLowisk);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie można zapisać lowiska.yml: " + e.getMessage());
        }
    }

    /** Wczytuje lowiska.yml na nowo z dysku - pod /@reloadfishing (żeby ręczne zmiany w liście gatunków działały bez restartu). */
    public void przeladuj() {
        lowiska.clear();
        YamlConfiguration swiezy = YamlConfiguration.loadConfiguration(plikLowisk);
        configLowisk.getKeys(false).forEach(k -> configLowisk.set(k, null));
        for (String klucz : swiezy.getKeys(false)) configLowisk.set(klucz, swiezy.get(klucz));
        wczytaj();
    }

    // ======================================================== Zarządzanie ====

    public void usun(Player player, String nazwa) {
        if (lowiska.remove(nazwa) == null) {
            player.sendMessage(Component.text("Nie ma łowiska o nazwie \"" + nazwa + "\".", NamedTextColor.RED));
            return;
        }
        zapisz();
        player.sendMessage(Component.text("Usunięto łowisko \"" + nazwa + "\".", NamedTextColor.YELLOW));
    }

    public void listuj(Player player) {
        if (lowiska.isEmpty()) {
            player.sendMessage(Component.text("Nie ma jeszcze żadnych zdefiniowanych łowisk.", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text("=== Łowiska ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (Map.Entry<String, Lowisko> wpis : lowiska.entrySet()) {
            player.sendMessage(Component.text(" - " + wpis.getKey() + ": ", NamedTextColor.YELLOW)
                    .append(Component.text(wpis.getValue().opisRozmiaru(), NamedTextColor.GRAY)));
        }
    }

    public void info(Player player, String nazwa) {
        Lowisko lowisko = lowiska.get(nazwa);
        if (lowisko == null) {
            player.sendMessage(Component.text("Nie ma łowiska o nazwie \"" + nazwa + "\".", NamedTextColor.RED));
            return;
        }
        String swiat = lowisko.maObaRogi() ? lowisko.rog1.getWorld().getName() : "-";
        player.sendMessage(Component.text("Łowisko \"" + nazwa + "\": ", NamedTextColor.YELLOW)
                .append(Component.text(lowisko.opisRozmiaru() + " bloków, świat " + swiat, NamedTextColor.GRAY)));
        if (lowisko.gatunki.isEmpty()) {
            player.sendMessage(Component.text("Gatunki: pełna domyślna pula (brak własnej listy w lowiska.yml).", NamedTextColor.GRAY));
        } else {
            player.sendMessage(Component.text("Gatunki (" + lowisko.gatunki.size() + "): " + String.join(", ", lowisko.gatunki.keySet()), NamedTextColor.GRAY));
        }
    }

    // ============================================================ Różdżka ====

    private ItemStack stworzRozdzke() {
        ItemStack item = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Różdżka Łowiska", NamedTextColor.AQUA, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("LPM bloku - ustaw róg 1", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("PPM bloku - ustaw róg 2", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Edytuje łowisko wybrane przez /@lowisko wand <nazwa>", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean jestRozdzka(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /** Daje graczowi różdżkę i pamięta, że jego kolejne kliknięcia mają edytować łowisko `nazwa` (tworząc je, jeśli jeszcze nie istnieje). */
    public void dajRozdzke(Player player, String nazwa) {
        lowiska.computeIfAbsent(nazwa, k -> new Lowisko());
        edytowaneLowisko.put(player.getUniqueId(), nazwa);
        player.getInventory().addItem(stworzRozdzke());
        player.sendMessage(Component.text("Różdżka edytuje teraz łowisko \"" + nazwa + "\".", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onWandClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!jestRozdzka(player.getInventory().getItemInMainHand())) return;

        event.setCancelled(true); // różdżka nie ma nic wspólnego z realnym kopaniem/stawianiem bloków
        if (!player.hasPermission("mainplugins.fishing.admin")) return;

        String nazwa = edytowaneLowisko.get(player.getUniqueId());
        if (nazwa == null) {
            player.sendMessage(Component.text("Ta różdżka nie edytuje żadnego łowiska - użyj /@lowisko wand <nazwa>.", NamedTextColor.RED));
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) return;

        Lowisko lowisko = lowiska.computeIfAbsent(nazwa, k -> new Lowisko());
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            lowisko.rog1 = block.getLocation();
            player.sendMessage(Component.text("[" + nazwa + "] Róg 1 ustawiony: " + opisLokalizacji(lowisko.rog1), NamedTextColor.GREEN));
        } else {
            lowisko.rog2 = block.getLocation();
            player.sendMessage(Component.text("[" + nazwa + "] Róg 2 ustawiony: " + opisLokalizacji(lowisko.rog2), NamedTextColor.GREEN));
        }

        if (lowisko.maObaRogi()) {
            if (!lowisko.rog1.getWorld().equals(lowisko.rog2.getWorld())) {
                player.sendMessage(Component.text("Uwaga: oba rogi muszą być w tym samym świecie - popraw jeden z nich!", NamedTextColor.RED));
            } else {
                player.sendMessage(Component.text("Łowisko \"" + nazwa + "\" aktywne (" + lowisko.opisRozmiaru() + ").", NamedTextColor.GREEN, TextDecoration.BOLD));
            }
        }
        zapisz();
    }

    private String opisLokalizacji(Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    // ======================================================== Podgląd granic ====

    private static final double ODSTEP_PUNKTOW = 1.0;
    private static final int MAX_PUNKTOW_NA_OS = 20;
    private static final int CZAS_TRWANIA_TICKOW = 20 * 20;
    private static final int ODSTEP_ODSWIEZEN_TICKOW = 8;

    /** Czysto wizualny obrys granic łowiska - patrz ObszarManager.pokazGranice (identyczny mechanizm). */
    public void pokazGranice(Player player, String nazwa) {
        Lowisko lowisko = lowiska.get(nazwa);
        if (lowisko == null || !lowisko.maObaRogi()) {
            player.sendMessage(Component.text("Łowisko \"" + nazwa + "\" nie ma jeszcze zaznaczonych obu rogów.", NamedTextColor.RED));
            return;
        }

        BukkitTask poprzedni = aktywnePodglady.remove(player.getUniqueId());
        if (poprzedni != null) poprzedni.cancel();

        List<Location> punkty = scianyLowiska(lowisko);
        Particle.DustOptions kolor = new Particle.DustOptions(Color.fromRGB(0, 180, 255), 2.0f);

        BukkitRunnable zadanie = new BukkitRunnable() {
            int pozostaloOdswiezen = CZAS_TRWANIA_TICKOW / ODSTEP_ODSWIEZEN_TICKOW;

            @Override
            public void run() {
                if (!player.isOnline() || pozostaloOdswiezen-- <= 0) {
                    aktywnePodglady.remove(player.getUniqueId());
                    cancel();
                    return;
                }
                for (int i = 0; i < punkty.size(); i++) {
                    Location punkt = punkty.get(i);
                    player.spawnParticle(Particle.DUST, punkt, 1, 0, 0, 0, kolor);
                    if (i % 8 == 0) {
                        player.spawnParticle(Particle.END_ROD, punkt, 1, 0.02, 0.02, 0.02, 0);
                    }
                }
            }
        };
        aktywnePodglady.put(player.getUniqueId(), zadanie.runTaskTimer(plugin, 0L, ODSTEP_ODSWIEZEN_TICKOW));
        player.sendMessage(Component.text("Pokazuję granice łowiska \"" + nazwa + "\" przez 20 sekund (widoczne tylko dla Ciebie).", NamedTextColor.AQUA));
    }

    private List<Location> scianyLowiska(Lowisko lowisko) {
        World world = lowisko.rog1.getWorld();
        double minX = Math.min(lowisko.rog1.getBlockX(), lowisko.rog2.getBlockX());
        double maxX = Math.max(lowisko.rog1.getBlockX(), lowisko.rog2.getBlockX()) + 1;
        double minY = Math.min(lowisko.rog1.getBlockY(), lowisko.rog2.getBlockY());
        double maxY = Math.max(lowisko.rog1.getBlockY(), lowisko.rog2.getBlockY()) + 1;
        double minZ = Math.min(lowisko.rog1.getBlockZ(), lowisko.rog2.getBlockZ());
        double maxZ = Math.max(lowisko.rog1.getBlockZ(), lowisko.rog2.getBlockZ()) + 1;

        int segX = segmentowNaOsi(maxX - minX);
        int segY = segmentowNaOsi(maxY - minY);
        int segZ = segmentowNaOsi(maxZ - minZ);

        List<Location> punkty = new ArrayList<>();

        for (double x : new double[]{minX, maxX}) {
            for (int iy = 0; iy <= segY; iy++) {
                double y = minY + (maxY - minY) * iy / segY;
                for (int iz = 0; iz <= segZ; iz++) {
                    double z = minZ + (maxZ - minZ) * iz / segZ;
                    punkty.add(new Location(world, x, y, z));
                }
            }
        }
        for (double y : new double[]{minY, maxY}) {
            for (int ix = 0; ix <= segX; ix++) {
                double x = minX + (maxX - minX) * ix / segX;
                for (int iz = 0; iz <= segZ; iz++) {
                    double z = minZ + (maxZ - minZ) * iz / segZ;
                    punkty.add(new Location(world, x, y, z));
                }
            }
        }
        for (double z : new double[]{minZ, maxZ}) {
            for (int ix = 0; ix <= segX; ix++) {
                double x = minX + (maxX - minX) * ix / segX;
                for (int iy = 0; iy <= segY; iy++) {
                    double y = minY + (maxY - minY) * iy / segY;
                    punkty.add(new Location(world, x, y, z));
                }
            }
        }

        return punkty;
    }

    private int segmentowNaOsi(double dlugosc) {
        return (int) Math.max(1, Math.min(MAX_PUNKTOW_NA_OS, Math.round(dlugosc / ODSTEP_PUNKTOW)));
    }

    // ==================================================================== API dla FishingManager ====

    /** Nazwy wszystkich zdefiniowanych łowisk - pod podpowiedzi Tab. */
    public Set<String> nazwyLowisk() {
        return lowiska.keySet();
    }

    /** Łowisko (z zaznaczonymi obydwoma rogami) obejmujące podaną lokalizację - albo null, jeśli to miejsce nie jest żadnym łowiskiem (wtedy łowienie zostaje czysto wanilijskie, patrz FishingManager.onFish). */
    Lowisko znajdzLowiskoPod(Location loc) {
        for (Lowisko lowisko : lowiska.values()) {
            if (lowisko.zawiera(loc)) return lowisko;
        }
        return null;
    }
}
