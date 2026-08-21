// =============================================================================
//  NOWE PLACEHOLDERY POD TAB LISTĘ
// =============================================================================
//
//  Top gracze i top wyspy JUŻ ISTNIEJĄ (MainpluginsPlaceholders.java, patrz
//  top_gracz_linia_N i top_wyspa_linia_N). Nie ruszamy tego.
//
//  Dopisujemy tylko to, czego brakuje do specyfikacji taba:
//
//    %mainplugins_wskazowka%        rotujący wiersz 1 stopki (rynek / pro tip)
//    %mainplugins_online%           gracze online (TAB ma %online% wbudowane,
//                                    ale zostawiamy dla spójności formatu)
//    %mainplugins_reset_cen_dni%    ile dni do globalnego resetu cen w sklepie
//    %mainplugins_event_info%       aktywny event cenowy, jeśli trwa; "" gdy nie
//
//  Rotacja wskazówki NIE dzieje się przy każdym odpytaniu placeholdera (TAB
//  odpytuje go często, dla każdego gracza) - liczy się z zegara systemowego,
//  więc wszyscy gracze widzą TĘ SAMĄ wskazówkę w danej chwili, zsynchronizowaną,
//  bez żadnego stanu do trzymania i bez zadania w tle.
//
//  PLIKI: zmiany w MainpluginsPlaceholders.java, wymaga wcześniej wgranego
//  DynamicPriceManager (shop) i AsyncConfigSaver (core).
// =============================================================================


// =============================================================================
//  CZĘŚĆ 1: mainplugins-hud/.../MainpluginsPlaceholders.java
// =============================================================================

// --- 1a. import DynamicPriceManager i CoreAPI odpowiednika dla shop -----------
//
// UWAGA: HUD nie ma dotąd żadnej zależności od mainplugins-shop. Podobnie jak
// IslandService, DynamicPriceManager musi być OPCJONALNY (shop mógłby się
// jeszcze nie włączyć albo w ogóle nie być wgrany) - patrz wzorzec
// znajdzIslandService() w HudData.java, robimy dokładnie to samo.
//
// Żeby to zadziałało, DynamicPriceManager (albo cienki interfejs nad nim)
// musi być zarejestrowany w Bukkit ServicesManager - tak jak EconomyService
// i IslandService. Jeśli tego jeszcze nie ma w mainplugins-shop, patrz KROK 0
// na samym dole tego patcha.

// Dopisz do importów:
import elo.mainplugins.core.api.CenyService;   // patrz KROK 0 - nowy, cienki interfejs


// --- 1b. stałe z listą pro tipów -----------------------------------------------
// Dopisz do klasy, obok MAX_TOP:

    /**
     * Pro tipy rotujące razem ze wskazówką rynkową w wierszu 1 stopki.
     * Kolejność ma znaczenie tylko o tyle, że jest stała - dzięki temu rotacja
     * oparta o czas (patrz wskazowka()) daje przewidywalną, powtarzalną
     * sekwencję zamiast losowej.
     */
    private static final String[] PRO_TIPY = {
        "&7Mozesz zebrac spawner patykiem &f- &7PPM na blok",
        "&7Sortuj sklep po cenie skupu &f- &7kliknij PPM na przycisk sortowania",
        "&7Szukaj przedmiotu w sklepie klikajac tabliczke na gorze menu",
        "&7Limit spawnerow na wyspe to &f10 &7- planuj z wyprzedzeniem",
        "&7Ceny w sklepie resetuja sie co &f14 dni",
        "&7Wpisz &f/komendy&7, zeby zobaczyc co potrafi serwer",
        "&7Sprawdz &f/questy &7i odbieraj nagrody za kolejne etapy",
        "&7Handluj z graczami przez &f/targ",
        "&7Powieksz swoja wyspe w &f/is menu",
        "&7Zapraszamy na naszego &9Discorda&7!",
        "&7Zaproc znajomego na wyspe &f- &7gra sie razniej w kilka osob",
        "&7Wpisz &f/menu&7, zeby otworzyc glowne menu serwera",
    };

    /** Co ile sekund zmienia się treść wiersza 1 stopki. */
    private static final int SEKUND_NA_SLAJD = 8;

    /**
     * Co która zmiana slajdu to wskazówka rynkowa (reszta to pro tipy).
     * 3 = rynkowa co trzeci slajd, żeby nie zdominowała rotacji obok 12 tipów.
     */
    private static final int CO_KTORY_SLAJD_RYNKOWY = 3;


// --- 1c. pole na opcjonalny serwis cen -----------------------------------------
// Dopisz obok economyManager:

    private final CenyService cenyService;   // może być null - patrz 1a


