package elo.mainplugins.shop;

import elo.mainplugins.core.util.AsyncConfigSaver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Rotacyjny sklep: losuje 5 itemów z puli i wpisuje je do categories/kolekcja.yml,
 * zmieniając ofertę co 14 dni.
 *
 * Nie dotyka GUI ani ShopManagera - przepisuje plik kategorii i woła przeładowanie.
 * Dla reszty sklepu "Kolekcja" wygląda jak każda inna, statyczna kategoria.
 */
public class RotacjaManager {

    /** Ile itemów widocznych naraz w sklepie. */
    private static final int ITEMOW_NA_ROTACJE = 5;

    /** Co ile dni zmiana oferty. */
    private static final int DNI_NA_ROTACJE = 14;

    /**
     * Ile rotacji item "odpoczywa" po tym, jak był w ofercie.
     * Przy puli 53 i 5 na rotację pełny obieg to ~10.6 rotacji, więc 5 to mniej
     * więcej połowa - oferta czuje się świeża, a pula nie musi być ogromna.
     */
    private static final int CHLODZENIE_ROTACJI = 5;

    /**
     * Pierwszy slot-klucz w kolekcja.yml, od którego zaczyna się zapis rotacji.
     * Generator Bruku przeniesiony do stałej oferty w "Rudy i Minerały" — cała
     * kategoria "Kolekcja" jest teraz w 100% rotacyjna, więc zaczynamy od 0.
     */
    private static final int PIERWSZY_SLOT_ROTACYJNY = 0;

    private final Plugin plugin;
    private final ShopManager shopManager;
    private final File plikStanu;
    private final FileConfiguration stan;
    private final AsyncConfigSaver saver;

    /** Wszystkie możliwe itemy - wczytane raz z pula-rotacyjna.yml. */
    private final List<Map<?, ?>> pula = new ArrayList<>();

    /**
     * Ile rotacji zostało do końca chłodzenia danego itemu.
     * Klucz to material+nazwa (bo 5 rogów kóz dzieli ten sam Material).
     */
    private final Map<String, Integer> chlodzenie = new HashMap<>();

    public RotacjaManager(Plugin plugin, ShopManager shopManager) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.plikStanu = new File(plugin.getDataFolder(), "rotacja.yml");
        this.stan = YamlConfiguration.loadConfiguration(plikStanu);
        this.saver = new AsyncConfigSaver(plugin, stan, plikStanu, 30);

