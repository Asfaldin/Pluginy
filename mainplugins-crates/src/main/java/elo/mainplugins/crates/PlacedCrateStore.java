package elo.mainplugins.crates;

import elo.mainplugins.crates.model.PlacedCrate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** placed.yml <-> lista postawionych skrzynek (czysta logika). Złe wpisy pomijane z ostrzeżeniem. */
public final class PlacedCrateStore {

    private PlacedCrateStore() {}

    public static List<PlacedCrate> parse(List<?> raw, Consumer<String> warn) {
        List<PlacedCrate> out = new ArrayList<>();
        if (raw == null) return out;
        for (int i = 0; i < raw.size(); i++) {
            String where = "placed.yml [" + (i + 1) + "]";
            if (!(raw.get(i) instanceof Map<?, ?> m)
                    || m.get("crate") == null || m.get("world") == null
                    || !(m.get("x") instanceof Number x) || !(m.get("y") instanceof Number y) || !(m.get("z") instanceof Number z)) {
                warn.accept(where + ": needs crate, world, x, y, z - skipping.");
                continue;
            }
            out.add(new PlacedCrate(String.valueOf(m.get("crate")), String.valueOf(m.get("world")),
                    x.intValue(), y.intValue(), z.intValue()));
        }
        return out;
    }

    public static List<Map<String, Object>> toYaml(List<PlacedCrate> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlacedCrate p : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("crate", p.crate());
            m.put("world", p.world());
            m.put("x", p.x());
            m.put("y", p.y());
            m.put("z", p.z());
            out.add(m);
        }
        return out;
    }
}