// --- 1d. konstruktor ------------------------------------------------------------
// Rozszerz istniejący konstruktor o drugi parametr (opcjonalny, może być null
// jeśli shop nie jest wgrany w momencie tworzenia HUD-a - i tak sprawdzamy
// przez ServicesManager na bieżąco, patrz znajdzCenyService()):

    public MainpluginsPlaceholders(EconomyService economyManager, CenyService cenyServiceIgnored) {
        this.economyManager = economyManager;
        this.cenyService = null;   // nieużywane - patrz znajdzCenyService(), ten sam
                                    // wzorzec co znajdzIslandService() w HudData:
                                    // odpytujemy ServicesManager za każdym razem,
                                    // a nie raz przy starcie, bo shop może się
                                    // włączyć PO hud (kolejność pluginów).
    }

// UWAGA: zostawiamy stary jednoargumentowy konstruktor też - MainpluginsHUD.java
// go woła. Prościej dopisać PRZECIĄŻENIE zamiast zmieniać istniejące wywołanie:

    public MainpluginsPlaceholders(EconomyService economyManager) {
        this.economyManager = economyManager;
        this.cenyService = null;
    }


// --- 1e. onRequest() - trzy nowe case'y ----------------------------------------
// W metodzie onRequest(), w switchu, dopisz obok istniejących case'ów:

            case "wskazowka" -> {
                return wskazowka();
            }
            case "reset_cen_dni" -> {
                CenyService ceny = znajdzCenyService();
                return ceny != null ? String.valueOf(ceny.dniDoResetu()) : "-";
            }
            case "event_info" -> {
                CenyService ceny = znajdzCenyService();
                if (ceny == null) return "";
                var zablokowane = ceny.getZablokowaneNazwy();
                if (zablokowane.isEmpty()) return "";
                // Pokazujemy tylko LICZBĘ aktywnych eventów, nie każdy z osobna -
                // stopka ma jedną linijkę, nie da się tam wypisać dowolnej ilości.
                return "&d&lEVENT &7- " + zablokowane.size()
                        + (zablokowane.size() == 1 ? " przedmiot" : " przedmioty")
                        + " w promocji!";
            }


// --- 1f. metoda budująca rotację ------------------------------------------------
// Dopisz jako nową metodę prywatną:

    /**
     * Wiersz 1 stopki: rotuje między wskazówką rynkową a pro tipami, oparta
     * o zegar systemowy - WSZYSCY gracze widzą to samo w danej chwili, bez
     * żadnego stanu do trzymania w pamięci pluginu i bez osobnego zadania
     * cyklicznego. TAB sam odpytuje placeholder z własną częstotliwością
     * odświeżania (configurowalną w jego configu) - my tylko zwracamy,
     * co POWINNO być widoczne w TEJ sekundzie.
     */
    private String wskazowka() {
        long slajd = (System.currentTimeMillis() / 1000L / SEKUND_NA_SLAJD);

        if (slajd % CO_KTORY_SLAJD_RYNKOWY == 0) {
            String rynkowa = wskazowkaRynkowa();
            if (rynkowa != null) return rynkowa;
            // Brak sensownej wskazówki rynkowej (np. shop nie wgrany, albo
            // akurat nic się nie odchyla od bazy) - spadamy na pro tip zamiast
            // pustego wiersza.
        }
        int indeks = (int) (slajd % PRO_TIPY.length);
        return PRO_TIPY[indeks];
    }

    /**
     * Item z największym bieżącym odchyleniem od ceny bazowej (w dowolną
     * stronę) - to jest ta sama informacja, którą gracz widzi jako strzałkę
     * przy skupie w sklepie, tylko wyciągnięta na tab.
     *
     * @return sformatowana linijka, albo null gdy nie ma o czym mówić
     *         (shop niewgrany, albo wszystko w normie)
     */
    private String wskazowkaRynkowa() {
        CenyService ceny = znajdzCenyService();
        if (ceny == null) return null;

        CenyService.Odchylenie top = ceny.najwiekszeOdchylenie();
        if (top == null) return null;   // wszystko w normie albo brak danych

        boolean wzrost = top.mnoznik() > 1.0;
        String strzalka = wzrost ? "&a▲" : "&c▼";
        int procent = (int) Math.round((top.mnoznik() - 1.0) * 100);
        String znak = procent >= 0 ? "+" : "";

        return strzalka + " &f" + top.nazwa() + " &7" + (wzrost ? "drozeje" : "taniej")
                + " &7(" + znak + procent + "%)";
    }

    private CenyService znajdzCenyService() {
        var rsp = Bukkit.getServicesManager().getRegistration(CenyService.class);
        return rsp != null ? rsp.getProvider() : null;
    }


// =============================================================================
//  KROK 0 (WYMAGANE WCZEŚNIEJ): cienki interfejs CenyService
//
//  HUD nie może zależeć bezpośrednio od klasy DynamicPriceManager w module
//  shop - to złamałoby kierunek zależności (core <- hud, core <- shop, ale
//  hud NIE MOŻE zależeć od shop, bo wtedy shop musiałby się ładować przed
//  hud, a hud.plugin.yml ma świadome "loadbefore: [TAB]", nie da się mieć
//  obu). Dlatego, dokładnie jak EconomyService i IslandService, potrzebny
//  jest cienki interfejs w core/api, a DynamicPriceManager w shop go
//  implementuje i rejestruje w ServicesManager - HUD zna tylko interfejs.
// =============================================================================

