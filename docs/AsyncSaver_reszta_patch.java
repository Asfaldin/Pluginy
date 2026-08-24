// =============================================================================
//  ASYNCHRONICZNY ZAPIS — reszta managerów (Wyspy, Spawnery, Questy, Targ)
// =============================================================================
//
//  AsyncConfigSaver już działa w EconomyManager, DynamicPriceManager
//  i StatystykiSklepu (mainplugins-core / mainplugins-shop). Ten patch podpina
//  DOKŁADNIE ten sam mechanizm do czterech pozostałych managerów, które wciąż
//  zapisują plik synchronicznie przy KAŻDEJ zmianie - stawianie spawnera,
//  ukończenie questa, wystawienie oferty na targu, cokolwiek na wyspie.
//
//  Przy pojedynczym graczu tego nie czuć. Przy wielu graczach robiących to
//  jednocześnie każdy zapis na chwilę zatrzymuje CAŁY serwer (zapis pliku na
//  dysk jest fizycznie najwolniejszą operacją, jaką komputer wykonuje) - objawia
//  się to jako lagi/przycinanie przy większym ruchu.
//
//  Wzorzec jest identyczny w każdym z 4 plików, bo każdy manager ma tę samą
//  strukturę: private final File plikX; private final FileConfiguration configX;
//  private void zapiszX() { configX.save(plikX); }. Zmiana to zawsze te same
//  4 kroki, tylko inne nazwy pól - patrz sekcje 1-4 poniżej.
//
//  WAŻNE: sam wzorzec zmiany opisałem raz dokładnie w SEKCJI 1 (IslandManager).
//  Sekcje 2-4 są skrócone, bo to dosłownie to samo, tylko inne nazwy - jeśli
//  coś jest niejasne w sekcjach 2-4, patrz pełne wyjaśnienie w sekcji 1.
// =============================================================================


// =============================================================================
//  SEKCJA 1/4: IslandManager.java (mainplugins-skyblock)
// =============================================================================

// --- 1a. import ------------------------------------------------------------

import elo.mainplugins.core.util.AsyncConfigSaver;


// --- 1b. nowe pole -----------------------------------------------------------
// Dopisz obok pól plikWysp / configWysp:

    private final AsyncConfigSaver saverWysp;


// --- 1c. utworzenie w konstruktorze -------------------------------------------
// W konstruktorze IslandManager(Plugin plugin, EconomyService economyManager),
// PO tym jak configWysp jest już wczytany (czyli po ewentualnym
// YamlConfiguration.loadConfiguration(plikWysp) albo podobnym), dopisz:

        this.saverWysp = new AsyncConfigSaver(plugin, configWysp, plikWysp, 30);

// Cykl 30 sekund - ten sam, którego już używamy w EconomyManager. Zmiany na
// wyspie nie są aż tak krytyczne jak saldo, więc nie trzeba krótszego cyklu.


// --- 1d. podmiana metody zapiszWyspy() -----------------------------------------
// Metoda zapiszWyspy() (linia ~530) dziś kończy się (albo w środku zawiera)
// wywołaniem configWysp.save(plikWysp) w bloku try/catch (linia ~600). Znajdź
// TĘ JEDNĄ linijkę:
//
//   BYŁO:
//     configWysp.save(plikWysp);
//
//   MA BYĆ:

        saverWysp.oznaczZmiane();

// Cały otaczający blok try/catch (IOException) można usunąć, bo
// oznaczZmiane() nie rzuca wyjątku - to tylko ustawienie flagi w pamięci,
// żadnego I/O nie ma w tym miejscu. Jeśli w tej samej metodzie zapiszWyspy()
// jest coś WIĘCEJ niż tylko save() (np. dodatkowa logika przed/po), zostaw tę
// resztę bez zmian - podmieniamy WYŁĄCZNIE linię z .save().


// --- 1e. zamknięcie przy wyłączaniu pluginu ------------------------------------
// Dopisz nową metodę publiczną w IslandManager:

    /** Wywołaj w onDisable() modułu skyblock - zapisuje natychmiast, zatrzymuje cykl. */
    public void zamknij() {
        saverWysp.zamknij();
    }

// I w MainpluginsSkyblock.java, w onDisable(), PRZED unregisterAll:

        if (islandManager != null) islandManager.zamknij();


// =============================================================================
//  SEKCJA 2/4: SpawnerManager.java (mainplugins-spawners)
// =============================================================================
//
//  Ten sam wzorzec co sekcja 1, inne nazwy: plikSpawnerow / configSpawnerow,
//  metoda zapisz() (linia ~153), zapis configSpawnerow.save(plikSpawnerow)
//  w try/catch (linia ~166).

// import:
import elo.mainplugins.core.util.AsyncConfigSaver;

