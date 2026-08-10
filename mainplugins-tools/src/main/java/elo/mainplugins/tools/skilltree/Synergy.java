package elo.mainplugins.tools.skilltree;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.Map;

/**
 * Kombinacja kart - bonus odblokowywany EMERGENTNIE, gdy gracz zainwestuje jednocześnie
 * w kilka konkretnych kart do podanego progu (np. "WYD_SPEED≥10 + MAG_HASTE≥3"), a nie
 * kupiony wprost jak zwykła karta. Ponieważ karty nigdy nie tracą poziomów, spełnienie
 * wymagań jest trwałe (patrz ToolSkillManager#hasSynergy - liczone na żywo z liczników
 * kart, bez osobnego stanu w PDC).
 *
 * Sam efekt synergii NIE jest tu zdefiniowany - to tylko deklaracja "co trzeba mieć i jak
 * to się nazywa"; realny bonus dopisuje podklasa managera (np. PickaxeSkillManager) w tym
 * samym miejscu co dziś hasRare(...), pod `hasSynergy(pdc, id)`.
 *
 * `rarity` jest tu czysto opisowa (jak "trudna/wartościowa" jest kombinacja) - NIE steruje
 * losowaniem jak Rarity kart, kombinacji nie da się "wylosować", tylko zbudować świadomie.
 * `highlightColor` to kolor podświetlenia w menu Kombinacji po kliknięciu (patrz
 * ToolSkillManager#openInfo).
 *
 * @param wymagania cardId -> minimalna liczba poziomów tej karty (WSZYSTKIE muszą być spełnione naraz)
 */
public record Synergy(String id, String displayName, Material icon, String opis, Rarity rarity,
                       NamedTextColor highlightColor, Map<String, Integer> wymagania) {}