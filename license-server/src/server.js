import { PLANS, LIMITS, FEATURES, entitlements, planOfLicenses } from "./plans.js";
import "dotenv/config";
import express from "express";
import { bindServer, createLicense, findByCustomer, findByKey, licenseGrants, listLicenses, recordValidation, revokeLicense } from "./db.js";
import { generateLicenseKey } from "./keys.js";
import { handleLemonSqueezyWebhook, verifyLemonSqueezySignature } from "./lemonsqueezy.js";
import {
    authenticateCustomer,
    changePassword,
    createPasswordResetToken,
    findCustomerById,
    isStaff,
    registerCustomer,
    toPublic,
    resetPasswordWithToken,
    totpEnabled,
    setPendingTotp,
    checkTotp,
    noteLoginIp,
    listAccounts,
} from "./customers.js";
import { createSession, deleteSession, deleteSessionsForCustomer, markSessionMfa, resolveSessionInfo } from "./sessions.js";
import { generateSecret, otpauthUrl, verifyTotp } from "./totp.js";
import QRCode from "qrcode";
import { recordEvent, recordHit, visitorStats } from "./analytics.js";
import { noteVisit, notifyAccount, notifySignup, notifySurvey } from "./siteNotify.js";
import { CATEGORIES, INDIVIDUAL_PLUGINS, PACKAGES } from "./catalog.js";
import { sendEmail } from "./mailer.js";
import { allowDownload, findJar, listJars, logDownload } from "./pluginJars.js";
import { issueToken, loadSigningKey, signingPublicKey } from "./signing.js";
import { hit, limitByIp } from "./rateLimit.js";
import { notifyCustomerOfReply, notifySupport } from "./notify.js";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash, timingSafeEqual } from "node:crypto";
import {
    addAdminReply as addTicketReply,
    addCustomerReply,
    CATEGORIES as TICKET_CATEGORIES,
    forCustomer as forCustomerTicket,
    createTicket,
    listAll as listAllTickets,
    listByCustomer as listTicketsByCustomer,
    PRIORITIES as TICKET_PRIORITIES,
    setStatus as setTicketStatus,
} from "./tickets.js";
import {
    EMAIL_RE,
    addIdea,
    addSurvey,
    surveyResponses,
    addToWaitlist,
    allIdeasForStaff,
    confirmWaitlist,
    listIdeas,
    moderateIdea,
    toggleVote,
    voterId,
    waitlistAll,
    waitlistConfirmed,
    waitlistRemove,
    unsubscribeSig,
    checkUnsubscribeSig,
    waitlistStats,
} from "./community.js";

const app = express();

// Za Caddy (reverse proxy na tym samym VPS) - req.ip ma być adresem klienta, nie 127.0.0.1,
// inaczej limity prób liczyłyby wszystkich razem.
app.set("trust proxy", "loopback");
app.disable("x-powered-by");
app.use((_req, res, next) => {
    res.set({
        "X-Content-Type-Options": "nosniff",
        "X-Frame-Options": "DENY",
        "Referrer-Policy": "no-referrer",
    });
    next();
});

// Bez klucza podpisu pluginy nie przyjmą żadnej licencji - lepiej nie wystartować wcale
// (widać od razu przy wdrożeniu) niż po cichu wyłączyć pluginy wszystkim klientom.
if (!loadSigningKey()) {
    console.error("LICENSE_SIGNING_KEY is missing from the environment (.env) - generate it with: node deploy/gen-signing-key.mjs");
    process.exit(1);
}
// Klucz publiczny nie jest tajny - do budowania jarów testowych: ./mvnw package -Dlicense.publicKey=<ten klucz>
console.log(`License public key: ${signingPublicKey()}`);

// Musi być zarejestrowany PRZED app.use(express.json()) i z express.raw() (nie .json()) -
// weryfikacja podpisu HMAC potrzebuje dokładnie surowych bajtów body, tak jak je podpisało
// LemonSqueezy. Gdyby to poszło przez express.json() jak reszta endpointów, ciało zostałoby
// sparsowane/zserializowane inaczej i podpis by się nie zgadzał.
app.post("/webhooks/lemonsqueezy", express.raw({ type: "application/json" }), async (req, res) => {
    const signature = req.get("x-signature");
    const secret = process.env.LEMONSQUEEZY_WEBHOOK_SECRET;
    if (!secret) {
        console.error("LEMONSQUEEZY_WEBHOOK_SECRET nieskonfigurowany w .env - webhook odrzucony.");
        return res.status(500).json({ error: "webhook not configured" });
    }
    if (!verifyLemonSqueezySignature(req.body, signature, secret)) {
        return res.status(401).json({ error: "invalid signature" });
    }
    try {
        const result = await handleLemonSqueezyWebhook(req.body);
        res.json(result);
    } catch (e) {
        console.error("LemonSqueezy webhook processing error:", e);
        res.status(400).json({ error: String(e) });
    }
});

