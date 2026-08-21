// =============================================================================
//  SCOREBOARD (PANEL Z PRAWEJ) — ranga, saldo, miejsce w topie, wskazówka, online
// =============================================================================
//
//  Panel widoczny CAŁY CZAS, bez trzymania Tab. Limit klienta gry: max 15 linii
//  łącznie z tytułem (potwierdzone w dokumentacji TAB). Nasz układ ma 6 - tytuł
//  RSMC plus 5 linii treści, bez pustych odstępów.
//
//  Trzy nowe placeholdery w MainpluginsPlaceholders.java (mainplugins-hud):
//    %mainplugins_ranga%           - ranga gracza (GRACZ/VIP/ADMIN), kolorowana
//    %mainplugins_saldo%           - to samo co %mainplugins_kasa%, alias pod
//                                     krótszą nazwę w scoreboardzie
//    %mainplugins_moje_miejsce%    - pozycja gracza w pełnym rankingu bogactwa,
//                                     nie tylko w top 10 jak dotychczasowe top_gracz_linia_N
//
//  Ranga: RankService już istnieje w core (patrz api/RankService.java,
//  CoreAPI.getRankService()) - dokładnie ten sam wzorzec co IslandService,
//  ToolsService itd. Nic nowego nie trzeba tworzyć w core, tylko podłączyć.
//
//  Miejsce w rankingu: EconomyService.getTop(limit) już istnieje, ale nie ma
//  sposobu na "znajdź pozycję TEGO gracza" bez pobierania całej listy i szukania
//  w niej ręcznie za każdym odpytaniem placeholdera - przy dużej liczbie graczy
//  i częstym odświeżaniu scoreboardu to niepotrzebne powtarzanie tej samej pracy.
//  Dlatego dopisujemy do EconomyService jedną nową metodę zamiast liczyć to
//  na piechotę w HUD - te same dane, które i tak trzyma EconomyManager.
//
//  PLIKI: zmiany w EconomyService.java + EconomyManager.java (core),
//  MainpluginsPlaceholders.java (hud) + nowy config TAB (scoreboard section).
// =============================================================================


// =============================================================================
//  CZĘŚĆ 1: api/EconomyService.java (core) — jedna nowa metoda
// =============================================================================

// Dopisz do interfejsu, obok getTop(int limit):

    /**
     * Pozycja gracza w pełnym rankingu bogactwa (1 = najbogatszy), niezależnie
     * od tego, ile pozycji zwraca getTop(). Gracze z kasą <= 0 nie są rankowani -
     * tak samo jak w getTop(), które ich pomija.
     *
     * @return pozycja (1-indeksowana) albo -1, gdy gracz ma kasę <= 0
     */
    int getPozycjaWRankingu(UUID uuid);


// =============================================================================
//  CZĘŚĆ 2: economy/EconomyManager.java (core) — implementacja
// =============================================================================

// Dopisz jako nową metodę, obok getTop():

    @Override
    public int getPozycjaWRankingu(UUID uuid) {
        long mojeSaldo = getGrosze(uuid);
        if (mojeSaldo <= 0) return -1;

        // Liczymy, ilu graczy ma WIĘCEJ niż gracz odpytujący - to jest jego
        // pozycja bez potrzeby pełnego sortowania całej listy.
        int wiecejMajacych = 0;
        for (String key : configEkonomii.getKeys(false)) {
            if (key.equals("meta") || key.equals("_meta")) continue;
            if (!czyUUID(key)) continue;
            if (key.equals(uuid.toString())) continue;

            long inneSaldo = configEkonomii.getLong(key, 0L);
            if (inneSaldo > mojeSaldo) wiecejMajacych++;
        }
        return wiecejMajacych + 1;
    }


// =============================================================================
//  CZĘŚĆ 3: MainpluginsPlaceholders.java (hud) — trzy nowe case'y
// =============================================================================

// --- 3a. import ------------------------------------------------------------
// Dopisz do importów:

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.Rank;
import elo.mainplugins.core.api.RankService;


// --- 3b. trzy nowe case'y w onRequest() -------------------------------------
// Dopisz w switchu, obok istniejących case'ów (np. po "kasa"):

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
                return pozycja > 0 ? "#" + pozycja : "-";
            }

// UWAGA: dodaj te case'y PRZED domyślną gałęzią "default -> { ... }" switcha,
// tak jak reszta - kolejność wewnątrz switcha nie ma znaczenia dla działania,
// tylko dla czytelności.


// =============================================================================
//  CZĘŚĆ 4: config TAB — sekcja scoreboard
// =============================================================================
//
//  W pliku plugins/TAB/config.yml, sekcja "scoreboard" (obecnie enabled: false
//  z domyślnym przykładem) - zamień CAŁĄ tę sekcję na poniższą.
// =============================================================================

# https://github.com/NEZNAMY/TAB/wiki/Feature-guide:-Scoreboard
scoreboard:
  enabled: true
  toggle-command: /sb
  remember-toggle-choice: false
  hidden-by-default: false
  delay-on-join-milliseconds: 0
  scoreboards:
    default:
      title: "&6&lRSMC"
      lines:
        - "&7Ranga: %mainplugins_ranga%"
        - "&7Saldo: &a%mainplugins_saldo%&a$"
        - "&7Miejsce w Top: &f%mainplugins_moje_miejsce%"
        - "%mainplugins_wskazowka%"
        - "&7Online: &f%online%"


// =============================================================================
//  CO SPRAWDZIĆ PO WGRANIU
//
//  1. Podmień jary: mainplugins-core, mainplugins-hud (oba się zmieniły).
//     Zrestartuj serwer.
//  2. Wejdź w grę - panel z prawej ma być widoczny OD RAZU, bez trzymania Tab.
//  3. Sprawdź rangę - jeśli nie masz ustawionej przez /@setranga, ma pokazywać
//     "Gracz" (szary). Ustaw sobie VIP/Admin i sprawdź, czy kolor/tekst się zmienia.
//  4. Sprawdź saldo - ma się zgadzać z tym, co pokazuje /portfel czy inna
//     istniejąca komenda ekonomii.
//  5. "Miejsce w Top" - jeśli masz kasę <= 0, ma pokazać "-". Jeśli masz
//     najwięcej kasy na serwerze, ma pokazać "#1".
//  6. Wiersz wskazówki ma się zmieniać co ~8 sekund, tak jak w tab liście -
//     to jest ten sam placeholder %mainplugins_wskazowka%, więc oba miejsca
//     (tab i scoreboard) będą pokazywać TĘ SAMĄ treść w danej chwili.
//  7. /papi parse <nick> %mainplugins_ranga% w konsoli - szybki test bez
//     czekania na wizualne sprawdzenie w grze.
//
//
//  UWAGA WYDAJNOŚCIOWA (na przyszłość, nie teraz)
//
//  getPozycjaWRankingu() przechodzi po WSZYSTKICH kontach w ekonomia.yml przy
//  każdym odpytaniu. Scoreboard TAB domyślnie odświeża się co pół sekundy
//  (patrz placeholder-refresh-intervals w configu, default-refresh-interval: 500)
//  - przy małej liczbie graczy (dziesiątki) to bez znaczenia, przy tysiącach
//  kont w pliku warto by było dodać interwał odświeżania specyficzny dla tego
//  placeholdera w tej samej sekcji configu, np.:
//
//    placeholder-refresh-intervals:
//      "%mainplugins_moje_miejsce%": 5000
//
//  żeby liczyć to raz na 5 sekund, a nie dwa razy na sekundę.
// =============================================================================
