package elo.mainplugins.license;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseTokenTest {

    private static String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static final String FORGED_TOKEN = b64url(
            "{\"v\":1,\"plugin\":\"crates\",\"serverId\":\"s1\",\"nonce\":\"n\",\"iat\":1,\"exp\":99999999999999}");

    @Test
    void rejectsMissingOrMalformedProofs() {
        assertNull(LicenseToken.verifyProof(null));
        assertNull(LicenseToken.verifyProof(""));
        assertNull(LicenseToken.verifyProof("no-dot"));
        assertNull(LicenseToken.verifyProof("."));
        assertNull(LicenseToken.verifyProof(FORGED_TOKEN + "."));
        assertNull(LicenseToken.verifyProof("!!!.@@@"));
    }

    @Test
    void rejectsTokenWithForgedSignature() {
        // 64 bajty "podpisu" - poprawna długość Ed25519, ale nie z naszego klucza.
        String fakeSig = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
        assertNull(LicenseToken.verify(FORGED_TOKEN, fakeSig));
        assertNull(LicenseToken.verifyProof(FORGED_TOKEN + "." + fakeSig));
    }

    @Test
    void publicKeyLoads() {
        // Błąd w stałej PUBLIC_KEY wywaliłby klasę przy starcie każdego płatnego pluginu.
        assertNull(LicenseToken.verify("x", "y"));
    }

    @Test
    void claimsExpiryAndPluginChecks() {
        LicenseToken.Claims c = new LicenseToken.Claims("crates", "s1", "n", 1_000, 10_000);
        assertTrue(c.validFor("crates", 5_000));
        assertFalse(c.validFor("crates", 10_000), "expired");
        assertFalse(c.validFor("shop", 5_000), "other plugin");
        LicenseToken.Claims future = new LicenseToken.Claims("crates", "s1", "n", 10L * 24 * 60 * 60 * 1000, Long.MAX_VALUE);
        assertFalse(future.validFor("crates", 0), "issued far in the future");
    }
}
