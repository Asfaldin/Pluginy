package elo.mainplugins.hud;

import elo.mainplugins.core.api.CenyService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.api.TopGracz;
import elo.mainplugins.core.util.MoneyFormat;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;

/**
 * Ekspansja PlaceholderAPI - zastepuje stary, recznie renderowany Tab
 * (TablistManager/PixelSpacer/wlasny resourcepack) tym, czego uzywaja profesjonalne
 * serwery: dane wystawione jako placeholdery, uklad/wyrownanie zostawione pluginowi
 * TAB (ma wlasny, sprawdzony resourcepack i silnik wyrownania - patrz README modulu).
 *
 * Format identyfikatorow: %mainplugins_<nazwa>% - patrz onRequest() dla pelnej listy.
 * Indeksy topek graczy/wysp sa 1-indeksowane (nizsza liczba = wyzsza pozycja). top_gracz_linia_N
 * i top_wyspa_linia_N zwracaja od razu cala sformatowana linijke (z kolorami) zamiast
 * pojedynczych pol - patrz komentarz przy liniaTopGracza(). top_gracz_linia_pad_N to ten sam
 * tekst dopelniony spacjami do stalej szerokosci (do sklejania z top_wyspa_linia_N w jednej
 * linii naglowka/stopki TAB - patrz SZEROKOSC_PAD_GRACZA).
 */
public class MainpluginsPlaceholders extends PlaceholderExpansion {

