package elo.mainplugins.market;

import elo.mainplugins.market.model.Listing;
import elo.mainplugins.market.model.MailItem;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ListingStoreTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final long DAY = 86_400_000L;

    private ListingStore reload(ListingStore s) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(s.yaml().saveToString());
        return new ListingStore(y);
    }

    @Test
    void everythingSurvivesSaveAndLoad() throws Exception {
        ListingStore s = new ListingStore(new YamlConfiguration());
        s.add(new Listing("o1", A, "Ala", 100, 5, "AAA="));
        s.add(new Listing("o2", B, "Bob", 250, 6, "BBB="));
        s.addMail(A, new MailItem(7, "CCC="));
        s.addMail(A, new MailItem(8, "DDD="));
        s.addEarning(B, 95, 1);
        s.addEarning(B, 5, 2);
        s.addPendingReturn(A, "EEE=");

        ListingStore r = reload(s);
        assertEquals(s.listings(), r.listings());
        assertEquals(List.of(new MailItem(7, "CCC="), new MailItem(8, "DDD=")), r.mailbox(A));
        assertArrayEquals(new long[]{100, 3}, r.takeEarnings(B));
        assertEquals(List.of("EEE="), r.takePendingReturns(A));
    }

    @Test
    void countRemoveAndGet() {
        ListingStore s = new ListingStore(new YamlConfiguration());
        s.add(new Listing("o1", A, "Ala", 100, 5, "AAA="));
        s.add(new Listing("o2", A, "Ala", 50, 6, "BBB="));
        s.add(new Listing("o3", B, "Bob", 10, 7, "CCC="));
        assertEquals(2, s.countBy(A));
        assertEquals("o2", s.remove("o2").id());
        assertNull(s.remove("o2"));
        assertNull(s.get("o2"));
        assertEquals(1, s.countBy(A));
        assertEquals(List.of("o1", "o3"), s.listings().stream().map(Listing::id).toList());
    }

    @Test
    void mailTakingAndEarningsAreOneShot() {
        ListingStore s = new ListingStore(new YamlConfiguration());
        s.addMail(A, new MailItem(1, "X="));
        assertNull(s.takeMail(A, 5));
        assertEquals(new MailItem(1, "X="), s.takeMail(A, 0));
        assertTrue(s.mailbox(A).isEmpty());
        assertNull(s.takeEarnings(A));
        s.addEarning(A, 10, 1);
        assertNotNull(s.takeEarnings(A));
        assertNull(s.takeEarnings(A));
        assertTrue(s.takePendingReturns(B).isEmpty());
    }

    @Test
    void expiredPicksOnlyOldOffers() {
        ListingStore s = new ListingStore(new YamlConfiguration());
        s.add(new Listing("old", A, "Ala", 1, 0, "A="));
        s.add(new Listing("new", A, "Ala", 1, 6 * DAY, "B="));
        assertEquals(List.of("old"), s.expired(7 * DAY, 7).stream().map(Listing::id).toList());
        assertTrue(s.expired(7 * DAY, 0).isEmpty());
    }

    @Test
    void importLegacyKeepsGoodOffers() {
        ListingStore s = new ListingStore(new YamlConfiguration());
        int n = s.importLegacy(Map.of(
                "a", new ListingStore.LegacyOffer("A=", 100, A.toString(), "Ala"),
                "b", new ListingStore.LegacyOffer("B=", 0, A.toString(), "Ala"),
                "c", new ListingStore.LegacyOffer("C=", 5, "not-a-uuid", "X")), 42);
        assertEquals(1, n);
        Listing l = s.get("a");
        assertEquals(A, l.seller());
        assertEquals(100, l.price());
        assertEquals(42, l.listedAt());
        assertEquals("A=", l.item());
    }
}