app.use(express.json());

const ADMIN_SECRET = process.env.ADMIN_SECRET;
if (!ADMIN_SECRET) {
    console.error("ADMIN_SECRET is missing from the environment (.env) - the server won't start without it, so the admin panel isn't left open.");
    process.exit(1);
}

/** Porównanie w stałym czasie (po skrócie) - długość/treść sekretu nie wycieka przez czas odpowiedzi. */
function safeEqual(a, b) {
    const ha = createHash("sha256").update(String(a ?? "")).digest();
    const hb = createHash("sha256").update(String(b ?? "")).digest();
    return timingSafeEqual(ha, hb);
}

function requireAdmin(req, res, next) {
    if (!hit(`admin:${req.ip}`, 60, 15 * 60 * 1000)) {
        return res.status(429).json({ error: "too many requests" });
    }
    if (!safeEqual(req.get("x-admin-key"), ADMIN_SECRET)) {
        return res.status(401).json({ error: "unauthorized" });
    }
    next();
}

/** Autoryzacja klienta (nie admina) - token z Authorization: Bearer <token>, wydany przez /api/auth/login. */
function requireCustomer(req, res, next) {
    const header = req.get("authorization") ?? "";
    const token = header.startsWith("Bearer ") ? header.slice("Bearer ".length) : null;
    const session = resolveSessionInfo(token);
    if (!session) {
        return res.status(401).json({ error: "unauthorized" });
    }
    req.customerId = session.customerId;
    req.sessionMfa = session.mfa;
    req.sessionToken = token;
    next();
}

// --- Publiczny endpoint: wywoływany przez pluginy (LicenseManager w mainplugins-core) ---
// Ważna licencja dostaje podpisany token (patrz src/signing.js) - pluginy przyjmują tylko
// podpisane odpowiedzi, więc fałszywy serwer licencji / podmieniony Core nic nie da.
// Pola valid/reason zostają dla starszych wersji Core.
const isShortString = (v, max) => typeof v === "string" && v.length > 0 && v.length <= max;

app.post("/api/validate", limitByIp("validate", 600, 60 * 60 * 1000), (req, res) => {
    const { key, plugin, serverId, nonce } = req.body ?? {};
    if (!isShortString(key, 64) || !isShortString(plugin, 32) || !isShortString(serverId, 64) || (nonce != null && !isShortString(nonce, 64))) {
        return res.status(400).json({ valid: false, reason: "missing key/plugin/serverId" });
    }
    const signed = () => {
        recordValidation(key, req.ip);
        return issueToken({ plugin, serverId, nonce });
    };

    const license = findByKey(key);
    if (!license) {
        return res.json({ valid: false, reason: "unknown key" });
    }
    if (license.status === "revoked") {
        return res.json({ valid: false, reason: "revoked" });
    }
    if (!licenseGrants(license, plugin)) {
        return res.json({ valid: false, reason: "key not valid for this plugin" });
    }

    if (!license.serverId) {
        // Pierwsze użycie klucza - przypisujemy go na stałe do tego serwera
        // (model "jeden klucz = jeden serwer").
        bindServer(key, serverId);
        return res.json({ valid: true, reason: "bound to this server", ...signed() });
    }
    if (license.serverId !== serverId) {
        return res.json({ valid: false, reason: "key already bound to a different server" });
    }
    return res.json({ valid: true, reason: "ok", ...signed() });
});

// --- Panel admina (chroniony x-admin-key) - na razie bez UI, tylko API do curl/skryptu ---
app.post("/api/admin/licenses", requireAdmin, (req, res) => {
    const { plugin, note } = req.body ?? {};
    if (!plugin) {
        return res.status(400).json({ error: "plugin is required (plugin name, e.g. 'tools', or '*' for the all-in-one bundle)" });
    }
    const license = createLicense({ key: generateLicenseKey(), plugin, note });
    res.status(201).json(license);
});

app.get("/api/admin/licenses", requireAdmin, (_req, res) => {
    res.json(listLicenses());
});

app.post("/api/admin/licenses/:key/revoke", requireAdmin, (req, res) => {
    const license = revokeLicense(req.params.key);
    if (!license) return res.status(404).json({ error: "not found" });
    res.json(license);
});

// --- Konta klientów (sklep w PluginManagerze) ---