        wczytajPule();
        wczytajChlodzenie();
        zaplanujCykl();
    }

    // =========================================================================
    //  WCZYTYWANIE
    // =========================================================================

    private void wczytajPule() {
        File plikPuli = new File(plugin.getDataFolder(), "pula-rotacyjna.yml");
        if (!plikPuli.exists()) {
            plugin.saveResource("pula-rotacyjna.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(plikPuli);
        List<Map<?, ?>> lista = cfg.getMapList("itemy");
        pula.addAll(lista);
        plugin.getLogger().info("Rotacja: wczytano " + pula.size() + " itemow do puli.");
    }

    private void wczytajChlodzenie() {
        ConfigurationSection sekcja = stan.getConfigurationSection("chlodzenie");
        if (sekcja == null) return;
        for (String klucz : sekcja.getKeys(false)) {
            chlodzenie.put(klucz, sekcja.getInt(klucz));
        }
    }

    /** Unikalny klucz itemu - sam Material nie wystarczy, bo 5 rogów kóz to ten sam GOAT_HORN. */
    private String klucz(Map<?, ?> item) {
        return item.get("material") + "|" + item.get("nazwa");
    }

    // =========================================================================
    //  CYKL
    // =========================================================================

    /**
     * Sprawdza raz na godzinę, czy minęło 14 dni od ostatniej rotacji.
     *
     * Czas liczony ze znacznika w pliku, nie z licznika w pamięci - dzięki temu
     * przerwa w działaniu serwera nie "zatrzymuje" rotacji. Jeśli serwer stał
     * wyłączony 3 tygodnie, przy pierwszym starcie od razu wykryje, że pora
     * na nową ofertę.
     */
    private void zaplanujCykl() {
        long ticki = 60L * 60L * 20L;   // godzina
        Bukkit.getScheduler().runTaskTimer(plugin, this::sprawdzCzyPoraNaRotacje, 20L, ticki);
    }

    private void sprawdzCzyPoraNaRotacje() {
        long ostatnia = stan.getLong("ostatnia-rotacja", 0L);
        long terazMs = System.currentTimeMillis();
        long okresMs = DNI_NA_ROTACJE * 24L * 60L * 60L * 1000L;

        if (ostatnia == 0L || terazMs - ostatnia >= okresMs) {
            wykonajRotacje(ostatnia != 0L);   // pierwsza rotacja bez ogłoszenia
        }
    }

    // =========================================================================
    //  LOSOWANIE
    // =========================================================================

    /** @param oglos czy wysłać komunikat na czat (przy pierwszym starcie nie ma po co) */
    public void wykonajRotacje(boolean oglos) {
        // Kandydaci: wszystko, co nie jest na chłodzeniu.
        List<Map<?, ?>> kandydaci = new ArrayList<>();
        for (Map<?, ?> item : pula) {
            if (chlodzenie.getOrDefault(klucz(item), 0) <= 0) kandydaci.add(item);
        }

        // Zabezpieczenie: gdyby ktoś mocno zmniejszył pulę albo podniósł chłodzenie
        // tak, że nie ma z czego losować - bierzemy całą pulę zamiast wywalać błąd.
        if (kandydaci.size() < ITEMOW_NA_ROTACJE) {
            plugin.getLogger().warning("Rotacja: za malo itemow poza chlodzeniem ("
                    + kandydaci.size() + "), losuje z calej puli.");
            kandydaci = new ArrayList<>(pula);
            chlodzenie.clear();
        }

        Collections.shuffle(kandydaci);
        List<Map<?, ?>> wylosowane = kandydaci.subList(0, Math.min(ITEMOW_NA_ROTACJE, kandydaci.size()));

        // Odliczamy chłodzenie wszystkim, potem nakładamy pełne na świeżo wylosowane.
        chlodzenie.replaceAll((k, v) -> Math.max(0, v - 1));
        for (Map<?, ?> item : wylosowane) {
            chlodzenie.put(klucz(item), CHLODZENIE_ROTACJI);
        }

        String blad = wpiszDoKategorii(wylosowane);
        if (blad != null) {
            plugin.getLogger().severe("Rotacja: nie udalo sie zapisac kategorii - " + blad);
            return;
        }

        stan.set("ostatnia-rotacja", System.currentTimeMillis());
        for (Map.Entry<String, Integer> e : chlodzenie.entrySet()) {
            stan.set("chlodzenie." + e.getKey(), e.getValue() > 0 ? e.getValue() : null);
        }
        saver.oznaczZmiane();

        shopManager.przeladujKonfiguracje();

        if (oglos) oglosNaCzacie(wylosowane);
        plugin.getLogger().info("Rotacja: nowa oferta w kategorii Kolekcja.");
    }

    // =========================================================================
    //  ZAPIS DO KATEGORII
    // =========================================================================

    /**
     * Przepisuje sloty rotacyjne w categories/kolekcja.yml (cała kategoria jest
     * rotacyjna — Generator Bruku ma teraz stałe miejsce w "Rudy i Minerały").
     *
     * @return komunikat błędu albo null przy powodzeniu
     */
    private String wpiszDoKategorii(List<Map<?, ?>> wylosowane) {
        File plik = new File(plugin.getDataFolder(), "categories/kolekcja.yml");
        if (!plik.exists()) return "brak pliku categories/kolekcja.yml";

        FileConfiguration kat = YamlConfiguration.loadConfiguration(plik);

        // Czyścimy stare sloty rotacyjne - bez tego po zmniejszeniu liczby
        // itemów zostałyby wpisy z poprzedniej oferty.
        for (int i = PIERWSZY_SLOT_ROTACYJNY; i < PIERWSZY_SLOT_ROTACYJNY + 20; i++) {
            kat.set("items." + i, null);
        }

        int slot = PIERWSZY_SLOT_ROTACYJNY;
        for (Map<?, ?> item : wylosowane) {
            String sciezka = "items." + slot + ".";
            kat.set(sciezka + "material", String.valueOf(item.get("material")));
            kat.set(sciezka + "slot", slot);
            kat.set(sciezka + "display-name", String.valueOf(item.get("nazwa")));
            kat.set(sciezka + "amount", 1);
            kat.set(sciezka + "buy-price", item.get("cena"));

            // Bez kodow koloru (&) - ShopManager nie parsuje ich w lore, kazda linia
            // dostaje jeden staly kolor od renderera (patrz customLore w ShopManager),
            // dokladnie tak jak lore w kazdej innej kategorii w tym projekcie.
            List<String> lore = new ArrayList<>();
            lore.add("OFERTA CZASOWA");
            lore.add("Dostepne przez ograniczony czas");
            kat.set(sciezka + "lore", lore);

            // Rogi kóz różnią się tylko komponentem instrumentu - bez tego
            // wszystkie 5 wariantów byłoby identycznym, domyślnym rogiem.
            Object instrument = item.get("instrument");
            if (instrument != null) {
                kat.set(sciezka + "instrument", String.valueOf(instrument));
            }
            slot++;
        }

        try {
            kat.save(plik);
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    // =========================================================================
    //  OGŁOSZENIE
    // =========================================================================

    private void oglosNaCzacie(List<Map<?, ?>> wylosowane) {
        Bukkit.broadcast(Component.empty());
        Bukkit.broadcast(Component.text("★ NOWA OFERTA W KOLEKCJI! ★",
                NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        for (Map<?, ?> item : wylosowane) {
            Bukkit.broadcast(Component.text("  • ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(String.valueOf(item.get("nazwa")), NamedTextColor.WHITE))
                    .append(Component.text("  " + item.get("cena") + " $", NamedTextColor.GOLD)));
        }
        Bukkit.broadcast(Component.text("Sprawdz /sklep - oferta znika za "
                + DNI_NA_ROTACJE + " dni!", NamedTextColor.GRAY));
        Bukkit.broadcast(Component.empty());
    }

    // =========================================================================
    //  API
    // =========================================================================

    /** Ile dni zostało do zmiany oferty - do wyświetlenia w GUI albo tab liście. */
    public int dniDoRotacji() {
        long ostatnia = stan.getLong("ostatnia-rotacja", 0L);
        if (ostatnia == 0L) return DNI_NA_ROTACJE;
        long minelo = System.currentTimeMillis() - ostatnia;
        long zostalo = (DNI_NA_ROTACJE * 24L * 60L * 60L * 1000L) - minelo;
        return (int) Math.max(0, zostalo / (24L * 60L * 60L * 1000L));
    }

    /** Wymuszona rotacja - do komendy administracyjnej. */
    public void wymusRotacje() {
        wykonajRotacje(true);
    }

    /** Ile itemów łącznie jest w puli - do komendy administracyjnej (/@sklep rotacja). */
    public int rozmiarPuli() {
        return pula.size();
    }

    /** Ile itemów jest aktualnie na chłodzeniu (nie może wypaść w najbliższej rotacji). */
    public int naChlodzeniu() {
        return (int) chlodzenie.values().stream().filter(v -> v > 0).count();
    }

    public void zamknij() {
        saver.zamknij();
    }
}
