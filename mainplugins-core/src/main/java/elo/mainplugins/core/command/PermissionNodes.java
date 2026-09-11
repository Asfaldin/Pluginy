package elo.mainplugins.core.command;

import java.util.Locale;

/** Domyślne uprawnienie komendy bez własnego: mainplugins.<plugin>.command[.admin].<nazwa>. */
public final class PermissionNodes {

    private PermissionNodes() {}

    public static String nodeFor(String pluginName, String command) {
        String plugin = pluginName.toLowerCase(Locale.ROOT).replaceFirst("^mainplugins", "");
        String cmd = command.toLowerCase(Locale.ROOT);
        return "mainplugins." + plugin + ".command." + (isAdmin(cmd) ? "admin." + cmd.substring(1) : cmd);
    }

    public static boolean isAdmin(String command) {
        return command.startsWith("@");
    }
}