app.post("/api/auth/register", limitByIp("register", 10, 60 * 60 * 1000), (req, res) => {
    const { email, password } = req.body ?? {};
    if (!email || !password || password.length < 8) {
        return res.status(400).json({ error: "Email and password (at least 8 characters) are required." });
    }
    const customer = registerCustomer(email, password);
    if (!customer) {
        return res.status(409).json({ error: "An account with this email already exists." });
    }
    const token = createSession(customer.id);
    // statystyki: tylko licznik nowych kont na dzień (+ zbiorcze powiadomienie na Discordzie)
    recordEvent("account");
    notifyAccount();
    res.status(201).json({ token, customer });
});

app.post("/api/auth/login", limitByIp("login", 30, 15 * 60 * 1000), (req, res) => {
    const { email, password } = req.body ?? {};
    // Dodatkowo limit na konto - zgadywanie hasła jednego konta z wielu adresów IP.
    if (!hit(`login:email:${String(email ?? "").toLowerCase()}`, 10, 15 * 60 * 1000)) {
        return res.status(429).json({ error: "Too many login attempts for this account - wait 15 minutes." });
    }
    const customer = authenticateCustomer(email ?? "", password ?? "");
    if (!customer) {
        return res.status(401).json({ error: "Wrong email or password." });
    }
    alertNewLogin(customer, req, "the RSMC Manager app");
    const token = createSession(customer.id);
    res.json({ token, customer });
});

app.post("/api/auth/logout", requireCustomer, (req, res) => {
    const header = req.get("authorization") ?? "";
    deleteSession(header.slice("Bearer ".length));
    res.json({ ok: true });
});

app.get("/api/me", requireCustomer, (req, res) => {
    const customer = findCustomerById(req.customerId);
    if (!customer) return res.status(404).json({ error: "not found" });
    res.json(toPublic(customer));
});

app.get("/api/me/licenses", requireCustomer, (req, res) => {
    res.json(findByCustomer(req.customerId));
});

// Plan konta (Free/Plus/Pro/Network) z jego licencji - funkcje i limity, patrz plans.js.
app.get("/api/me/plan", requireCustomer, (req, res) => {
    res.json(entitlements(planOfLicenses(findByCustomer(req.customerId))));
});

// --- Płatne jary pluginów (patrz src/pluginJars.js) - tylko dla konta z aktywną licencją ---

/** Czy konto ma aktywną licencję obejmującą plugin. */
function customerOwns(customerId, pluginId) {
    return findByCustomer(customerId).some((l) => l.status === "active" && licenseGrants(l, pluginId));
}

// Lista jarów, które to konto może pobrać (rozmiar + sha1 - appka porównuje z tym, co jest
// na serwerze Minecrafta, i sprawdza pobrany plik).
app.get("/api/me/plugins", requireCustomer, (req, res) => {
    res.json(listJars().filter((j) => customerOwns(req.customerId, j.id)).map(({ id, filename, size, sha1 }) => ({ id, filename, size, sha1 })));
});

app.get("/api/me/plugins/:id/download", requireCustomer, (req, res) => {
    const jar = findJar(req.params.id);
    if (!jar) return res.status(404).json({ error: "This plugin isn't available for download." });
    if (!customerOwns(req.customerId, jar.id)) {
        return res.status(403).json({ error: "You don't have an active license for this plugin." });
    }
    if (!allowDownload(req.customerId)) {
        return res.status(429).json({ error: "Too many downloads in a short time - try again in an hour." });
    }
    logDownload({ customerId: req.customerId, plugin: jar.id, filename: jar.filename, ip: req.ip });
    res.set({
        "Content-Type": "application/java-archive",
        "Cache-Control": "no-store",
        "X-Jar-Filename": jar.filename,
        "X-Jar-Sha1": jar.sha1,
    });
    res.sendFile(jar.path);
});

// Testowe "kup" bez prawdziwej płatności - WYŁĄCZONE domyślnie (musisz jawnie ustawić
// DEV_LICENSE_GRANTS=1 w .env), żeby nie dało się tego przypadkiem zostawić włączonego
// na produkcji. Appka pokazuje przycisk, który tu trafia, tylko w trybie deweloperskim
// (import.meta.env.DEV, patrz ShopPage.tsx) - to druga, niezależna warstwa zabezpieczenia.
app.post("/api/me/dev-grant", requireCustomer, (req, res) => {
    if (process.env.DEV_LICENSE_GRANTS !== "1") {
        return res.status(404).json({ error: "not found" });
    }
    const { plugin } = req.body ?? {};
    if (!plugin) {
        return res.status(400).json({ error: "plugin is required" });
    }
    const license = createLicense({ key: generateLicenseKey(), plugin, note: "TEST (dev-grant)", customerId: req.customerId });
    res.status(201).json(license);
});

