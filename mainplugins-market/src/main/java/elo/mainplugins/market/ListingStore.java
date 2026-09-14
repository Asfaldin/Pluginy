package elo.mainplugins.market;

import elo.mainplugins.market.model.Listing;
import elo.mainplugins.market.model.MailItem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dane Targu w listings.yml: oferty, skrzynki "Do odebrania", zarobki offline i zwroty.
 * Trzyma wszystko w pamięci i od razu wpisuje do yml; zapis na dysk robi wołający (AsyncConfigSaver).
 * Przedmioty jako tekst Base64 - bez serwera, więc da się testować.
 */
public final class ListingStore {

    /** Wpis ze starego rynek.yml (przedmiot już zamieniony na Base64). */
    public record LegacyOffer(String item, long price, String seller, String sellerName) {}

    private final YamlConfiguration yml;
    private final Map<String, Listing> listings = new LinkedHashMap<>();

    public ListingStore(YamlConfiguration yml) {
        this.yml = yml;
        ConfigurationSection s = yml.getConfigurationSection("listings");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection l = s.getConfigurationSection(id);
            UUID seller = uuid(l == null ? null : l.getString("seller"));
            if (l == null || seller == null || l.getString("item") == null) continue;
            listings.put(id, new Listing(id, seller, l.getString("seller-name", "?"), l.getLong("price"),
                    l.getLong("listed-at"), l.getString("item")));
        }
    }

    public YamlConfiguration yaml() {
        return yml;
    }

    // ---------- oferty ----------

    public List<Listing> listings() {
        return List.copyOf(listings.values());
    }

    public Listing get(String id) {
        return listings.get(id);
    }

    public void add(Listing l) {
        listings.put(l.id(), l);
        String p = "listings." + l.id() + ".";
        yml.set(p + "seller", l.seller().toString());
        yml.set(p + "seller-name", l.sellerName());
        yml.set(p + "price", l.price());
        yml.set(p + "listed-at", l.listedAt());
        yml.set(p + "item", l.item());
    }

    public Listing remove(String id) {
        Listing l = listings.remove(id);
        if (l != null) yml.set("listings." + id, null);
        return l;
    }

    public int countBy(UUID seller) {
        int n = 0;
        for (Listing l : listings.values()) if (l.seller().equals(seller)) n++;
        return n;
    }

    public List<Listing> expired(long now, int expireDays) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings.values()) if (MarketRules.expired(l.listedAt(), now, expireDays)) out.add(l);
        return out;
    }

    /** Przenosi oferty ze starego rynek.yml; pomija złe (cena <= 0, zły UUID). Zwraca liczbę przeniesionych. */
    public int importLegacy(Map<String, LegacyOffer> old, long now) {
        int n = 0;
        for (Map.Entry<String, LegacyOffer> e : old.entrySet()) {
            LegacyOffer o = e.getValue();
            UUID seller = uuid(o.seller());
            if (seller == null || o.price() <= 0 || o.item() == null) continue;
            add(new Listing(e.getKey(), seller, o.sellerName() == null ? "?" : o.sellerName(), o.price(), now, o.item()));
            n++;
        }
        return n;
    }

    // ---------- skrzynka "Do odebrania" ----------

    public List<MailItem> mailbox(UUID player) {
        List<MailItem> out = new ArrayList<>();
        for (Map<?, ?> m : yml.getMapList("mailbox." + player)) {
            Object item = m.get("item");
            if (item == null) continue;
            out.add(new MailItem(m.get("added-at") instanceof Number n ? n.longValue() : 0, String.valueOf(item)));
        }
        return out;
    }

    public void addMail(UUID player, MailItem item) {
        List<MailItem> list = mailbox(player);
        list.add(item);
        writeMailbox(player, list);
    }

    /** Zabiera przedmiot o danym numerze; null, gdy numeru nie ma. */
    public MailItem takeMail(UUID player, int index) {
        List<MailItem> list = mailbox(player);
        if (index < 0 || index >= list.size()) return null;
        MailItem taken = list.remove(index);
        writeMailbox(player, list);
        return taken;
    }

    private void writeMailbox(UUID player, List<MailItem> list) {
        if (list.isEmpty()) {
            yml.set("mailbox." + player, null);
            return;
        }
        List<Map<String, Object>> raw = new ArrayList<>();
        for (MailItem m : list) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("added-at", m.addedAt());
            e.put("item", m.item());
            raw.add(e);
        }
        yml.set("mailbox." + player, raw);
    }

    // ---------- zarobki offline ----------

    public void addEarning(UUID seller, long amount, int count) {
        String p = "earnings." + seller + ".";
        yml.set(p + "amount", yml.getLong(p + "amount") + amount);
        yml.set(p + "count", yml.getInt(p + "count") + count);
    }

    /** {kwota, liczba sprzedanych} i czyści; null, gdy nic nie czeka. */
    public long[] takeEarnings(UUID seller) {
        String p = "earnings." + seller;
        if (!yml.contains(p)) return null;
        long[] out = {yml.getLong(p + ".amount"), yml.getLong(p + ".count")};
        yml.set(p, null);
        return out;
    }

    // ---------- zwroty, gdy skrzynka wyłączona ----------

    public void addPendingReturn(UUID player, String item) {
        List<String> list = new ArrayList<>(yml.getStringList("returns." + player));
        list.add(item);
        yml.set("returns." + player, list);
    }

    public List<String> takePendingReturns(UUID player) {
        List<String> list = yml.getStringList("returns." + player);
        yml.set("returns." + player, null);
        return list;
    }

    private static UUID uuid(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
