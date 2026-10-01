import { sendEmail } from "./mailer.js";

// Powiadomienia zespołu wsparcia o nowych zgłoszeniach i odpowiedziach klientów.
// Konfiguracja w .env (oba opcjonalne, można oba naraz):
//   SUPPORT_NOTIFY_EMAIL=ty@domena.pl,kolega@domena.pl   - e-mail (przez Resend, jak reszta maili)
//   SUPPORT_DISCORD_WEBHOOK=https://discord.com/api/webhooks/...   - wiadomość na kanał Discorda
// Treść zgłoszenia idzie w skrócie - pełna rozmowa jest w panelu /admin.

const PANEL_URL = () => `${(process.env.PUBLIC_API_URL || "https://api.rsmc-network.pl").replace(/\/+$/, "")}/admin`;

const shorten = (text, max) => (text.length > max ? `${text.slice(0, max - 1)}…` : text);

export async function notifySupport({ title, ticket, customerEmail, body }) {
    const text = `${title}\n\nSubject: ${ticket.subject}\nFrom: ${customerEmail}\nCategory: ${ticket.category} · Priority: ${ticket.priority}\n\n${shorten(body, 1500)}\n\nReply in the support panel: ${PANEL_URL()}`;

    const emails = (process.env.SUPPORT_NOTIFY_EMAIL || "").split(",").map((e) => e.trim()).filter(Boolean);
    await Promise.all(emails.map((to) => sendEmail(to, `[Support] ${shorten(ticket.subject, 80)}`, text)));

    const webhook = process.env.SUPPORT_DISCORD_WEBHOOK;
    if (webhook) {
        try {
            const resp = await fetch(webhook, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                // Bez pingów (@everyone itp.) - treść pochodzi od klienta.
                body: JSON.stringify({ content: shorten(text, 1900), allowed_mentions: { parse: [] } }),
            });
            if (!resp.ok) console.error(`Discord webhook returned HTTP ${resp.status}`);
        } catch (e) {
            console.error("Couldn't notify Discord:", e);
        }
    }
}

/** Klient dostaje maila, że jest odpowiedź - samą treść czyta w aplikacji (zakładka Support). */
export async function notifyCustomerOfReply(customerEmail, ticket) {
    await sendEmail(
        customerEmail,
        `Reply to your ticket: ${shorten(ticket.subject, 80)}`,
        `We've replied to your support ticket "${ticket.subject}".\n\nOpen RSMC Manager and go to Support to read it and reply.\n\n- RSMC Manager support`
    );
}
