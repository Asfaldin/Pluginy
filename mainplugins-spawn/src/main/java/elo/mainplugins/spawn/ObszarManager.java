package elo.mainplugins.spawn;

import elo.mainplugins.core.api.ObszarService;
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
import java.util.UUID;

/**
 * Dowolna liczba nazwanych, chronionych obszarów - spawn to tylko jeden z nich (patrz
 * javadoc Obszar), każdy zaznaczany tą samą różdżką i z własnymi przełącznikami mobów
 * pasywnych/agresywnych. Docelowo ma z tego korzystać też przyszły system warpów,
 * zamiast pisać ochronę terenu od nowa dla każdego nowego miejsca.
 *
 * Sama definicja/zarządzanie obszarami żyje tutaj; cała rzeczywista ochrona terenu
 * (co dokładnie jest zablokowane wewnątrz obszaru) to osobna klasa - patrz
 * {@link ObszarProtectionManager} - tak samo jak IslandManager/IslandProtectionManager
 * w mainplugins-skyblock.
 *
 * Różdżka jest "przełączalna" - /@obszar wand <nazwa> pamięta PER GRACZA, który obszar
 * aktualnie edytuje jego różdżka (edytowanyObszar), więc kilku adminów może jednocześnie
 * zaznaczać różne obszary bez wchodzenia sobie w drogę.
 */
public class ObszarManager implements Listener, ObszarService {

    private final Plugin plugin;
    private final File plikObszarow;
    private final FileConfiguration configObszarow;
    private final NamespacedKey wandKey;

    private final Map<String, Obszar> obszary = new HashMap<>();
    private final Map<UUID, String> edytowanyObszar = new HashMap<>();
    private final Map<UUID, BukkitTask> aktywnePodglady = new HashMap<>();

    public ObszarManager(Plugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "obszar_wand");

