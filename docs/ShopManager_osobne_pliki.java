// =============================================================================
//  OSOBNY PLIK YML NA KATEGORIĘ
//
//  Zamiast jednego sklep.yml z sekcją "categories:" zawierającą wszystko,
//  każda kategoria dostaje własny plik w folderze categories/. Kod scala je
//  w jeden config w pamięci, więc reszta ShopManagera (znajdzOferteSkupu,
//  otworzKategorieStrona, sprzedajLoty itd.) nie wymaga ŻADNYCH zmian —
//  wszystkie odwołują się do sklepConfig po ścieżce "categories.<klucz>...",
//  a ta ścieżka dalej istnieje, tylko powstaje inaczej.
//
//  Struktura na dysku:
//    plugins/MainpluginsShop/
//      sklep.yml              <- zostaje, ale bez sekcji categories (patrz niżej)
//      categories/
//        bloki.yml
//        roslinki.yml
//        mineraly.yml
//        ... (jeden plik na kategorię, nazwa pliku = klucz kategorii)
//
//  Każdy plik w categories/ ma dokładnie taką zawartość, jaka wcześniej
//  siedziała pod "categories.<klucz>:" w sklep.yml — bez tego zagnieżdżenia,
//  bo nazwa pliku JEST kluczem.
// =============================================================================


// --- KROK 1: metoda scalająca ------------------------------------------------
// Dopisz w ShopManager.java, obok stworzLubWczytajPlikSklepu().

    /**
     * Wczytuje sklep.yml (ustawienia globalne, jeśli jakieś zostały) i dokleja
     * do niego zawartość każdego pliku z categories/ pod "categories.<nazwa>".
     * Brakujący folder categories/ nie jest błędem — po prostu nie ma kategorii.
     */
    private YamlConfiguration wczytajSklepZFolderow() {
        File plikGlowny = new File(getDataFolder(), "sklep.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(plikGlowny);

        File folderKategorii = new File(getDataFolder(), "categories");
        File[] pliki = folderKategorii.listFiles((dir, name) -> name.endsWith(".yml"));
        if (pliki == null) {
            getLogger().warning("Brak folderu categories/ — sklep będzie pusty.");
            return cfg;
        }

        for (File plik : pliki) {
            String klucz = plik.getName().substring(0, plik.getName().length() - 4); // bez ".yml"
            YamlConfiguration kat = YamlConfiguration.loadConfiguration(plik);

            // getValues(true) daje płaską mapę ze wszystkimi zagnieżdżeniami —
            // createSection ją odtwarza jako pełną strukturę sekcji.
            cfg.createSection("categories." + klucz, kat.getValues(true));
        }

        getLogger().info("Wczytano " + pliki.length + " kategorii z categories/.");
        return cfg;
    }


// --- KROK 2: podmień wywołanie w miejscu, gdzie sklepConfig jest budowany ---
// Wszędzie tam, gdzie dotąd było coś w rodzaju:
//   sklepConfig = YamlConfiguration.loadConfiguration(plikSklepu);
// (zarówno przy starcie pluginu, jak i w handlerze /reloadsklep) zamień na:

        sklepConfig = wczytajSklepZFolderow();

// Reszta kodu (znajdzOferteSkupu, otworzSklep, otworzKategorieStrona,
// sprzedajLoty...) zostaje bez zmian — dalej czyta z sklepConfig po tych
// samych ścieżkach "categories.<klucz>...", więc nic więcej nie trzeba ruszać.


// --- KROK 3: pierwsze uruchomienie -------------------------------------------
// Jeśli folder categories/ ma powstać automatycznie przy pierwszym starcie
// (analogicznie do saveResource dla sklep.yml), dodaj w miejscu inicjalizacji:

        File folderKategorii = new File(getDataFolder(), "categories");
        if (!folderKategorii.exists()) {
            folderKategorii.mkdirs();
            // Jeśli w src/main/resources/categories/ leżą domyślne pliki,
            // skopiuj je tu przez saveResource("categories/" + nazwa + ".yml", false)
            // dla każdej znanej kategorii. W innym wypadku administrator sam
            // wrzuca pliki .yml do tego folderu.
        }
