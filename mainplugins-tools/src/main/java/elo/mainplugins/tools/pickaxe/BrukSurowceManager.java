package elo.mainplugins.tools.pickaxe;

import elo.mainplugins.tools.pickaxe.BrukSurowce.SurowiecDrop;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bonus do farmienia bruku (COBBLESTONE) - każdy tier kilofa ma własną szansę % (patrz
 * resources/bruk-szanse.yml) na to, że skopanie bruku od razu doda do wypadających przedmiotów
 * bonusowy surowiec. Pula możliwych surowców kumuluje się z tierem kilofa (patrz
 * BrukSurowce#pulaDlaTieru) - np. kilof kamienny losuje z puli drewnianego + własne.
 *
 * WAŻNE: to jest czysta klasa obliczeniowa, NIE listener - roll i faktyczny drop wykonuje
 * PickaxeSkillManager#applyMiningPerks (patrz rollBonusZBruku tamże), bo na tym Skyblocku
 * to JEDYNY sposób zdobycia "rudy" (brak realnego świata z blokami rudy) - wynik tego rolla
 * musiał stać się też warunkiem kart "rudowych" (Oko Sokoła, Rezonans Rudy, Serce Głębi...),
 * więc oba mechanizmy musiały się zejść w jednym miejscu zamiast żyć w dwóch niezależnych
 * listenerach na ten sam BlockBreakEvent.
 *
 * Dwie karty kilofa (gałąź Magia) modyfikują ten mechanizm: MAG_BRUK_SZCZESCIE podnosi samą
 * szansę na trigger (+1pp/poziom), MAG_BRUK_INSTYNKT nie rusza szansy na trigger, tylko
 * przechyla WAŻONE losowanie w puli w stronę surowców oznaczonych jako "cenne" (patrz
 * BrukSurowce#cenny) - dzięki rozdzieleniu tych dwóch efektów drugą kartę można trzymać
 * hojniejszą bez ryzyka zalania ekonomii Złomem Netherytu.
 *
 * Klucz PDC "pk_bruk_disabled" należy do PickaxeSkillManager - tworzymy tu własną instancję
 * NamespacedKey o tej samej nazwie (ten sam plugin => równy klucz), zamiast wstrzykiwać tamtą
 * klasę tylko po to.
 */
public class BrukSurowceManager {

    /** Ile punktów procentowych szansy na trigger dodaje jeden poziom karty MAG_BRUK_SZCZESCIE. */
    private static final double SZCZESCIE_PP_ZA_POZIOM = 1.0;

    /** Mnożnik wagi surowców "cennych" = 1 + tyle_za_poziom * poziom karty MAG_BRUK_INSTYNKT. */
    private static final double INSTYNKT_MNOZNIK_ZA_POZIOM = 0.5;

    private final Plugin plugin;
    private final NamespacedKey pkBrukDisabled;

    private FileConfiguration szanseConfig;

    public BrukSurowceManager(Plugin plugin) {
        this.plugin = plugin;
        this.pkBrukDisabled = new NamespacedKey(plugin, "pk_bruk_disabled");
        wczytajKonfiguracje();
    }

    /** Bazowa szansa % (tier kilofa 0-4) skonfigurowana w bruk-szanse.yml, BEZ bonusu z karty - pod wyświetlanie w hubie kilofa. */
    public double szansaDlaTieru(int tier) {
        return szanseConfig.getDouble("tiery." + tier, 0.0);
    }

    /** Realna, całkowita szansa % na trigger - bazowa z yml + bonus z poziomu karty MAG_BRUK_SZCZESCIE. */
    public double calkowitaSzansa(int tier, int poziomSzczescia) {
        return Math.max(0, szansaDlaTieru(tier) + poziomSzczescia * SZCZESCIE_PP_ZA_POZIOM);
    }

    /** Udział (0-1) danego surowca w WAŻONEJ puli danego tieru (z pominięciem wyłączonych przez gracza) - pod wyświetlanie realnych % rozkładu w hubie kilofa. */
    public double udzialSurowca(int tier, SurowiecDrop surowiec, int poziomInstynktu, Set<String> wylaczone) {
        List<SurowiecDrop> pula = BrukSurowce.pulaDlaTieru(tier);
        double mnoznikCennych = 1 + INSTYNKT_MNOZNIK_ZA_POZIOM * poziomInstynktu;
        double suma = sumaWag(pula, mnoznikCennych, wylaczone);
        if (suma <= 0) return 0;
        return wagaEfektywna(surowiec, mnoznikCennych, wylaczone) / suma;
    }

    /** Waga efektywna 0, jeśli gracz ręcznie wyłączył ten surowiec (patrz PickaxeSkillManager#openBrukConfig) - traktowane jak nieobecność w puli. */
    private double wagaEfektywna(SurowiecDrop surowiec, double mnoznikCennych, Set<String> wylaczone) {
        if (wylaczone.contains(surowiec.id())) return 0;
        return surowiec.cenny() ? surowiec.waga() * mnoznikCennych : surowiec.waga();
    }

    private double sumaWag(List<SurowiecDrop> pula, double mnoznikCennych, Set<String> wylaczone) {
        double suma = 0;
        for (SurowiecDrop s : pula) suma += wagaEfektywna(s, mnoznikCennych, wylaczone);
        return suma;
    }

    /**
     * Losuje JEDEN surowiec z puli, ważąc surowce "cenne" mnożnikiem z poziomu karty
     * MAG_BRUK_INSTYNKT (0 = bez zmian, waga jak w BrukSurowce) i pomijając surowce ręcznie
     * wyłączone przez gracza. Zwraca null, jeśli gracz wyłączył WSZYSTKO w puli tego tieru -
     * wtedy po prostu nic nie wypada (patrz PickaxeSkillManager#rollBonusZBruku).
     */
    public SurowiecDrop losujWazony(List<SurowiecDrop> pula, int poziomInstynktu, Set<String> wylaczone) {
        double mnoznikCennych = 1 + INSTYNKT_MNOZNIK_ZA_POZIOM * poziomInstynktu;
        double suma = sumaWag(pula, mnoznikCennych, wylaczone);
        if (suma <= 0) return null;
        double roll = ThreadLocalRandom.current().nextDouble(suma);
        double cumulative = 0;
        for (SurowiecDrop s : pula) {
            cumulative += wagaEfektywna(s, mnoznikCennych, wylaczone);
            if (roll < cumulative) return s;
        }
        return null;
    }

    /** Zbiór id surowców (patrz SurowiecDrop#id) ręcznie wyłączonych przez gracza w konfiguracji bonusu z bruku. */
    public Set<String> wylaczoneSurowce(PersistentDataContainer pdc) {
        String csv = pdc.getOrDefault(pkBrukDisabled, PersistentDataType.STRING, "");
        if (csv.isEmpty()) return Set.of();
        return new LinkedHashSet<>(Arrays.asList(csv.split(",")));
    }

    private void wczytajKonfiguracje() {
        File plik = new File(plugin.getDataFolder(), "bruk-szanse.yml");
        if (!plik.exists()) {
            // Domyślne wartości żyją jako zasób pluginu (resources/bruk-szanse.yml) -
            // kopiujemy 1:1 tylko przy pierwszym uruchomieniu, żeby nie nadpisywać
            // ręcznych zmian admina przy kolejnych restartach.
            plugin.saveResource("bruk-szanse.yml", false);
        }
        szanseConfig = YamlConfiguration.loadConfiguration(plik);
    }
}