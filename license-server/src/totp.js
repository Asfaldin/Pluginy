import { createHmac, randomBytes } from "node:crypto";

// Kody jednorazowe z aplikacji (Google Authenticator, Authy, 1Password...) - standard TOTP (RFC 6238):
// HMAC-SHA1 z sekretu i numeru 30-sekundowego okna, 6 cyfr. Bez zależności - to kilkanaście linijek.

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
const STEP_SECONDS = 30;

function base32Encode(buf) {
    let bits = 0, value = 0, out = "";
    for (const byte of buf) {
        value = (value << 8) | byte;
        bits += 8;
        while (bits >= 5) {
            out += ALPHABET[(value >>> (bits - 5)) & 31];
            bits -= 5;
        }
    }
    if (bits > 0) out += ALPHABET[(value << (5 - bits)) & 31];
    return out;
}

function base32Decode(str) {
    let bits = 0, value = 0;
    const out = [];
    for (const ch of str.replace(/[\s=]/g, "").toUpperCase()) {
        const idx = ALPHABET.indexOf(ch);
        if (idx < 0) continue;
        value = (value << 5) | idx;
        bits += 5;
        if (bits >= 8) {
            out.push((value >>> (bits - 8)) & 255);
            bits -= 8;
        }
    }
    return Buffer.from(out);
}

/** Nowy sekret (160 bitów, zapis base32 - tak jak go wpisuje się ręcznie w aplikacji). */
export function generateSecret() {
    return base32Encode(randomBytes(20));
}

function codeAt(secret, step) {
    const counter = Buffer.alloc(8);
    counter.writeBigUInt64BE(BigInt(step));
    const h = createHmac("sha1", base32Decode(secret)).update(counter).digest();
    const off = h[h.length - 1] & 15;
    const num = ((h[off] & 127) << 24) | (h[off + 1] << 16) | (h[off + 2] << 8) | h[off + 3];
    return String(num % 1_000_000).padStart(6, "0");
}

/**
 * Sprawdza kod z tolerancją ±1 okno (zegar telefonu może się spieszyć/spóźniać o ~30 s).
 * @param lastStep ostatnie użyte okno - ten sam kod nie przejdzie drugi raz (ochrona przed podsłuchanym kodem).
 * @returns numer okna, w którym kod pasuje (zapisz jako lastStep), albo null.
 */
export function verifyTotp(secret, code, lastStep = -1, now = Date.now()) {
    const clean = String(code ?? "").replace(/\s/g, "");
    if (!/^\d{6}$/.test(clean)) return null;
    const current = Math.floor(now / 1000 / STEP_SECONDS);
    for (const step of [current - 1, current, current + 1]) {
        if (step > lastStep && codeAt(secret, step) === clean) return step;
    }
    return null;
}

/** Adres otpauth:// do kodu QR - aplikacja pokaże "RSMC Admin (email)". */
export function otpauthUrl(secret, email) {
    const label = encodeURIComponent(`RSMC Admin:${email}`);
    return `otpauth://totp/${label}?secret=${secret}&issuer=${encodeURIComponent("RSMC Admin")}&algorithm=SHA1&digits=6&period=${STEP_SECONDS}`;
}

export const _test = { codeAt, base32Decode, base32Encode };