        this.plikObszarow = new File(plugin.getDataFolder(), "obszary.yml");
        if (!plikObszarow.exists()) {
            plikObszarow.getParentFile().mkdirs();
            try { plikObszarow.createNewFile(); } catch (IOException ignored) {}
        }
        this.configObszarow = YamlConfiguration.loadConfiguration(plikObszarow);
        wczytaj();
    }

    // ==================================================== Wczytywanie/zapis ====

    private void wczytaj() {
        ConfigurationSection sekcja = configObszarow.getConfigurationSection("obszary");
        if (sekcja == null) return;

        for (String nazwa : sekcja.getKeys(false)) {
            String path = "obszary." + nazwa + ".";
            Obszar obszar = new Obszar();

            if (configObszarow.contains(path + "world")) {
                World world = Bukkit.getWorld(configObszarow.getString(path + "world"));
                if (world != null) {
                    obszar.rog1 = new Location(world, configObszarow.getInt(path + "x1"), configObszarow.getInt(path + "y1"), configObszarow.getInt(path + "z1"));
                    obszar.rog2 = new Location(world, configObszarow.getInt(path + "x2"), configObszarow.getInt(path + "y2"), configObszarow.getInt(path + "z2"));
                }
            }
            obszar.mobyPasywneDozwolone = configObszarow.getBoolean(path + "moby-pasywne", true);
            obszar.mobyAgresywneDozwolone = configObszarow.getBoolean(path + "moby-agresywne", false);
            obszar.rybyDozwolone = configObszarow.getBoolean(path + "ryby-dozwolone", false);

            obszary.put(nazwa, obszar);
        }
    }

    private void zapisz() {
        configObszarow.set("obszary", null); // czyścimy stare wpisy, żeby usunięte obszary nie zostawały w pliku
        for (Map.Entry<String, Obszar> wpis : obszary.entrySet()) {
            String path = "obszary." + wpis.getKey() + ".";
            Obszar obszar = wpis.getValue();

            if (obszar.maObaRogi()) {
                configObszarow.set(path + "world", obszar.rog1.getWorld().getName());
                configObszarow.set(path + "x1", obszar.rog1.getBlockX());
                configObszarow.set(path + "y1", obszar.rog1.getBlockY());
                configObszarow.set(path + "z1", obszar.rog1.getBlockZ());
                configObszarow.set(path + "x2", obszar.rog2.getBlockX());
                configObszarow.set(path + "y2", obszar.rog2.getBlockY());
                configObszarow.set(path + "z2", obszar.rog2.getBlockZ());
            }
            configObszarow.set(path + "moby-pasywne", obszar.mobyPasywneDozwolone);
            configObszarow.set(path + "moby-agresywne", obszar.mobyAgresywneDozwolone);
            configObszarow.set(path + "ryby-dozwolone", obszar.rybyDozwolone);
        }

        try {
            configObszarow.save(plikObszarow);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie można zapisać obszary.yml: " + e.getMessage());
        }
    }

    // ======================================================== Zarządzanie ====

    public void usun(Player player, String nazwa) {
        if (obszary.remove(nazwa) == null) {
            player.sendMessage(Component.text("Nie ma obszaru o nazwie \"" + nazwa + "\".", NamedTextColor.RED));
            return;
        }
        zapisz();
        player.sendMessage(Component.text("Usunięto obszar \"" + nazwa + "\".", NamedTextColor.YELLOW));
    }

    public void listuj(Player player) {
        if (obszary.isEmpty()) {
            player.sendMessage(Component.text("Nie ma jeszcze żadnych zdefiniowanych obszarów.", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text("=== Obszary chronione ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (Map.Entry<String, Obszar> wpis : obszary.entrySet()) {
            player.sendMessage(Component.text(" - " + wpis.getKey() + ": ", NamedTextColor.YELLOW)
                    .append(Component.text(wpis.getValue().opisRozmiaru(), NamedTextColor.GRAY)));
        }
    }

    public void info(Player player, String nazwa) {
        Obszar obszar = obszary.get(nazwa);
        if (obszar == null) {
            player.sendMessage(Component.text("Nie ma obszaru o nazwie \"" + nazwa + "\".", NamedTextColor.RED));
            return;
        }
        String swiat = obszar.maObaRogi() ? obszar.rog1.getWorld().getName() : "-";
        player.sendMessage(Component.text("Obszar \"" + nazwa + "\": ", NamedTextColor.YELLOW)
                .append(Component.text(obszar.opisRozmiaru() + " bloków, świat " + swiat, NamedTextColor.GRAY)));
        player.sendMessage(Component.text("Moby pasywne: " + (obszar.mobyPasywneDozwolone ? "wł." : "wył.")
                + "   Moby agresywne: " + (obszar.mobyAgresywneDozwolone ? "wł." : "wył.")
                + "   Łowisko: " + (obszar.rybyDozwolone ? "wł." : "wył."), NamedTextColor.GRAY));
    }

    public void ustawMoby(Player player, String nazwa, boolean pasywne, boolean dozwolone) {
        Obszar obszar = obszary.computeIfAbsent(nazwa, k -> new Obszar());
        if (pasywne) obszar.mobyPasywneDozwolone = dozwolone;
        else obszar.mobyAgresywneDozwolone = dozwolone;
        zapisz();
        player.sendMessage(Component.text("Moby " + (pasywne ? "pasywne" : "agresywne") + " w obszarze \"" + nazwa + "\": "
                + (dozwolone ? "włączone" : "wyłączone"), NamedTextColor.GREEN));
    }

    /** Włącza/wyłącza obszar jako "łowisko" (patrz {@link #jestLowiskiem(Location)}) - komenda /@obszar ryby. */
    public void ustawRyby(Player player, String nazwa, boolean dozwolone) {
        Obszar obszar = obszary.computeIfAbsent(nazwa, k -> new Obszar());
        obszar.rybyDozwolone = dozwolone;
        zapisz();
        player.sendMessage(Component.text("Łowisko w obszarze \"" + nazwa + "\": "
                + (dozwolone ? "włączone" : "wyłączone"), NamedTextColor.GREEN));
    }

    // ============================================================ Różdżka ====

    private ItemStack stworzRozdzke() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Różdżka Obszaru", NamedTextColor.GOLD, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("LPM bloku - ustaw róg 1", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("PPM bloku - ustaw róg 2", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Edytuje obszar wybrany przez /@obszar wand <nazwa>", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean jestRozdzka(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /** Daje graczowi różdżkę i pamięta, że jego kolejne kliknięcia mają edytować obszar `nazwa` (tworząc go, jeśli jeszcze nie istnieje). */
    public void dajRozdzke(Player player, String nazwa) {
        obszary.computeIfAbsent(nazwa, k -> new Obszar());
        edytowanyObszar.put(player.getUniqueId(), nazwa);
        player.getInventory().addItem(stworzRozdzke());
        player.sendMessage(Component.text("Różdżka edytuje teraz obszar \"" + nazwa + "\".", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onWandClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!jestRozdzka(player.getInventory().getItemInMainHand())) return;

        event.setCancelled(true); // różdżka nie ma nic wspólnego z realnym kopaniem/stawianiem bloków
        if (!player.hasPermission("mainplugins.spawn.admin")) return;

        String nazwa = edytowanyObszar.get(player.getUniqueId());
        if (nazwa == null) {
            player.sendMessage(Component.text("Ta różdżka nie edytuje żadnego obszaru - użyj /@obszar wand <nazwa>.", NamedTextColor.RED));
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) return;

        Obszar obszar = obszary.computeIfAbsent(nazwa, k -> new Obszar());
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            obszar.rog1 = block.getLocation();
            player.sendMessage(Component.text("[" + nazwa + "] Róg 1 ustawiony: " + opisLokalizacji(obszar.rog1), NamedTextColor.GREEN));
        } else {
            obszar.rog2 = block.getLocation();
            player.sendMessage(Component.text("[" + nazwa + "] Róg 2 ustawiony: " + opisLokalizacji(obszar.rog2), NamedTextColor.GREEN));
        }

        if (obszar.maObaRogi()) {
            if (!obszar.rog1.getWorld().equals(obszar.rog2.getWorld())) {
                player.sendMessage(Component.text("Uwaga: oba rogi muszą być w tym samym świecie - popraw jeden z nich!", NamedTextColor.RED));
            } else {
                player.sendMessage(Component.text("Obszar \"" + nazwa + "\" aktywny (" + obszar.opisRozmiaru() + ").", NamedTextColor.GREEN, TextDecoration.BOLD));
            }
        }
        zapisz();
    }

    private String opisLokalizacji(Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    // ======================================================== Podgląd granic ====

    private static final double ODSTEP_PUNKTOW = 1.0; // gęstość siatki na ścianach - 1 blok, dopóki nie uderzymy w limit poniżej
    private static final int MAX_PUNKTOW_NA_OS = 20; // zabezpieczenie na oś (bardzo duży obszar dostaje grubszą siatkę zamiast eksplodować liczbą cząsteczek)
    private static final int CZAS_TRWANIA_TICKOW = 20 * 20; // 20 sekund
    private static final int ODSTEP_ODSWIEZEN_TICKOW = 8; // cząsteczki same gasną - to tylko jak często je odtwarzamy od nowa

    /**
     * Czysto wizualny obrys granic obszaru - siatka cząsteczek na WSZYSTKICH 6 ścianach
     * prostopadłościanu (nie tylko krawędziach), żeby przy dużym obszarze od razu było
     * widać cały jego kształt, a nie tylko cienkie linie po bokach. Krawędzie same
     * wychodzą jaśniejsze, bo tam stykają się dwie ściany naraz - podwójna gęstość za darmo.
     * Widoczne WYŁĄCZNIE dla wywołującego gracza ({@link Player#spawnParticle}, a nie
     * broadcast do świata), znika samo po 20 sekundach. Zero wpływu na ochronę - to tylko
     * podpowiedź "gdzie dokładnie są granice".
     */
    public void pokazGranice(Player player, String nazwa) {
        Obszar obszar = obszary.get(nazwa);
        if (obszar == null || !obszar.maObaRogi()) {
            player.sendMessage(Component.text("Obszar \"" + nazwa + "\" nie ma jeszcze zaznaczonych obu rogów.", NamedTextColor.RED));
            return;
        }

        BukkitTask poprzedni = aktywnePodglady.remove(player.getUniqueId());
        if (poprzedni != null) poprzedni.cancel();

        List<Location> punkty = scianyObszaru(obszar);
        // End Rod na wierzchu (co 8. punkt) - ma naturalny, świecący blask, którego samo
        // kolorowe dust nie daje - dorzucone rzadko, żeby nie podwoić całego ruchu cząsteczek.
        Particle.DustOptions kolor = new Particle.DustOptions(Color.fromRGB(0, 225, 255), 2.0f);

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
        player.sendMessage(Component.text("Pokazuję granice obszaru \"" + nazwa + "\" przez 20 sekund (widoczne tylko dla Ciebie).", NamedTextColor.AQUA));
    }

    /**
     * Siatka punktów na wszystkich 6 ścianach prostopadłościanu obejmującego CAŁE bloki
     * obszaru (nie ich środki). Gęstość na każdej osi liczona z jej realnej długości i
     * ograniczona {@link #MAX_PUNKTOW_NA_OS}, żeby bardzo duży obszar dostawał grubszą
     * (ale ograniczoną liczbowo) siatkę zamiast zalewać gracza cząsteczkami bez limitu.
     */
    private List<Location> scianyObszaru(Obszar obszar) {
        World world = obszar.rog1.getWorld();
        double minX = Math.min(obszar.rog1.getBlockX(), obszar.rog2.getBlockX());
        double maxX = Math.max(obszar.rog1.getBlockX(), obszar.rog2.getBlockX()) + 1;
        double minY = Math.min(obszar.rog1.getBlockY(), obszar.rog2.getBlockY());
        double maxY = Math.max(obszar.rog1.getBlockY(), obszar.rog2.getBlockY()) + 1;
        double minZ = Math.min(obszar.rog1.getBlockZ(), obszar.rog2.getBlockZ());
        double maxZ = Math.max(obszar.rog1.getBlockZ(), obszar.rog2.getBlockZ()) + 1;

        int segX = segmentowNaOsi(maxX - minX);
        int segY = segmentowNaOsi(maxY - minY);
        int segZ = segmentowNaOsi(maxZ - minZ);

        List<Location> punkty = new ArrayList<>();

        // Ściana zachodnia/wschodnia - stałe X, siatka po Y/Z
        for (double x : new double[]{minX, maxX}) {
            for (int iy = 0; iy <= segY; iy++) {
                double y = minY + (maxY - minY) * iy / segY;
                for (int iz = 0; iz <= segZ; iz++) {
                    double z = minZ + (maxZ - minZ) * iz / segZ;
                    punkty.add(new Location(world, x, y, z));
                }
            }
        }
        // Podłoga/sufit - stałe Y, siatka po X/Z
        for (double y : new double[]{minY, maxY}) {
            for (int ix = 0; ix <= segX; ix++) {
                double x = minX + (maxX - minX) * ix / segX;
                for (int iz = 0; iz <= segZ; iz++) {
                    double z = minZ + (maxZ - minZ) * iz / segZ;
                    punkty.add(new Location(world, x, y, z));
                }
            }
        }
        // Ściana północna/południowa - stałe Z, siatka po X/Y
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

    /** Nazwy wszystkich zdefiniowanych obszarów - pod podpowiedzi Tab (patrz SpawnCommands#onTabComplete). */
    public java.util.Set<String> nazwyObszarow() {
        return obszary.keySet();
    }

    /** Pierwszy obszar (z zaznaczonymi obydwoma rogami), który obejmuje podaną lokalizację - albo null. Używane przez {@link ObszarProtectionManager}. */
    Obszar znajdzObszarPod(Location loc) {
        for (Obszar obszar : obszary.values()) {
            if (obszar.zawiera(loc)) return obszar;
        }
        return null;
    }

    /**
     * {@inheritDoc} Implementacja {@link ObszarService} dla mainplugins-fishing (patrz
     * FishingManager#onFish) - w odróżnieniu od {@link #znajdzObszarPod} nie wystarczy
     * byle jaki obszar, musi mieć jawnie włączoną flagę ryby-dozwolone (/@obszar ryby).
     */
    @Override
    public boolean jestLowiskiem(Location loc) {
        for (Obszar obszar : obszary.values()) {
            if (obszar.rybyDozwolone && obszar.zawiera(loc)) return true;
        }
        return false;
    }
}
