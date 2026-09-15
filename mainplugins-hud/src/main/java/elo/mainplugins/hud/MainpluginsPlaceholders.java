package elo.mainplugins.hud;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.api.Rank;
import elo.mainplugins.core.api.RankService;
import elo.mainplugins.core.api.TopGracz;
import elo.mainplugins.core.util.MoneyFormat;
import elo.mainplugins.hud.config.HudConfig;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Placeholdery HUD-a (rejestrowane w PlaceholderService core) - zastepuje stary, recznie renderowany Tab
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
public class MainpluginsPlaceholders {

    private final EconomyService economyManager;
    private volatile HudConfig config;

    public MainpluginsPlaceholders(EconomyService economyManager, HudConfig config) {
        this.economyManager = economyManager;
        this.config = config;
    }

    /** Podmienia pro tipy/ustawienia/fake-dane na żywo - patrz /@reloadhud. */
    public void aktualizujKonfiguracje(HudConfig nowy) {
        this.config = nowy;
    }

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
            case "saldo" -> {
                // Alias pod krotsza nazwe do scoreboardu - dokladnie to samo,
                // co juz istniejacy %mainplugins_kasa%, zeby nie psuc tamtego
                // gdziekolwiek jest juz uzywany.
                return player == null ? "" : MoneyFormat.kompaktowo(economyManager.getKasa(player.getUniqueId()));
            }
            case "ranga" -> {
                if (player == null) return "";
                RankService rankService = CoreAPI.getRankService();
                Rank ranga = rankService != null ? rankService.getRank(player.getUniqueId()) : Rank.GRACZ;
                return switch (ranga) {
                    case ADMIN -> "&c&lAdmin";
                    case VIP   -> "&6&lVIP";
                    case GRACZ -> "&7Gracz";
                };
            }
            case "moje_miejsce" -> {
                if (player == null) return "-";
                int pozycja = economyManager.getPozycjaWRankingu(player.getUniqueId());
                if (pozycja <= 0) return "&7-";
                String kolor = switch (pozycja) {
                    case 1 -> "&6&l";
                    case 2 -> "&7&l";
                    case 3 -> "&c&l";
                    default -> "&f";
                };
                return kolor + pozycja;
            }
            case "czas_gry" -> {
                Player online = player != null ? Bukkit.getPlayer(player.getUniqueId()) : null;
                if (online == null) return "-";
                int tickiGry = online.getStatistic(Statistic.PLAY_ONE_MINUTE);
                long minuty = tickiGry / 20 / 60;
                long godziny = minuty / 60;
                return godziny + "h " + (minuty % 60) + "m";
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
            // reset_cen_dni i event_info wystawia teraz sam Sklep (ShopPlaceholders) - bez Sklepu ich nie ma.
            default -> { /* sprawdz pozostale wzorce ponizej (z numerem na koncu) */ }
        }

        Integer indeks = wyciagnijIndeks(params, "top_gracz_linia_pad_");
        if (indeks != null) return liniaTopGracza(indeks, config.szerokoscPadGracza());

        indeks = wyciagnijIndeks(params, "top_gracz_linia_");
        if (indeks != null) return liniaTopGracza(indeks, 0);

        indeks = wyciagnijIndeks(params, "top_wyspa_linia_");
        if (indeks != null) return liniaTopWyspy(indeks);

        return null;
    }

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
        List<TopGracz> top = HudData.pobierzTopGraczy(economyManager, config.maxTop(), config.fakeTopGraczy());
        int i = indeks1based - 1;
        return i >= 0 && i < top.size() ? top.get(i) : null;
    }

    private IslandSummary pobierzTopWyspeLubNull(int indeks1based) {
        List<IslandSummary> top = HudData.pobierzTopWysp(config.maxTop(), config.fakeTopWysp());
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
        long slajd = (System.currentTimeMillis() / 1000L / config.sekundNaSlajd());

        if (slajd % config.coKtorySlajdRynkowy() == 0) {
            String rynkowa = wskazowkaRynkowa();
            if (rynkowa != null) return dopelnij(rynkowa, config.szerokoscProTipu());
            // Brak sensownej wskazówki rynkowej (np. shop nie wgrany, albo
            // akurat nic się nie odchyla od bazy) - spadamy na pro tip zamiast
            // pustego wiersza.
        }
        List<String> proTipy = config.proTipy();
        if (proTipy.isEmpty()) return "";
        int indeks = (int) (slajd % proTipy.size());
        return dopelnij(proTipy.get(indeks), config.szerokoscProTipu());
    }

    /**
     * Dopełnia tekst spacjami z prawej do zadanej szerokości, licząc długość BEZ
     * kodów koloru (&7, &f, &l itd.) - inaczej dłuższy kod koloru sztucznie
     * wydłużałby "widoczną" długość i psuł wyrównanie. Ten sam pomysł co
     * SZEROKOSC_PAD_GRACZA/liniaTopGracza(), tylko współdzielony przez pro tipy
     * i wskazówkę rynkową, bo oba wskakują w ten sam wiersz stopki.
     */
    private static String dopelnij(String tekst, int szerokosc) {
        int widocznaDlugosc = tekst.replaceAll("&[0-9a-fk-or]", "").length();
        int brakujace = szerokosc - widocznaDlugosc;
        return brakujace > 0 ? tekst + " ".repeat(brakujace) : tekst;
    }

    /**
     * Item z największym bieżącym odchyleniem od ceny bazowej (w dowolną
     * stronę) - to jest ta sama informacja, którą gracz widzi jako strzałkę
     * przy skupie w sklepie, tylko wyciągnięta na tab.
     *
     * @return sformatowana linijka, albo null gdy nie ma o czym mówić
     *         (shop niewgrany, albo wszystko w normie). Tekst daje Sklep (placeholder shop_trend).
     */
    private String wskazowkaRynkowa() {
        String trend = CoreAPI.getPlaceholderService().resolve(null, "shop_trend");
        return trend == null || trend.isEmpty() ? null : trend;
    }
}