// Zawsze { ok: true } niezależnie od tego, czy e-mail istnieje w systemie - inaczej
// formularz zdradzałby, które adresy mają konto (enumeracja kont). Kod trafia mailem
// tylko wtedy, gdy konto istnieje.
app.post("/api/auth/forgot-password", limitByIp("forgot", 5, 60 * 60 * 1000), async (req, res) => {
    const { email } = req.body ?? {};
    if (!email) {
        return res.status(400).json({ error: "Email is required." });
    }
    const token = createPasswordResetToken(email);
    if (token) {
        await sendEmail(
            email,
            "Password reset - RSMC Manager",
            `Your password reset code: ${token}\n\nPaste it in the app on the "Forgot password" screen together with your new password. The code is valid for 30 minutes.\n\nIf you didn't request a reset, ignore this message - your password won't change.`
        );
    }
    res.json({ ok: true });
});

app.post("/api/auth/reset-password", limitByIp("reset", 10, 60 * 60 * 1000), (req, res) => {
    const { token, newPassword } = req.body ?? {};
    if (!token || !newPassword || newPassword.length < 8) {
        return res.status(400).json({ error: "The code and a new password (at least 8 characters) are required." });
    }
    const customerId = resetPasswordWithToken(token, newPassword);
    if (!customerId) {
        return res.status(400).json({ error: "The code is invalid or has expired." });
    }
    // Reset hasła = ktoś mógł przejąć konto: wylogowujemy wszystkie urządzenia.
    deleteSessionsForCustomer(customerId);
    res.json({ ok: true });
});

app.patch("/api/me/password", requireCustomer, limitByIp("password", 10, 15 * 60 * 1000), (req, res) => {
    const { currentPassword, newPassword } = req.body ?? {};
    if (!currentPassword || !newPassword || newPassword.length < 8) {
        return res.status(400).json({ error: "The current password and a new password (at least 8 characters) are required." });
    }
    const ok = changePassword(req.customerId, currentPassword, newPassword);
    if (!ok) {
        return res.status(401).json({ error: "The current password is incorrect." });
    }
    // Zmiana hasła wylogowuje pozostałe urządzenia (skradziony token przestaje działać).
    const header = req.get("authorization") ?? "";
    deleteSessionsForCustomer(req.customerId, header.slice("Bearer ".length));
    res.json({ ok: true });
});

// --- Zgłoszenia (Wsparcie w appce) ---

app.get("/api/tickets/meta", (_req, res) => {
    res.json({ categories: TICKET_CATEGORIES, priorities: TICKET_PRIORITIES });
});

const MAX_SUBJECT = 200;
const MAX_MESSAGE = 10000;
const validText = (v, max) => typeof v === "string" && v.trim().length > 0 && v.length <= max;

app.post("/api/tickets", requireCustomer, limitByIp("ticket", 20, 60 * 60 * 1000), async (req, res) => {
    const { subject, category, priority, serverProfile, message } = req.body ?? {};
    if (!validText(subject, MAX_SUBJECT) || !validText(message, MAX_MESSAGE)) {
        return res.status(400).json({ error: `Subject (max ${MAX_SUBJECT} characters) and message (max ${MAX_MESSAGE}) are required.` });
    }
    if (!TICKET_CATEGORIES.includes(category) || !TICKET_PRIORITIES.includes(priority)) {
        return res.status(400).json({ error: "Unknown category or priority." });
    }
    const profile = typeof serverProfile === "string" ? serverProfile.slice(0, 100) : null;
    const ticket = createTicket({ customerId: req.customerId, subject: subject.trim(), category, priority, serverProfile: profile, message: message.trim() });
    res.status(201).json(forCustomerTicket(ticket));
    const customer = findCustomerById(req.customerId);
    void notifySupport({ title: "New support ticket", ticket, customerEmail: customer?.email ?? "?", body: ticket.messages[0].body });
});

app.get("/api/tickets", requireCustomer, (req, res) => {
    res.json(listTicketsByCustomer(req.customerId).map(forCustomerTicket));
});

// Klient odpisuje we własnym zgłoszeniu (zamknięte wraca do kolejki jako "open").
app.post("/api/tickets/:id/reply", requireCustomer, limitByIp("ticket-reply", 60, 60 * 60 * 1000), async (req, res) => {
    const { message } = req.body ?? {};
    if (!validText(message, MAX_MESSAGE)) return res.status(400).json({ error: `Message is required (max ${MAX_MESSAGE} characters).` });
    const ticket = addCustomerReply(req.params.id, req.customerId, message.trim());
    if (!ticket) return res.status(404).json({ error: "not found" });
    res.json(forCustomerTicket(ticket));
    const customer = findCustomerById(req.customerId);
    void notifySupport({ title: "Customer replied", ticket, customerEmail: customer?.email ?? "?", body: message.trim() });
});

