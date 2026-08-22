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
        int[] ulepszenieSpawnerowSlotyTypow,
        IslandScreen spawnerPodmenu,
        IslandScreen czlonkowieWyspy,
        int czlonkowieSlotWlasciciela,
        int czlonkowiePierwszySlot,
        int czlonkowieOstatniSlot
) {
}
