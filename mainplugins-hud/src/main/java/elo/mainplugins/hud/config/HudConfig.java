package elo.mainplugins.hud.config;

import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.api.TopGracz;

import java.util.List;

/**
 * Cała konfiguracja HUD/placeholderów wczytana z hud-config.yml (patrz HudConfigLoader) -
 * jeden niemutowalny snapshot, podmieniany w całości przy /@reloadhud.
 */
public record HudConfig(
        List<String> proTipy,
        int maxTop,
        int sekundNaSlajd,
        int coKtorySlajdRynkowy,
        int szerokoscProTipu,
        int szerokoscPadGracza,
        List<TopGracz> fakeTopGraczy,
        List<IslandSummary> fakeTopWysp
) {}
