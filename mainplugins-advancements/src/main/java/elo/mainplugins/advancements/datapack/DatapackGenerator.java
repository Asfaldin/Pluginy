package elo.mainplugins.advancements.datapack;

import elo.mainplugins.advancements.config.AchievementsConfig;
import elo.mainplugins.advancements.model.AchievementCategory;
import elo.mainplugins.advancements.model.AchievementDef;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Zapisuje datapack z WŁASNYMI osiągnięciami do
 * {@code <świat>/datapacks/mainplugins-osiagniecia/}, żeby pojawiły się w natywnym
 * ekranie ESC → Postępy jako prawdziwe zakładki/drzewka (z natywnym toastem).
 *
 * To NIE jest NMS - generujemy zwykłe pliki JSON, które wczytuje samo waniliowe
 * Minecraft. Każde osiągnięcie ma kryterium {@code minecraft:impossible}, więc
 * jedyny sposób zdobycia to Bukkit API (patrz NativeAdvancementBridge#przyznaj) -
 * plugin pozostaje jedynym źródłem prawdy o postępie.
 *
 * Format datapacka (pack_format, nazwa folderu {@code advancement(s)}, klucz ikony
 * {@code id}/{@code item}) jest dobierany do wersji serwera. Zmiany są widoczne
 * dopiero po {@code /reload} albo restarcie - dlatego generator zwraca informację,
 * czy coś się faktycznie zmieniło.
 */
public final class DatapackGenerator {

    public static final String DIR_NAME = "mainplugins-osiagniecia";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** @param trzebaReload true, gdy zawartość datapacka się zmieniła i wymaga {@code /reload} */
    public record Wynik(boolean wlaczony, boolean trzebaReload, String info) {}

    private final Plugin plugin;

    public DatapackGenerator(Plugin plugin) {
        this.plugin = plugin;
    }

    // ---------- publiczne API ----------

    /** Ścieżka advancementu ({@code namespace:kategoria/id}) - wspólna z NativeAdvancementBridge. */
    @SuppressWarnings("deprecation")
    public static NamespacedKey klucz(String namespace, AchievementDef def) {
        return new NamespacedKey(namespace, sanitize(def.kategoriaId()) + "/" + sanitize(def.id()));
    }

    /**
     * Folder, z którego MC czyta datapacki tego świata: {@code <korzeń poziomu>/datapacks}.
     * Od przebudowy układu świata ("dimensions") {@code World#getWorldFolder()} potrafi
     * zwrócić {@code world/dimensions/minecraft/overworld} - a datapacki są w korzeniu
     * poziomu (tam gdzie {@code level.dat}), nie w folderze wymiaru. Idziemy więc w górę
     * aż do katalogu z {@code level.dat}; jak się nie uda - fallback na worldContainer + nazwę.
     */
    public static File datapacksDir() {
        if (Bukkit.getWorlds().isEmpty()) return null;
        var main = Bukkit.getWorlds().get(0);

        // 1. w górę od folderu świata aż do katalogu z level.dat (korzeń poziomu)
        File probe = main.getWorldFolder();
        for (int i = 0; i < 8 && probe != null; i++) {
            if (new File(probe, "level.dat").isFile()) {
                return new File(probe, "datapacks");
            }
            probe = probe.getParentFile();
        }
        // 2. worldContainer + nazwa świata (klasyczny układ .../world)
        File byName = new File(Bukkit.getWorldContainer(), main.getName());
        if (byName.isDirectory()) {
            return new File(byName, "datapacks");
        }
        // 3. ostateczny fallback - stare zachowanie
        return new File(main.getWorldFolder(), "datapacks");
    }

    public Wynik wygeneruj(AchievementsConfig cfg) {
        File datapacks = datapacksDir();
        if (datapacks == null) {
            return new Wynik(false, false, "brak wczytanego świata - pomijam datapack");
        }
        File baseDir = new File(datapacks, DIR_NAME);
        sprzatnijStareLokalizacje(cfg);

        if (!cfg.datapack().wlaczony()) {
            boolean bylo = baseDir.isDirectory();
            if (bylo) usunRekurencyjnie(baseDir);
            return new Wynik(false, bylo, bylo ? "datapack wyłączony - usunięto folder, zrób /reload" : "datapack wyłączony");
        }

        int[] wer = wersjaMc();
        // Nowy, "rokowy" schemat wersji MC (25.x, 26.x, ...) - stary był zawsze 1.x.y.
        boolean rokowySchemat = wer[0] >= 20;
        int packFormat = packFormat(wer);
        boolean ikonaKluczId = rokowySchemat || (wer[0] == 1 && (wer[1] > 20 || (wer[1] == 20 && wer[2] >= 5)));
        String advDir = (rokowySchemat || (wer[0] == 1 && wer[1] >= 21)) ? "advancement" : "advancements";
        String ns = cfg.datapack().namespace();

        StringBuilder wszystko = new StringBuilder();   // do wykrycia zmiany
        String stareHash = czytajHash(baseDir);

        try {
            File dataDir = new File(baseDir, "data");
            if (dataDir.exists()) usunRekurencyjnie(dataDir);

            String mcmeta = packMcmeta(packFormat);
            wszystko.append(mcmeta);
            zapisz(new File(baseDir, "pack.mcmeta"), mcmeta);

            for (AchievementCategory kat : cfg.kategorie()) {
                String tlo = kat.tloZakladki() != null ? kat.tloZakladki() : cfg.datapack().tloDomyslne();
                String rootJson = rootJson(kat, tlo, ikonaKluczId);
                wszystko.append(rootJson);
                zapisz(new File(baseDir, "data/" + ns + "/" + advDir + "/" + sanitize(kat.id()) + "/root.json"), rootJson);

                for (AchievementDef def : cfg.wgKategorii(kat.id())) {
                    String json = advJson(def, ns, kat.id(), ikonaKluczId);
                    wszystko.append(json);
                    zapisz(new File(baseDir, "data/" + ns + "/" + advDir + "/" + sanitize(kat.id()) + "/" + sanitize(def.id()) + ".json"), json);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udało się zapisać datapacka osiągnięć: " + e.getMessage());
            return new Wynik(true, false, "błąd zapisu: " + e.getMessage());
        }

        String noweHash = Integer.toHexString(wszystko.toString().hashCode());
        zapiszCicho(new File(baseDir, ".mpa-hash"), noweHash);
        boolean zmiana = !noweHash.equals(stareHash);

        return new Wynik(true, zmiana,
                "datapack MC " + wer[0] + "." + wer[1] + "." + wer[2] + " (pack_format " + packFormat + "), "
                        + cfg.osiagniecia().size() + " osiągnięć"
                        + (zmiana ? " - ZMIANA, wpisz /reload lub zrestartuj serwer" : " - bez zmian"));
    }

    // ---------- budowa JSON ----------

    private static String packMcmeta(int packFormat) {
        // Szeroki zakres - nie chcemy zgadywać dokładnego formatu przy każdej nowej wersji MC.
        // MC przyjmuje pack, jeśli jego bieżący format mieści się w [min_inclusive, max_inclusive].
        return "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": " + packFormat + ",\n"
                + "    \"description\": \"Mainplugins - osiagniecia (auto-generowane; nie edytuj recznie)\",\n"
                + "    \"supported_formats\": { \"min_inclusive\": 1, \"max_inclusive\": 9999 }\n"
                + "  }\n"
                + "}\n";
    }

    private static String rootJson(AchievementCategory kat, String tlo, boolean ikonaKluczId) {
        String nazwa = PLAIN.serialize(kat.nazwa());
        return "{\n"
                + "  \"display\": {\n"
                + "    \"icon\": { " + q(ikonaKluczId ? "id" : "item") + ": " + q(materialId(kat.ikona())) + " },\n"
                + "    \"title\": " + q(nazwa) + ",\n"
                + "    \"description\": " + q(nazwa) + ",\n"
                + "    \"frame\": \"task\",\n"
                + "    \"show_toast\": false,\n"
                + "    \"announce_to_chat\": false,\n"
                + "    \"background\": " + q(tlo) + "\n"
                + "  },\n"
                + "  \"criteria\": { \"trigger\": { \"trigger\": \"minecraft:impossible\" } }\n"
                + "}\n";
    }

    private static String advJson(AchievementDef def, String ns, String kategoriaId, boolean ikonaKluczId) {
        String tytul = PLAIN.serialize(def.nazwa());
        StringBuilder opis = new StringBuilder();
        for (Component c : def.opis()) {
            if (opis.length() > 0) opis.append(' ');
            opis.append(PLAIN.serialize(c));
        }
        if (opis.length() == 0) opis.append(tytul);

        return "{\n"
                + "  \"display\": {\n"
                + "    \"icon\": { " + q(ikonaKluczId ? "id" : "item") + ": " + q(materialId(def.ikona())) + " },\n"
                + "    \"title\": " + q(tytul) + ",\n"
                + "    \"description\": " + q(opis.toString()) + ",\n"
                + "    \"frame\": " + q(def.ramka()) + ",\n"
                + "    \"show_toast\": true,\n"
                + "    \"announce_to_chat\": false,\n"
                + "    \"hidden\": " + def.ukryte() + "\n"
                + "  },\n"
                + "  \"parent\": " + q(ns + ":" + sanitize(kategoriaId) + "/root") + ",\n"
                + "  \"criteria\": { \"trigger\": { \"trigger\": \"minecraft:impossible\" } },\n"
                + "  \"requirements\": [ [\"trigger\"] ]\n"
                + "}\n";
    }

    // ---------- wersja / format ----------

    private int[] wersjaMc() {
        String v;
        try {
            v = Bukkit.getMinecraftVersion();   // Paper - "1.21.4"
        } catch (Throwable t) {
            v = Bukkit.getBukkitVersion().split("-")[0];   // "1.21.4-R0.1-SNAPSHOT" -> "1.21.4"
        }
        String[] cz = v.split("\\.");
        int major = bezpieczny(cz, 0, 1);
        int minor = bezpieczny(cz, 1, 20);
        int patch = bezpieczny(cz, 2, 0);
        return new int[]{major, minor, patch};
    }

    private static int bezpieczny(String[] arr, int idx, int domyslny) {
        if (idx >= arr.length) return domyslny;
        try {
            return Integer.parseInt(arr[idx].replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return domyslny;
        }
    }

    /**
     * Numer formatu datapacka dla znanych wersji; dla nowszych - rozsądny domysł.
     * I tak decyduje {@code supported_formats} w pack.mcmeta (zakres 1..9999), więc
     * ta liczba wpływa tylko na komunikat w logu, nie na to, czy MC przyjmie pack.
     */
    private static int packFormat(int[] w) {
        int major = w[0], mn = w[1], pt = w[2];

        // Nowy, "rokowy" schemat (25.x, 26.x, ...) - dokładnego numeru nie znamy z góry.
        if (major >= 20) {
            return 100;
        }
        if (major != 1) {
            return 100;
        }
        if (mn <= 20) {
            if (pt <= 1) return 15;
            if (pt == 2) return 18;
            if (pt <= 4) return 26;
            return 41;                       // 1.20.5 - 1.20.6
        }
        if (mn == 21) {
            return switch (pt) {
                case 0, 1 -> 48;
                case 2, 3 -> 57;
                case 4 -> 61;
                case 5 -> 71;
                case 6, 7, 8 -> 80;
                default -> 88;               // 1.21.9+
            };
        }
        return 90;                            // 1.22+ nieznane
    }

    // ---------- pliki ----------

    private static void zapisz(File plik, String tresc) throws IOException {
        File rodzic = plik.getParentFile();
        if (rodzic != null && !rodzic.isDirectory() && !rodzic.mkdirs()) {
            throw new IOException("nie mozna utworzyc " + rodzic);
        }
        Files.writeString(plik.toPath(), tresc, StandardCharsets.UTF_8);
    }

    private static void zapiszCicho(File plik, String tresc) {
        try {
            zapisz(plik, tresc);
        } catch (IOException ignored) { /* .mpa-hash to tylko optymalizacja - brak jest OK */ }
    }

    private static String czytajHash(File baseDir) {
        File h = new File(baseDir, ".mpa-hash");
        if (!h.isFile()) return null;
        try {
            return Files.readString(h.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** Kasuje datapack zapisany przez starsze wersje pluginu w złym miejscu (folder wymiaru zamiast korzenia poziomu). */
    private void sprzatnijStareLokalizacje(AchievementsConfig cfg) {
        try {
            File dobry = new File(datapacksDir(), DIR_NAME).getCanonicalFile();
            File zle = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "datapacks/" + DIR_NAME).getCanonicalFile();
            if (!dobry.equals(zle) && zle.isDirectory()) {
                usunRekurencyjnie(zle);
                plugin.getLogger().info("Usunięto stary, źle umieszczony datapack: " + zle);
            }
        } catch (Exception ignored) { /* sprzątanie best-effort */ }
    }

    private void usunRekurencyjnie(File dir) {
        if (!dir.exists()) return;
        try (Stream<java.nio.file.Path> s = Files.walk(dir.toPath())) {
            s.sorted(Comparator.reverseOrder()).map(java.nio.file.Path::toFile).forEach(File::delete);
        } catch (IOException e) {
            plugin.getLogger().warning("Sprzatanie datapacka osiagniec: " + e.getMessage());
        }
    }

    // ---------- drobne ----------

    private static String materialId(Material m) {
        if (m == null || !m.isItem()) return "minecraft:book";
        return m.getKey().toString();
    }

    private static String q(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + '"';
    }

    /** Do części klucza NamespacedKey: dozwolone tylko [a-z0-9_.-]; reszta -> '_'. */
    private static String sanitize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }
}
