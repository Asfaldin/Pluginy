package elo.mainplugins.license;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Podpisany token licencji z serwera licencji (license-server/src/signing.js).
 * Token = base64url(JSON), podpis = Ed25519 po bajtach tokenu, base64url.
 * Klucz publiczny jest wbudowany tutaj - przyjmujemy wyłącznie tokeny podpisane kluczem
 * prywatnym, który zna tylko serwer licencji. Nie da się ich podrobić fałszywym serwerem
 * (api.url w license.yml) ani podmienionym Core.
 *
 * Postać przekazywana między Core a pluginami ("dowód"): {@code <token>.<podpis>}.
 */
public final class LicenseToken {

    /** Produkcyjny klucz publiczny Ed25519 (SPKI DER, base64) - para do LICENSE_SIGNING_KEY na VPS. */
    static final String PROD_PUBLIC_KEY = "MCowBQYDK2VwAyEAKDFHxn9IGm5OupdT8CAb1B4Shfo+JNpR0ipKVg2zwRA=";

    /**
     * Klucz, którym ten jar sprawdza licencje: wpisany przy budowaniu (-Dlicense.publicKey=..., domyślnie
     * produkcyjny), żeby dało się testować z własnym license-serverem. Celowo tylko przy budowaniu - gdyby
     * dało się go zmienić w configu serwera, każdy podstawiłby własny klucz i odblokował płatne pluginy.
     */
    static final String PUBLIC_KEY = buildKey();

    private static String buildKey() {
        try (InputStream in = LicenseToken.class.getResourceAsStream("/elo/mainplugins/license/license-key.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String k = p.getProperty("publicKey", "").trim();
                if (!k.isEmpty() && !k.contains("${")) return k;
            }
        } catch (IOException ignored) {
            // brak zasobu = klucz produkcyjny
        }
        return PROD_PUBLIC_KEY;
    }

    /** Jar zbudowany z innym kluczem niż produkcyjny (do testów) - Core ostrzega o tym w logu. */
    public static boolean isDevKey() {
        return !PUBLIC_KEY.equals(PROD_PUBLIC_KEY);
    }

    /** Ile zegar serwera Minecrafta może się spieszyć względem serwera licencji. */
    private static final long CLOCK_SKEW_MS = 24L * 60 * 60 * 1000;

    public record Claims(String plugin, String serverId, String nonce, long issuedAt, long expiresAt) {
        /** Token dotyczy tego pluginu i jest ważny w chwili {@code now}. */
        public boolean validFor(String pluginId, long now) {
            return plugin.equals(pluginId) && now < expiresAt && issuedAt <= now + CLOCK_SKEW_MS;
        }
    }

    private static final PublicKey KEY = loadKey();

    private static PublicKey loadKey() {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load the license public key", e);
        }
    }

    private LicenseToken() {}

    /** Dowód w postaci "token.podpis" -> dane z tokenu, albo null, gdy podpis się nie zgadza. */
    public static Claims verifyProof(String proof) {
        if (proof == null) return null;
        int dot = proof.indexOf('.');
        if (dot <= 0 || dot == proof.length() - 1) return null;
        return verify(proof.substring(0, dot), proof.substring(dot + 1));
    }

    /** Sprawdza podpis tokenu i zwraca jego dane, albo null, gdy podpis/format się nie zgadza. */
    public static Claims verify(String token, String signature) {
        if (token == null || signature == null || token.length() > 4096 || signature.length() > 256) return null;
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(KEY);
            sig.update(token.getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(Base64.getUrlDecoder().decode(signature))) return null;
            String json = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (longField(json, "v") != 1) return null;
            String plugin = stringField(json, "plugin");
            String serverId = stringField(json, "serverId");
            String nonce = stringField(json, "nonce");
            long iat = longField(json, "iat");
            long exp = longField(json, "exp");
            if (plugin == null || serverId == null || iat < 0 || exp < 0) return null;
            return new Claims(plugin, serverId, nonce == null ? "" : nonce, iat, exp);
        } catch (Exception e) {
            return null;
        }
    }

    // JSON tokenu ma stały, płaski kształt i jest już zweryfikowany podpisem - wystarczy
    // prosty odczyt pól (bez zewnętrznej biblioteki JSON).
    private static String stringField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"\\\\]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static long longField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d{1,18})").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }
}
