import { readFileSync } from "node:fs";
import nodemailer from "nodemailer";

// Wspólna wysyłka maili - licencje (lemonsqueezy.js), reset hasła, wsparcie, zapisy ze strony.
// Dwa sposoby (patrz .env.example):
//   1. SMTP - dowolna skrzynka, np. noreply@rsmc-network.pl w Hostido (SMTP_HOST, SMTP_PORT,
//      SMTP_USER, SMTP_PASS, FROM_EMAIL). Preferowany: poczta w UE, bez dodatkowej usługi.
//   2. Resend (RESEND_API_KEY + FROM_EMAIL) - gdy SMTP nie jest ustawiony.
// Bez żadnego z nich loguje ostrzeżenie i nic nie wysyła - dev dalej działa.

/** Adres w logach tylko częściowo (j***@gmail.com) - logi serwera nie przechowują pełnych adresów. */
const mask = (e) => String(e).replace(/^(.)[^@]*(@.*)$/, "$1***$2");

let transport = null;
function smtp() {
    const { SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS, DKIM_DOMAIN, DKIM_SELECTOR, DKIM_KEY_FILE } = process.env;
    if (!SMTP_HOST) return null;
    if (!transport) {
        const port = Number(SMTP_PORT || 465);
        const local = SMTP_HOST === "127.0.0.1" || SMTP_HOST === "localhost";
        // Podpis DKIM robi aplikacja (klucz poza repo) - lokalny Postfix na VPS tylko dostarcza.
        let dkim;
        if (DKIM_DOMAIN && DKIM_SELECTOR && DKIM_KEY_FILE) {
            try {
                dkim = { domainName: DKIM_DOMAIN, keySelector: DKIM_SELECTOR, privateKey: readFileSync(DKIM_KEY_FILE, "utf8") };
            } catch (e) {
                console.error(`DKIM key not readable (${DKIM_KEY_FILE}): ${e.message} - sending without DKIM.`);
            }
        }
        transport = nodemailer.createTransport({
            host: SMTP_HOST,
            port,
            secure: !local && port === 465, // 465 = SSL od razu, 587 = STARTTLS; lokalny Postfix bez szyfrowania (tylko 127.0.0.1)
            requireTLS: !local && port !== 465,
            ignoreTLS: local,
            auth: SMTP_USER && SMTP_PASS ? { user: SMTP_USER, pass: SMTP_PASS } : undefined,
            dkim,
        });
    }
    return transport;
}

/** extra.unsubscribe: link wypisania - trafia do nagłówków List-Unsubscribe (przycisk "Wypisz" w Gmailu). */
export async function sendEmail(toEmail, subject, text, extra = {}) {
    const fromEmail = process.env.FROM_EMAIL;
    const viaSmtp = smtp();
    if (!fromEmail || (!viaSmtp && !process.env.RESEND_API_KEY)) {
        console.warn(`Email not configured (SMTP_* or RESEND_API_KEY, and FROM_EMAIL) - "${subject}" to ${mask(toEmail)} was NOT sent.`);
        return false;
    }
    try {
        if (viaSmtp) {
            // Koperta z adresu domeny - zgodność SPF/DMARC (DMARC domeny jest ustawiony ściśle).
            const envelopeFrom = (fromEmail.match(/<([^>]+)>/) ?? [null, fromEmail])[1];
            await viaSmtp.sendMail({
                from: fromEmail,
                to: toEmail,
                subject,
                text,
                envelope: { from: envelopeFrom, to: toEmail },
                // noreply@ nie ma skrzynki - odpowiedzi idą na adres kontaktowy z polityki prywatności
                replyTo: process.env.REPLY_TO || undefined,
                list: extra.unsubscribe ? { unsubscribe: { url: extra.unsubscribe } } : undefined,
                headers: extra.unsubscribe ? { "List-Unsubscribe-Post": "List-Unsubscribe=One-Click" } : undefined,
            });
            return true;
        }
        const resp = await fetch("https://api.resend.com/emails", {
            method: "POST",
            headers: { Authorization: `Bearer ${process.env.RESEND_API_KEY}`, "Content-Type": "application/json" },
            body: JSON.stringify({ from: fromEmail, to: toEmail, subject, text }),
        });
        if (!resp.ok) {
            console.error(`Resend returned HTTP ${resp.status} when sending to ${mask(toEmail)}: ${await resp.text()}`);
            return false;
        }
        return true;
    } catch (e) {
        console.error(`Couldn't send an email to ${mask(toEmail)}: ${e.message}`);
        return false;
    }
}
