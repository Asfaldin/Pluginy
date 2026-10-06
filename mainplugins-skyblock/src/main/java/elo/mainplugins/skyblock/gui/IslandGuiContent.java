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
        IslandScreen czlonkowieWyspy,
        /** Pola na główki członków wyspy, po kolei; więcej członków niż pól = reszta się nie mieści. */
        int[] czlonkowieSloty
) {
}
