package elo.mainplugins.core.customitem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Wszystkie wpisy ze wszystkich plików items/*.yml. Id bez rozróżniania wielkości liter; duplikat = wygrywa pierwszy. */
public final class ItemCatalog {

    private final Map<String, ItemSpec> byLowerId;

    private ItemCatalog(Map<String, ItemSpec> byLowerId) {
        this.byLowerId = byLowerId;
    }

    public static ItemCatalog build(List<ItemSpec> specsInLoadOrder, Consumer<String> warn) {
        Map<String, ItemSpec> map = new LinkedHashMap<>();
        for (ItemSpec spec : specsInLoadOrder) {
            String key = spec.id().toLowerCase(Locale.ROOT);
            ItemSpec first = map.get(key);
            if (first != null) {
                warn.accept(spec.sourceFile() + ": duplicate item id '" + spec.id() + "' (already defined in "
                        + first.sourceFile() + ") - keeping the first one.");
                continue;
            }
            map.put(key, spec);
        }
        return new ItemCatalog(Collections.unmodifiableMap(map));
    }

    public ItemSpec get(String id) {
        return id == null ? null : byLowerId.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean contains(String id) {
        return get(id) != null;
    }

    public Set<String> ids() {
        Set<String> out = new LinkedHashSet<>();
        for (ItemSpec spec : byLowerId.values()) out.add(spec.id());
        return out;
    }
}
