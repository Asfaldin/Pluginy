package elo.mainplugins.tools.evolving;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * Jeden wpis z ewoluujace-narzedzia.yml, w pełni wczytany i sparsowany (patrz
 * EvolvingToolLoader) - odpowiednik CustomItemDefinition (mainplugins-core), tylko dla
 * STANOWEGO (poziom/exp per-instancja) narzędzia zamiast statycznego przedmiotu.
 *
 * id = custom-id (patrz CustomItemKeys#CUSTOM_ITEM_ID) - tym samym mechanizmem co
 * custom-items.yml, więc inne pluginy (sklep, questy) rozpoznają narzędzie identycznie
 * jak każdy inny custom item, mimo że fizycznie żyje w osobnym rejestrze.
 */
public record ToolDefinition(
        String id,
        Kategoria kategoria,
        Material material,
        Component nazwa,
        Key model,
        boolean glint,
        int maxPoziom,
        int expNaPoziom,
        List<ToolStat> staty,
        List<EnchantProgress> enchanty,
        Map<Integer, List<ToolEffect>> kamienieMilowe,
        List<ToolEffect> pasywne,
        String czastkaOtoczenia
) {
    public List<ToolEffect> efektyOdblokowaneDo(int poziom) {
        return kamienieMilowe.entrySet().stream()
                .filter(e -> e.getKey() <= poziom)
                .flatMap(e -> e.getValue().stream())
                .toList();
    }
}