// --- Panel wsparcia (/admin) - konta z rolą admin/support (src/set-role.mjs) ---

/** Konto zespołu, ale bez wymogu 2FA - tylko /api/staff/me i włączanie weryfikacji. */
function requireStaffLogin(req, res, next) {
    requireCustomer(req, res, () => {
        if (!isStaff(req.customerId)) return res.status(403).json({ error: "This account isn't part of the support team." });
        next();
    });
}

/** Panel zespołu: sesja musi być potwierdzona kodem 2FA (logowanie przez /api/staff/login). */
function requireStaff(req, res, next) {
    requireStaffLogin(req, res, () => {
        if (!req.sessionMfa) return res.status(403).json({ error: "Sign in to the team panel with your 2FA code.", mfaRequired: true });
        next();
    });
}

// --- Logowanie do panelu zespołu: hasło + kod z aplikacji (Google Authenticator itp.) ---

const ipHash = (req) => createHash("sha256").update(`${ADMIN_SECRET}|login-ip|${req.ip}`).digest("hex").slice(0, 32);

/**
 * Mail do właściciela konta zespołu przy logowaniu z nowego IP (albo przy złym kodzie 2FA po dobrym haśle).
 * Nie czeka na wysyłkę - logowanie nie może się przez to wydłużyć ani wywrócić.
 */
function alertNewLogin(customer, req, where, { wrongCode = false, remember = true } = {}) {
    if (!isStaff(customer.id)) return;
    const h = ipHash(req);
    const known = (findCustomerById(customer.id)?.loginIps ?? []).includes(h);
    if (remember && !wrongCode) noteLoginIp(customer.id, h);
    if ((known && !wrongCode) || !hit(`login-alert:${customer.id}:${h}:${wrongCode}`, 1, 60 * 60 * 1000)) return;
    const when = new Date().toISOString().replace("T", " ").slice(0, 16) + " UTC";
    const what = wrongCode
        ? "Someone entered the correct password for your RSMC team account, but a wrong 2FA code, so they were NOT let in."
        : `Your RSMC team account was just signed in to (${where}) from an IP address we haven't seen before.`;
    sendEmail(
        customer.email,
        wrongCode ? "Wrong 2FA code on your RSMC team account" : "New sign-in to your RSMC team account",
        `Hi,

${what}

When: ${when}
IP address: ${req.ip}
Browser: ${String(req.get("user-agent") ?? "unknown").slice(0, 160)}

If this was you, you can ignore this email.
If it wasn't, change your password right away in the RSMC Manager app (Profile), because someone knows it.

- RSMC security`,
    ).catch(() => {});
}

app.post("/api/staff/login", limitByIp("staff-login", 30, 15 * 60 * 1000), (req, res) => {
    const { email, password, code } = req.body ?? {};
    if (!hit(`login:email:${String(email ?? "").toLowerCase()}`, 10, 15 * 60 * 1000)) {
        return res.status(429).json({ error: "Too many login attempts for this account - wait 15 minutes." });
    }
    const customer = authenticateCustomer(email ?? "", password ?? "");
    // Ten sam komunikat dla konta spoza zespołu - panel nie zdradza, kto ma do niego dostęp.
    if (!customer || !isStaff(customer.id)) return res.status(401).json({ error: "Wrong email or password." });
    const has2fa = totpEnabled(customer.id);
    if (has2fa) {
        if (!code) return res.status(401).json({ error: "Enter the 6-digit code from your authenticator app.", totpRequired: true });
        if (!hit(`totp:${customer.id}`, 10, 15 * 60 * 1000)) {
            return res.status(429).json({ error: "Too many wrong codes - wait 15 minutes.", totpRequired: true });
        }
        if (!checkTotp(customer.id, code, verifyTotp)) {
            alertNewLogin(customer, req, "team panel", { wrongCode: true });
            return res.status(401).json({ error: "Wrong or expired code - try the current one.", totpRequired: true });
        }
    }
    alertNewLogin(customer, req, "team panel");
    // Bez włączonego 2FA sesja wpuszcza tylko do ekranu włączania weryfikacji.
    res.json({ token: createSession(customer.id, has2fa), customer, totp: has2fa });
});

// Włączanie 2FA: sekret + kod QR, potem potwierdzenie pierwszym kodem. Tylko gdy 2FA jeszcze nie ma -
// po włączeniu nikt z samym hasłem go nie podmieni (reset wyłącznie skryptem src/reset-2fa.mjs).
app.post("/api/staff/2fa/setup", requireStaffLogin, async (req, res) => {
    if (totpEnabled(req.customerId)) return res.status(409).json({ error: "2FA is already on for this account." });
    const secret = generateSecret();
    if (!setPendingTotp(req.customerId, secret)) return res.status(409).json({ error: "2FA is already on for this account." });
    const email = findCustomerById(req.customerId).email;
    const qr = await QRCode.toDataURL(otpauthUrl(secret, email), { margin: 1, width: 220 });
    res.set("Cache-Control", "no-store").json({ secret, qr });
});