    private static final int MAX_TOP = 10;

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
        "&7Zapros znajomego na wyspe &f- &7gra sie razniej w kilka osob",
        "&7Wpisz &f/menu&7, zeby otworzyc glowne menu serwera",
    };

    /** Co ile sekund zmienia się treść wiersza 1 stopki. */
    private static final int SEKUND_NA_SLAJD = 8;

    /**
     * Co która zmiana slajdu to wskazówka rynkowa (reszta to pro tipy).
     * 3 = rynkowa co trzeci slajd, żeby nie zdominowała rotacji obok 12 tipów.
     */
    private static final int CO_KTORY_SLAJD_RYNKOWY = 3;

    private final EconomyService economyManager;
    private final CenyService cenyService;   // może być null - szukane dynamicznie, patrz znajdzCenyService()

    public MainpluginsPlaceholders(EconomyService economyManager) {
        this.economyManager = economyManager;
        this.cenyService = null;
    }

    /**
     * Przeciążenie - CenyService jest opcjonalny (shop mógłby się jeszcze
     * nie włączyć albo w ogóle nie być wgrany), więc i tak odpytujemy
     * ServicesManager na bieżąco przy każdym żądaniu (patrz znajdzCenyService()),
     * a nie raz przy starcie - shop może się włączyć PO hud (kolejność pluginów).
     */
    public MainpluginsPlaceholders(EconomyService economyManager, CenyService cenyServiceIgnored) {
        this.economyManager = economyManager;
        this.cenyService = null;
    }

    @Override
    public String getIdentifier() {
        return "mainplugins";
    }

    @Override
    public String getAuthor() {
        return "Mainplugins";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    /** Placeholdery maja przetrwac /papi reload - nie trzymamy zadnego stanu, wiec bezpieczne. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        params = params.toLowerCase(Locale.ROOT);
        switch (params) {
            // online/tps NIE sa tu potrzebne - TAB ma je wbudowane natywnie (%online%,
            // %tps%), bez PlaceholderAPI. maxplayers NIE ma jednak wbudowanego
            // odpowiednika w TAB (mimo pozorow z dokumentacji) - stad wlasny.
            case "maxplayers" -> {
                return String.valueOf(Bukkit.getMaxPlayers());
            }
            case "kasa" -> {
                return player == null ? "" : MoneyFormat.kompaktowo(economyManager.getKasa(player.getUniqueId()));
            }
            case "wyspa_rozmiar" -> {
                IslandSummary wyspa = player == null ? null : HudData.pobierzWlasnaWyspe(player.getUniqueId());
                return wyspa != null ? String.valueOf(wyspa.borderSize()) : "-";
            }
            case "wyspa_czlonkowie" -> {
                IslandSummary wyspa = player == null ? null : HudData.pobierzWlasnaWyspe(player.getUniqueId());
                return wyspa != null ? String.valueOf(wyspa.memberCount()) : "-";
            }
            case "wyspa_opis" -> {
                IslandSummary wyspa = player == null ? null : HudData.pobierzWlasnaWyspe(player.getUniqueId());
                return wyspa != null
                        ? wyspa.borderSize() + " bl. (" + wyspa.memberCount() + " czlonkow)"
                        : "Brak (wpisz /is)";
            }
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
            default -> { /* sprawdz pozostale wzorce ponizej (z numerem na koncu) */ }
        }

        Integer indeks = wyciagnijIndeks(params, "top_gracz_linia_pad_");
        if (indeks != null) return liniaTopGracza(indeks, SZEROKOSC_PAD_GRACZA);

        indeks = wyciagnijIndeks(params, "top_gracz_linia_");
        if (indeks != null) return liniaTopGracza(indeks, 0);

        indeks = wyciagnijIndeks(params, "top_wyspa_linia_");
        if (indeks != null) return liniaTopWyspy(indeks);

        return null;
    }

    /**
     * Szerokosc (w ZNAKACH, nie pikselach) do jakiej dopelniana jest spacjami linijka
     * gracza w wariancie "_pad_" - uzywana gdy Top Gracze i Top Wyspy sa sklejane w
     * jedna linie naglowka/stopki TAB (bez siatki Layout - patrz komentarz klasowy).
     * Font Minecrafta nie jest monospace, wiec to przyblizenie, nie piksel-perfect -
     * ale bez wlasnego resourcepacka (patrz historia projektu) to jedyna opcja.
     */
    private static final int SZEROKOSC_PAD_GRACZA = 34;

    /**
     * Cala sformatowana linijka rankingu graczy (numer + nick + kasa) albo "" gdy
     * dany rzedu jeszcze nie ma (mniej niz 10 realnych graczy z kasa > 0) - dzieki
     * temu pusty wiersz w configu TAB nie zostawia sierocego "N. - $" bez tresci.
     * Pierwsze 3 miejsca pogrubione, zeby czolowka byla widoczna na pierwszy rzut oka.
     */
    private String liniaTopGracza(int rank, int szerokoscPad) {
        TopGracz gracz = pobierzTopGraczaLubNull(rank);
        if (gracz == null) return szerokoscPad > 0 ? " ".repeat(szerokoscPad) : "";
        String surowy = rank + ". " + gracz.nick() + " - " + MoneyFormat.kompaktowo(gracz.kasa()) + "$";
        String kolor = rank <= 3 ? "&a&l" : "&a";
        String kolorowy = kolor + rank + ". &f" + gracz.nick() + " &7- " + kolor + MoneyFormat.kompaktowo(gracz.kasa()) + "&7$";
        int brakujace = szerokoscPad - surowy.length();
        return brakujace > 0 ? kolorowy + " ".repeat(brakujace) : kolorowy;
    }

    /** Odpowiednik liniaTopGracza() dla rankingu wysp - patrz ten komentarz. */
    private String liniaTopWyspy(int rank) {
        IslandSummary wyspa = pobierzTopWyspeLubNull(rank);
        if (wyspa == null) return "";
        String kolor = rank <= 3 ? "&6&l" : "&6";
        return kolor + rank + ". &f" + wyspa.ownerName() + " &7- " + kolor + wyspa.borderSize() + "&7 bl.";
    }

    private TopGracz pobierzTopGraczaLubNull(int indeks1based) {
        List<TopGracz> top = HudData.pobierzTopGraczy(economyManager, MAX_TOP);
        int i = indeks1based - 1;
        return i >= 0 && i < top.size() ? top.get(i) : null;
    }

    private IslandSummary pobierzTopWyspeLubNull(int indeks1based) {
        List<IslandSummary> top = HudData.pobierzTopWysp(MAX_TOP);
        int i = indeks1based - 1;
        return i >= 0 && i < top.size() ? top.get(i) : null;
    }

    /** Zwraca liczbe z konca "params", jesli zaczyna sie od "prefix<N>" - inaczej null. */
    private Integer wyciagnijIndeks(String params, String prefix) {
        if (!params.startsWith(prefix)) return null;
        try {
            return Integer.parseInt(params.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

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
}
