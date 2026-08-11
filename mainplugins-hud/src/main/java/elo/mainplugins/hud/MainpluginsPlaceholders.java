package elo.mainplugins.hud;

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

    private final EconomyService economyManager;

    public MainpluginsPlaceholders(EconomyService economyManager) {
        this.economyManager = economyManager;
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
}