app.post("/api/staff/2fa/enable", requireStaffLogin, (req, res) => {
    if (!hit(`totp:${req.customerId}`, 10, 15 * 60 * 1000)) return res.status(429).json({ error: "Too many wrong codes - wait 15 minutes." });
    if (!checkTotp(req.customerId, req.body?.code, verifyTotp, true)) {
        return res.status(400).json({ error: "That code doesn't match - check the time on your phone and try the current code." });
    }
    markSessionMfa(req.sessionToken);
    res.json({ ok: true });
});

/** Zgłoszenie + dane klienta potrzebne przy odpowiadaniu (e-mail, licencje). */
function withCustomer(ticket) {
    const customer = findCustomerById(ticket.customerId);
    const licenses = customer ? findByCustomer(customer.id).map((l) => ({ plugin: l.plugin, status: l.status, billingType: l.billingType, boundAt: l.boundAt })) : [];
    return { ...ticket, customer: customer ? { email: customer.email, createdAt: customer.createdAt, licenses } : null };
}

app.get("/api/staff/me", requireStaffLogin, (req, res) => {
    res.json({ ...toPublic(findCustomerById(req.customerId)), totp: totpEnabled(req.customerId), mfa: req.sessionMfa });
});

app.get("/api/staff/tickets", requireStaff, (_req, res) => {
    res.json(listAllTickets().map(withCustomer));
});

app.post("/api/staff/tickets/:id/reply", requireStaff, async (req, res) => {
    const { message, close } = req.body ?? {};
    if (!validText(message, MAX_MESSAGE)) return res.status(400).json({ error: `Message is required (max ${MAX_MESSAGE} characters).` });
    const staff = findCustomerById(req.customerId);
    let ticket = addTicketReply(req.params.id, message.trim(), staff?.email ?? null);
    if (!ticket) return res.status(404).json({ error: "not found" });
    if (close === true) ticket = setTicketStatus(ticket.id, "closed");
    res.json(withCustomer(ticket));
    const customer = findCustomerById(ticket.customerId);
    if (customer) void notifyCustomerOfReply(customer.email, ticket);
});

app.post("/api/staff/tickets/:id/status", requireStaff, (req, res) => {
    const { status } = req.body ?? {};
    if (!["open", "answered", "closed"].includes(status)) return res.status(400).json({ error: "status must be open, answered or closed" });
    const ticket = setTicketStatus(req.params.id, status);
    if (!ticket) return res.status(404).json({ error: "not found" });
    res.json(withCustomer(ticket));
});

// Stare endpointy dla skryptów/curl (x-admin-key) zostają.
app.get("/api/admin/tickets", requireAdmin, (_req, res) => {
    res.json(listAllTickets());
});

app.post("/api/admin/tickets/:id/reply", requireAdmin, (req, res) => {
    const { message } = req.body ?? {};
    if (!validText(message, MAX_MESSAGE)) return res.status(400).json({ error: "message is required" });
    const ticket = addTicketReply(req.params.id, message.trim(), "admin-key");
    if (!ticket) return res.status(404).json({ error: "not found" });
    res.json(ticket);
});

app.post("/api/admin/tickets/:id/close", requireAdmin, (req, res) => {
    const ticket = setTicketStatus(req.params.id, "closed");
    if (!ticket) return res.status(404).json({ error: "not found" });
    res.json(ticket);
});

// Strona panelu wsparcia - statyczne pliki z admin/, ze ścisłą polityką CSP (tylko własne
// skrypty, bez ramek). Dane zgłoszeń panel wstawia wyłącznie jako tekst (textContent).
const ADMIN_DIR = join(dirname(fileURLToPath(import.meta.url)), "..", "admin");
app.use(
    "/admin",
    express.static(ADMIN_DIR, {
        index: "index.html",
        setHeaders: (res) => {
            res.set({
                "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
                "Cache-Control": "no-store",
            });
        },
    })
);

// Publiczny - katalog tego, co można kupić (patrz src/catalog.js). storeUrl + variantId
// wystarczą appce do zbudowania linku checkout LemonSqueezy bez dodatkowego zapytania.
app.get("/api/catalog", (_req, res) => {
    res.json({
        storeUrl: process.env.LEMONSQUEEZY_STORE_URL ?? null,
        categories: CATEGORIES,
        individualPlugins: INDIVIDUAL_PLUGINS,
        packages: PACKAGES,
        plans: PLANS,
    });
});

