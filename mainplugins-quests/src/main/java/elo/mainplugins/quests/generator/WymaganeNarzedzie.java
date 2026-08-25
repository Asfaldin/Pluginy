package elo.mainplugins.quests.generator;

/** Jakim narzędziem trzeba kopać dany generator - patrz GeneratorManager#jestWlasciwymNarzedziem. */
public enum WymaganeNarzedzie {
    PICKAXE, SHOVEL;

    public static WymaganeNarzedzie zNazwy(String nazwa, WymaganeNarzedzie domyslne) {
        if (nazwa == null) return domyslne;
        try {
            return WymaganeNarzedzie.valueOf(nazwa.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return domyslne;
        }
    }
}
