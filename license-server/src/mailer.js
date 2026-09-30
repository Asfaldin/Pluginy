// Wspólna wysyłka maili przez Resend - używana przez licencje (lemonsqueezy.js) i reset
// hasła (server.js). Bez RESEND_API_KEY/FROM_EMAIL w .env po prostu loguje ostrzeżenie
// i nic nie wysyła - dev bez skonfigurowanego mailera dalej działa (patrz .env.example).
export async function sendEmail(toEmail, subject, text) {
    const apiKey = process.env.RESEND_API_KEY;
    const fromEmail = process.env.FROM_EMAIL;
    if (!apiKey || !fromEmail) {
        console.warn(`RESEND_API_KEY/FROM_EMAIL not configured - email "${subject}" to ${toEmail} was NOT sent.`);
        return;
    }
    try {
        const resp = await fetch("https://api.resend.com/emails", {
            method: "POST",
            headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
            body: JSON.stringify({ from: fromEmail, to: toEmail, subject, text }),
        });
        if (!resp.ok) {
            console.error(`Resend returned HTTP ${resp.status} when sending to ${toEmail}: ${await resp.text()}`);
        }
    } catch (e) {
        console.error(`Couldn't send an email to ${toEmail}:`, e);
    }
}