// Publiczny - plany, funkcje (funkcja -> najniższy plan) i limity, dla appki i strony.
app.get("/api/plans", (_req, res) => {
    res.json({ plans: PLANS, features: FEATURES, limits: LIMITS });
});

app.get("/health", (_req, res) => res.json({ ok: true }));

const port = process.env.PORT || 3000;
// ---- Społeczność ze strony (zapisy na wczesny dostęp, tablica pomysłów) ----

const SITE_URL = process.env.SITE_URL || "https://rsmc-network.pl";
const PUBLIC_API_URL = process.env.PUBLIC_API_URL || "https://api.rsmc-network.pl";
const SITE_ORIGINS = new Set([SITE_URL, SITE_URL.replace("://", "://www."), "http://localhost:5500", "http://127.0.0.1:5500"]);

// Strona jest na innej domenie niż API - CORS tylko dla niej i tylko dla /api/community.
app.use("/api/community", (req, res, next) => {
    const origin = req.get("origin");
    if (origin && SITE_ORIGINS.has(origin)) {
        res.set("Access-Control-Allow-Origin", origin);
        res.set("Vary", "Origin");
        res.set("Access-Control-Allow-Methods", "GET, POST");
        res.set("Access-Control-Allow-Headers", "Content-Type");
    }
    if (req.method === "OPTIONS") return res.sendStatus(204);
    next();
});

app.post("/api/community/waitlist", limitByIp("waitlist", 5, 60 * 60 * 1000), async (req, res) => {
    const { email, consent, website, source } = req.body ?? {};
    if (website) return res.json({ ok: true }); // pułapka na boty (ukryte pole)
    if (typeof email !== "string" || !EMAIL_RE.test(email.trim())) return res.status(400).json({ error: "Enter a valid email address." });
    if (consent !== true) return res.status(400).json({ error: "Please agree to receive the early access emails." });
    const token = addToWaitlist(email, source);
    if (token) {
        const st = waitlistStats();
        notifySignup({ confirmed: false, total: st.total, source: typeof source === "string" ? source.slice(0, 40) : "" });
    }
    let mailSent = true;
    if (token) {
        const link = `${PUBLIC_API_URL}/api/community/confirm?token=${encodeURIComponent(token)}`;
        mailSent = await sendEmail(
            email.trim(),
            "Confirm your RSMC Manager sign-up",
            `Hi!

Thanks for signing up for RSMC Manager. Confirm your email with this link (valid for 7 days):

${link}

We'll write when your invite is ready. If you didn't sign up, just ignore this email.

- The RSMC team

Don't want our emails? Unsubscribe: ${unsubLink(email)}`,
            { unsubscribe: unsubLink(email) },
        );
    }
    // mailSent=false: wysyłka nie jest jeszcze skonfigurowana - zapis jest, potwierdzenie przyjdzie później.
    res.json({ ok: true, mailSent });
});

// Wypisanie z listy zapisów. GET pokazuje przycisk (skanery linków w poczcie nie wypiszą nikogo same),
// POST wypisuje - także "jednym kliknięciem" z Gmaila (List-Unsubscribe-Post). Odpowiedź zawsze ta sama.
const unsubLink = (email) =>
    `${PUBLIC_API_URL}/api/community/unsubscribe?e=${encodeURIComponent(email.trim().toLowerCase())}&s=${unsubscribeSig(email, ADMIN_SECRET)}`;
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
function unsubPage(res, status, title, body) {
    res.status(status)
        .set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        .type("html")
        .send(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex"><title>${title}</title>
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0e0d13;color:#ece9f5;font:16px/1.5 system-ui,sans-serif}main{max-width:420px;padding:32px;text-align:center}button{margin-top:12px;padding:10px 20px;border:0;border-radius:8px;background:#a855f7;color:#1a0a29;font:inherit;font-weight:700;cursor:pointer}a{color:#c084fc}</style>
</head><body><main><h1>${title}</h1>${body}</main></body></html>`);
}
app.get("/api/community/unsubscribe", (req, res) => {
    const e = String(req.query.e ?? ""), s = String(req.query.s ?? "");
    if (!checkUnsubscribeSig(e, s, ADMIN_SECRET)) return unsubPage(res, 400, "Link not valid", `<p>This unsubscribe link is broken. Write to us and we'll remove your address.</p>`);
    unsubPage(res, 200, "Unsubscribe", `<p>Stop emails from RSMC Network to <b>${esc(e)}</b> and delete it from our sign-up list?</p><form method="post"><button type="submit">Unsubscribe</button></form>`);
});
app.post("/api/community/unsubscribe", limitByIp("unsub", 30, 60 * 60 * 1000), (req, res) => {
    const e = String(req.query.e ?? ""), s = String(req.query.s ?? "");
    if (!checkUnsubscribeSig(e, s, ADMIN_SECRET)) return unsubPage(res, 400, "Link not valid", `<p>This unsubscribe link is broken. Write to us and we'll remove your address.</p>`);
    waitlistRemove(e);
    unsubPage(res, 200, "You're unsubscribed", `<p>We deleted your address from our list. You won't get any more emails from us.</p><p><a href="${esc(SITE_URL)}">Back to the website</a></p>`);
});