// --- Nowy plik: mainplugins-core/.../api/CenyService.java ---------------------

package elo.mainplugins.core.api;

import java.util.List;

/**
 * Cienki interfejs nad dynamicznymi cenami sklepu (patrz mainplugins-shop /
 * DynamicPriceManager) - żeby inne moduły (HUD, ewentualnie questy) mogły
 * czytać stan cen bez twardej zależności od modułu shop.
 */
public interface CenyService {

    /** Jedno odchylenie ceny od bazy - do wyświetlenia jako "co teraz warto sprzedać". */
    record Odchylenie(String nazwa, double mnoznik) {}

    /**
     * Item z największym bieżącym odchyleniem od ceny bazowej, w dowolną
     * stronę (może to być zarówno spadek, jak i wzrost - i tak ma sens
     * pokazać graczowi, gdzie dzieje się coś nietypowego).
     *
     * @return null, gdy brak danych albo wszystkie itemy są w normie (blisko 1.0)
     */
    Odchylenie najwiekszeOdchylenie();

    /** Ile dni zostało do najbliższego globalnego resetu cen. */
    int dniDoResetu();

    /** Nazwy wyświetlane itemów z aktywnym, zablokowanym mnożnikiem (eventy). */
    List<String> getZablokowaneNazwy();
}


// --- DynamicPriceManager.java (mainplugins-shop) — implementacja --------------
// Klasa zaczyna implementować nowy interfejs:
//
//   public class DynamicPriceManager implements CenyService {
//
// Dopisz trzy metody wymagane przez interfejs (mogą korzystać z istniejących
// pól - mnozniki, CYKLI_DO_RESETU, cykliOdResetu, zablokowane):

    @Override
    public Odchylenie najwiekszeOdchylenie() {
        String najlepszyKlucz = null;
        double najwiekszeOdchylenie = 0.02;   // próg - poniżej tego to "w normie"

        for (var wpis : mnozniki.entrySet()) {
            double odchylenie = Math.abs(wpis.getValue() - 1.0);
            if (odchylenie > najwiekszeOdchylenie) {
                najwiekszeOdchylenie = odchylenie;
                najlepszyKlucz = wpis.getKey();
            }
        }
        if (najlepszyKlucz == null) return null;

        // Nazwa wyswietlana z cennika, nie surowy klucz (COBBLESTONE -> Bruk).
        // ShopManager już ma tę logikę (kluczCeny + odwrotne wyszukanie po
        // display-name) - tu zakładam, że jest dostępna jako nazwaWyswietlana(klucz);
        // jeśli nie, patrz UWAGA niżej.
        String nazwa = nazwaWyswietlana(najlepszyKlucz);
        return new Odchylenie(nazwa, mnozniki.get(najlepszyKlucz));
    }

    @Override
    public int dniDoResetu() {
        int cykliZostalo = CYKLI_DO_RESETU - cykliOdResetu;
        return Math.max(0, cykliZostalo / 24);   // MINUT_NA_CYKL=60 -> 24 cykle = 1 dzień
    }

    @Override
    public List<String> getZablokowaneNazwy() {
        return zablokowane.stream().map(this::nazwaWyswietlana).toList();
    }

// UWAGA: nazwaWyswietlana(klucz) - jeśli DynamicPriceManager nie ma dostępu
// do configu sklepu (sklepConfig siedzi w ShopManager, nie tutaj), najprościej
// przekazać ShopManager przez konstruktor DynamicPriceManager i wywołać jego
// istniejącą metodę szukania display-name po kluczu. Jeśli DynamicPriceManager
// już dostaje ShopManager gdzieś w konstruktorze - użyj tego. Jeśli nie,
// tymczasowe rozwiązanie: zwracać sam klucz (COBBLESTONE) zamiast ładnej
// nazwy - działa, tylko brzydziej w tab liście.


// --- Rejestracja w ServicesManager ---------------------------------------------
// W konstruktorze DynamicPriceManager, na końcu (po this.saver = ...):

        Bukkit.getServicesManager().register(CenyService.class, this,
                plugin, org.bukkit.plugin.ServicePriority.Normal);

// I analogicznie wyrejestrowanie w zamknij():

        Bukkit.getServicesManager().unregister(CenyService.class, this);


// =============================================================================
//  CZĘŚĆ 2: MainpluginsHUD.java — nic nie trzeba zmieniać
//
//  Konstruktor MainpluginsPlaceholders(economyManager) ma przeciążenie
//  jednoargumentowe (patrz 1d) - istniejące wywołanie w onEnable() działa
//  bez zmian. CenyService jest wyszukiwany dynamicznie przy każdym
//  odpytaniu placeholdera (znajdzCenyService()), nie w konstruktorze.
// =============================================================================
