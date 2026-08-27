package elo.mainplugins.fishing;

/**
 * Gdzie wyświetlić pasek minigry łowienia (patrz FishingMinigame) - wybór gracza,
 * zapamiętywany w PersistentDataContainer samego gracza (patrz FishingManager.pozycjaPaska/
 * ustawPozycjePaska), ustawiany komendą /rybpasek gora|dol w MainpluginsFishing.
 *
 * GORA to dotychczasowe (jedyne) zachowanie - prawdziwy BossBar u góry ekranu, z natywnym
 * kolorowym paskiem wypełnienia pod tytułem. DOL zamiast bossbara używa action bara (tekst
 * tuż nad hotbarem/paskiem doświadczenia) - action bar nie ma natywnego paska wypełnienia,
 * więc procent postępu jest tam zamiast tego dopisywany liczbowo na początku linii (patrz
 * FishingMinigame.zbudujPasekActionBar).
 */
enum PozycjaPaska {
    GORA("na górze ekranu (bossbar)"),
    DOL("nad paskiem doświadczenia (action bar)");

    private final String opis;

    PozycjaPaska(String opis) {
        this.opis = opis;
    }

    String opis() {
        return opis;
    }
}
