package elo.mainplugins.core.api;

/**
 * Weryfikacja licencji dla płatnych pluginów ekosystemu Mainplugins. Rejestrowany
 * przez sam MainpluginsCore (patrz {@link elo.mainplugins.core.license.LicenseManager}) -
 * dostępny zawsze, gdy tylko Core jest włączony, tak jak {@code CustomItemService}.
 *
 * Model: jeden klucz licencyjny = jeden serwer Minecraft. Klucze i URL serwera
 * licencyjnego konfiguruje się w {@code license.yml} w folderze danych Core.
 * Plugin darmowy (np. mainplugins-announcer) NIE powinien w ogóle wołać tego
 * serwisu - sprawdzanie licencji dotyczy wyłącznie pluginów płatnych.
 *
 * Użycie (zwykle na samej górze {@code onEnable()} płatnego pluginu):
 * <pre>{@code
 * if (!CoreAPI.getLicenseService().isLicensed("tools")) {
 *     getLogger().severe("Brak ważnej licencji - plugin zostanie wyłączony.");
 *     getServer().getPluginManager().disablePlugin(this);
 *     return;
 * }
 * }</pre>
 */
public interface LicenseService {

    /**
     * @param pluginId identyfikator pluginu tak, jak wpisany w sekcji {@code keys} pliku
     *                 license.yml (np. "tools", "shop") - NIE nazwa klasy ani plugin.yml.
     * @return true jeśli skonfigurowany klucz dla tego pluginu jest ważny (zweryfikowany
     *         na serwerze licencyjnym, albo w okresie karencji przy jego chwilowej
     *         niedostępności) - false jeśli brak klucza, klucz jest nieprawidłowy,
     *         odwołany, przypisany do innego serwera, albo okres karencji minął.
     */
    boolean isLicensed(String pluginId);
}
