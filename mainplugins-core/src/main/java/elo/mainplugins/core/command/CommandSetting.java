package elo.mainplugins.core.command;

import java.util.List;

/** Jeden wpis commands.yml: oryginalna nazwa z plugin.yml -> nowa nazwa, aliasy, włączona/wyłączona. */
public record CommandSetting(String command, boolean enabled, String name, List<String> aliases) {

    public CommandSetting {
        aliases = List.copyOf(aliases);
    }
}
