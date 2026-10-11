package elo.mainplugins.coreminer.config;

import org.bukkit.Material;

import java.util.List;

/** Cały coreminer.yml - niemutowalny snapshot, podmieniany przy /@coreminer reload. */
public record MinerConfig(MinerSettings ustawienia, List<MinerGroup> grupy) {

    /** Pierwsza włączona grupa z tym blokiem albo null (blok nie jest kopany żyłą). */
    public MinerGroup grupaDla(Material m) {
        for (MinerGroup g : grupy) if (g.wlaczona() && g.zawiera(m)) return g;
        return null;
    }
}
