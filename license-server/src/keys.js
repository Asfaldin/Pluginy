import { randomBytes } from "node:crypto";

// Alfabet bez znaków łatwych do pomylenia (0/O, 1/I/L) - klucz czasem trzeba
// będzie przepisać ręcznie (Discord, e-mail).
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

function randomGroup(length) {
    const bytes = randomBytes(length);
    let out = "";
    for (let i = 0; i < length; i++) {
        out += ALPHABET[bytes[i] % ALPHABET.length];
    }
    return out;
}

/** Format: MPX-XXXXX-XXXXX-XXXXX */
export function generateLicenseKey() {
    return `MPX-${randomGroup(5)}-${randomGroup(5)}-${randomGroup(5)}`;
}
