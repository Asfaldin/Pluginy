package elo.mainplugins.advancements.config;

import elo.mainplugins.advancements.model.AchievementCategory;
import elo.mainplugins.advancements.model.AchievementDef;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Niemutowalny obraz osiagniecia.yml. Budowany w całości przez
 * {@link AchievementsConfigLoader}; przy {@code /@reloadosiagniecia} AchievementManager
 * dostaje nową instancję i podmienia referencję - stan graczy jest trzymany osobno.
 */
public final class AchievementsConfig {

    /** Ustawienia wizualne panelu GUI. */
    public record Gui(Component tytul, int rozmiar, Material tlo) {}

    /**
     * Jak sygnalizujemy zdobycie WŁASNEGO osiągnięcia (vanilla ma własny toast Minecrafta).
     * {@code dzwiekKey} to surowy klucz dźwięku (np. {@code entity.player.levelup}) grany
     * przez {@code Player#playSound(Location, String, float, float)} - stabilny od zawsze
     * overload, bez zależności od enuma/rejestru Sound zmienianego między wersjami Paper.
     */
    public record Powiadomienia(String dzwiekKey, String tytulLegacy, String podtytulLegacy, boolean efektRzadkich) {}

    /**
     * Generowanie datapacka z własnymi osiągnięciami (natywny ekran ESC → Postępy).
     * {@code namespace} - przestrzeń nazw advancementów w datapacku (domyślnie {@code mpa});
     * {@code tloDomyslne} - tekstura tła drzewka, gdy kategoria nie poda własnej.
     */
    public record Datapack(boolean wlaczony, String namespace, String tloDomyslne) {}

    /**
     * Zachowanie nagród. {@code autoWszystkie} - wręczaj nagrodę od razu przy zdobyciu
     * (bez klikania w GUI); pojedyncze osiągnięcie może to nadpisać wpisem {@code auto: false}.
     * Gdy w ekwipunku brak miejsca, przedmioty trafiają do kolejki i są dostarczane
     * automatycznie, gdy miejsce się zwolni (patrz AchievementManager).
     */
    public record Nagrody(boolean autoWszystkie) {}

    /**
     * Ukrycie wbudowanych advancementów Minecrafta w natywnym ekranie ESC → Postępy.
     * Gdy {@code wlaczone}, datapack nadpisuje KAŻDY waniliowy advancement 5 zakładek
     * ({@code story/nether/end/adventure/husbandry}) - łącznie z ich rootami - wersją
     * bez sekcji {@code display}, więc całe te zakładki znikają z ekranu. Zostają tylko
     * nasze drzewka {@code mpa:*}. {@code minecraft:recipes/*} pozostają nietknięte.
     */
    public record VanillaPrzejecie(boolean wlaczone) {}

    private final Gui gui;
    private final Powiadomienia powiadomienia;
    private final Datapack datapack;
    private final Nagrody nagrody;
    private final VanillaPrzejecie vanillaPrzejecie;
    private final int sprawdzanieCoSekund;
    private final List<AchievementCategory> kategorie;
    private final List<AchievementDef> osiagniecia;
    private final Map<String, AchievementDef> wgId;
    private final Map<String, List<AchievementDef>> wgKategorii;

    AchievementsConfig(Gui gui, Powiadomienia powiadomienia, Datapack datapack, Nagrody nagrody,
                       VanillaPrzejecie vanillaPrzejecie, int sprawdzanieCoSekund,
                       List<AchievementCategory> kategorie, List<AchievementDef> osiagniecia) {
        this.gui = gui;
        this.powiadomienia = powiadomienia;
        this.datapack = datapack;
        this.nagrody = nagrody;
        this.vanillaPrzejecie = vanillaPrzejecie;
        this.sprawdzanieCoSekund = sprawdzanieCoSekund;
        this.kategorie = List.copyOf(kategorie);
        this.osiagniecia = List.copyOf(osiagniecia);

        Map<String, AchievementDef> wgId = new LinkedHashMap<>();
        Map<String, List<AchievementDef>> wgKat = new LinkedHashMap<>();
        for (AchievementCategory k : this.kategorie) {
            wgKat.put(k.id(), new ArrayList<>());
        }
        for (AchievementDef a : this.osiagniecia) {
            wgId.put(a.id(), a);
            wgKat.computeIfAbsent(a.kategoriaId(), key -> new ArrayList<>()).add(a);
        }
        this.wgId = Map.copyOf(wgId);
        Map<String, List<AchievementDef>> wgKatNiemut = new LinkedHashMap<>();
        wgKat.forEach((key, lista) -> wgKatNiemut.put(key, List.copyOf(lista)));
        this.wgKategorii = Map.copyOf(wgKatNiemut);
    }

    public Gui gui() { return gui; }

    public Powiadomienia powiadomienia() { return powiadomienia; }

    public Datapack datapack() { return datapack; }

    public Nagrody nagrody() { return nagrody; }

    public VanillaPrzejecie vanillaPrzejecie() { return vanillaPrzejecie; }

    public int sprawdzanieCoSekund() { return sprawdzanieCoSekund; }

    /** Zakładki posortowane wg {@code kolejnosc} (już przez loader). */
    public List<AchievementCategory> kategorie() { return kategorie; }

    /** Wszystkie osiągnięcia w kolejności z pliku. */
    public List<AchievementDef> osiagniecia() { return osiagniecia; }

    public AchievementDef wgId(String id) { return wgId.get(id); }

    /** Osiągnięcia danej kategorii w kolejności z pliku - pusta lista dla nieznanego id. */
    public List<AchievementDef> wgKategorii(String kategoriaId) {
        return wgKategorii.getOrDefault(kategoriaId, List.of());
    }
}