// Anonimowe statystyki odwiedzin (src/analytics.js). text/plain = zwykłe zapytanie, bez preflightu CORS.
app.post("/api/community/hit", limitByIp("hit", 300, 60 * 60 * 1000), express.text({ limit: "2kb" }), (req, res) => {
    let body = null;
    try {
        body = JSON.parse(req.body);
    } catch {
        /* śmieci - pomijamy */
    }
    const counted = recordHit(body, req.ip, req.get("user-agent") ?? "");
    if (counted) noteVisit(counted);
    res.sendStatus(204);
});

app.get("/api/community/confirm", (req, res) => {
    const ok = confirmWaitlist(String(req.query.token ?? ""));
    if (ok) notifySignup({ confirmed: true, confirmedTotal: waitlistStats().confirmed });
    res.redirect(302, `${SITE_URL}/?joined=${ok ? "1" : "0"}#join`);
});

app.get("/api/community/ideas", limitByIp("ideas-list", 120, 60 * 1000), (req, res) => {
    res.json({ ideas: listIdeas(voterId(req, ADMIN_SECRET)), waitlist: waitlistStats().confirmed });
});

app.post("/api/community/ideas", limitByIp("ideas-add", 4, 60 * 60 * 1000), (req, res) => {
    const { type, title, body, author, website } = req.body ?? {};
    if (website) return res.json({ ok: true });
    if (typeof title !== "string" || title.trim().length < 4) return res.status(400).json({ error: "Give it a short title (at least 4 characters)." });
    if (typeof body === "string" && body.length > 2000) return res.status(400).json({ error: "The description is too long (2000 characters max)." });
    const idea = addIdea({ type, title, body, author });
    res.json({ ok: true, id: idea.id });
});

app.post("/api/community/ideas/:id/vote", limitByIp("ideas-vote", 60, 60 * 60 * 1000), (req, res) => {
    const r = toggleVote(String(req.params.id), voterId(req, ADMIN_SECRET));
    if (!r) return res.status(404).json({ error: "Not found." });
    res.json(r);
});

app.post("/api/community/survey", limitByIp("survey", 5, 60 * 60 * 1000), (req, res) => {
    const b = req.body ?? {};
    if (b.website) return res.json({ ok: true });
    const saved = addSurvey(b.answers);
    if (!saved) return res.status(400).json({ error: "Answer at least one question." });
    notifySurvey(saved, surveyResponses().length);
    res.json({ ok: true });
});

// Admin (rola admin, nie support - to dane osobowe): zapisy i ankiety do panelu /admin/community.html.
function requireAdminRole(req, res, next) {
    requireStaff(req, res, () => {
        if (findCustomerById(req.customerId)?.role !== "admin") return res.status(403).json({ error: "Only admins can see sign-ups and survey answers." });
        next();
    });
}

app.get("/api/admin/community", requireAdminRole, (_req, res) => {
    res.json({ signups: waitlistAll(), accounts: listAccounts(), survey: surveyResponses(), visitors: visitorStats(90) });
});

app.post("/api/admin/community/signups/remove", requireAdminRole, (req, res) => {
    const ok = waitlistRemove(req.body?.email ?? "");
    if (!ok) return res.status(404).json({ error: "Not on the list." });
    res.json({ ok: true });
});

// Zespół (role jak przy zgłoszeniach wsparcia): lista zapisów, moderacja pomysłów.
app.get("/api/staff/community", requireStaff, (_req, res) => {
    res.json({ waitlist: waitlistConfirmed(), stats: waitlistStats(), ideas: allIdeasForStaff(), survey: surveyResponses() });
});

app.post("/api/staff/ideas/:id", requireStaff, (req, res) => {
    const { status, reply } = req.body ?? {};
    const idea = moderateIdea(String(req.params.id), { status, reply });
    if (!idea) return res.status(404).json({ error: "Not found." });
    res.json({ ok: true });
});

// Tylko localhost - z zewnątrz ruch idzie wyłącznie przez Caddy (HTTPS). HOST=0.0.0.0 gdyby było trzeba inaczej.
app.listen(port, process.env.HOST || "127.0.0.1", () => console.log(`Mainplugins license server listening on port ${port}`));
