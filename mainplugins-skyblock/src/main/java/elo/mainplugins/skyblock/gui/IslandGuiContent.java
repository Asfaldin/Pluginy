package elo.mainplugins.skyblock.gui;

/**
 * Cały układ GUI systemu wysp wczytany z wyspy-gui.yml (patrz IslandGuiLoader) - jeden
 * niemutowalny snapshot, podmieniany w całości przy /@reloadwyspy.
 */
public record IslandGuiContent(
        IslandScreen panelWyspy,
        IslandScreen permisjeWyspy,
        IslandScreen ustawieniaWyspy,
        IslandScreen topkaWysp,
        int[] topkaSlotyRankingu,
        IslandScreen ulepszeniaWyspy,
        IslandScreen ulepszenieSpawnerow,
        /** Wolne miejsca na spawnery bez własnego pola (np. nowe, dodane później w pluginie Spawnery), po kolei. */
        int[] ulepszenieSpawnerowSlotyTypow,
        /** Stałe miejsca spawnerów: id -> {pole, strona od 0}. */
        java.util.Map<String, int[]> spawneryPola,
        /** Spawnery schowane przez właściciela - nie ma ich w oknie. */
        java.util.Set<String> ukryteSpawnery,
        IslandScreen spawnerPodmenu,
        IslandScreen czlonkowieWyspy,
        /** Pola na główki członków wyspy, po kolei; więcej członków niż pól = reszta się nie mieści. */
        int[] czlonkowieSloty
) {
}
