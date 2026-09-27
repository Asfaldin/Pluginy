package elo.mainplugins.menu.model;

import java.util.List;

/**
 * Jeden przycisk menu (buttons w menu.yml). command = komenda gracza bez "/" (player.performCommand).
 * requires = nazwa pluginu z plugin.yml (np. MainpluginsShop); gdy go nie ma na serwerze, przycisk się nie pokazuje.
 */
public record MenuButton(int slot, String material, String name, List<String> lore, String command, String requires) {
}
