package elo.mainplugins.menu.model;

import java.util.List;

/** Cały menu.yml po wczytaniu: rozmiar okna, tło wolnych pól i przyciski. */
public record MenuConfig(int size, String background, List<MenuButton> buttons) {

    public static final String DEFAULT_BACKGROUND = "GRAY_STAINED_GLASS_PANE";

    public static MenuConfig empty() {
        return new MenuConfig(27, DEFAULT_BACKGROUND, List.of());
    }
}