// pole:
    private final AsyncConfigSaver saverSpawnerow;

// w konstruktorze SpawnerManager(Plugin plugin), po wczytaniu configSpawnerow:
        this.saverSpawnerow = new AsyncConfigSaver(plugin, configSpawnerow, plikSpawnerow, 30);

// w metodzie zapisz(), zamień:
//   try { configSpawnerow.save(plikSpawnerow); } catch (...) { ... }
// na:
        saverSpawnerow.oznaczZmiane();

// nowa metoda:
    public void zamknij() {
        saverSpawnerow.zamknij();
    }

// w MainpluginsSpawners.java, onDisable(), przed unregisterAll:
        if (spawnerManager != null) spawnerManager.zamknij();


// =============================================================================
//  SEKCJA 3/4: QuestManager.java (mainplugins-quests)
// =============================================================================
//
//  Ten sam wzorzec: plikPostepow / configPostepow, metoda zapiszPostep()
//  (linia ~234), zapis configPostepow.save(plikPostepow) w try/catch
//  (linia ~249).

// import:
import elo.mainplugins.core.util.AsyncConfigSaver;

// pole:
    private final AsyncConfigSaver saverPostepow;

// w konstruktorze QuestManager(Plugin plugin), po wczytaniu configPostepow:
        this.saverPostepow = new AsyncConfigSaver(plugin, configPostepow, plikPostepow, 30);

// w metodzie zapiszPostep(), zamień:
//   try { configPostepow.save(plikPostepow); } catch (...) { ... }
// na:
        saverPostepow.oznaczZmiane();

// nowa metoda:
    public void zamknij() {
        saverPostepow.zamknij();
    }

// w MainpluginsQuests.java, onDisable(), przed unregisterAll:
        if (questManager != null) questManager.zamknij();


// =============================================================================
//  SEKCJA 4/4: MarketManager.java (mainplugins-market)
// =============================================================================
//
//  Ten sam wzorzec: plikRynku / configRynku, metoda zapiszRynek()
//  (linia ~111), zapis configRynku.save(plikRynku) w try/catch (linia ~113).
//
//  UWAGA specyficzna dla targu: oferty kupna/sprzedaży to realne pieniądze
//  i realne przedmioty graczy - w razie awarii serwera między zapisem
//  w pamięci a zrzutem na dysk najnowsza transakcja MOGŁABY się nie zapisać.
//  Dlatego cykl dla targu jest KRÓTSZY niż w pozostałych trzech modułach
//  (10 sekund zamiast 30) - mniejsze okno ryzyka, kosztem odrobinę częstszych
//  zapisów w tle. To nadal I/O asynchroniczne, więc nie zatrzymuje serwera -
//  krótszy cykl tutaj jest praktycznie bezkosztowy.

// import:
import elo.mainplugins.core.util.AsyncConfigSaver;

// pole:
    private final AsyncConfigSaver saverRynku;

// w konstruktorze MarketManager(Plugin plugin, EconomyService economyManager),
// po wczytaniu configRynku:
        this.saverRynku = new AsyncConfigSaver(plugin, configRynku, plikRynku, 10);

// w metodzie zapiszRynek(), zamień:
//   try { configRynku.save(plikRynku); } catch (...) { ... }
// na:
        saverRynku.oznaczZmiane();

// nowa metoda:
    public void zamknij() {
        saverRynku.zamknij();
    }

// w MainpluginsMarket.java, onDisable(), przed unregisterAll:
        if (marketManager != null) marketManager.zamknij();


// =============================================================================
//  CO SPRAWDZIĆ PO WGRANIU (dla KAŻDEGO z 4 modułów osobno)
//
//  1. Zrób zmianę (postaw spawner / ukończ quest / wystaw ofertę na targu /
//     zmień coś na wyspie) - efekt w grze ma być natychmiastowy, tak jak
//     dotąd (dane siedzą w pamięci, gra ich nie odróżnia).
//  2. Odczekaj 30 sekund, zajrzyj do pliku .yml na dysku - zmiana ma tam być.
//  3. Zrób zmianę i ZRESTARTUJ serwer w ciągu kilku sekund - zmiana ma się
//     ZACHOWAĆ (to sprawdza, czy zamknij() w onDisable() faktycznie działa).
//  4. W folderze pluginu nie powinny zostawać pliki .tmp po restarcie - jeśli
//     zostają, znaczy że podmiana pliku (wewnątrz AsyncConfigSaver) się nie
//     kończy poprawnie.
//
//  Rób to po kolei, jeden moduł na raz (najpierw skyblock, potem spawners,
//  quests, market) - łatwiej złapać, w którym module coś poszło nie tak,
//  niż wgrywać wszystkie 4 na raz i zgadywać.
// =============================================================================
