// Wyłącza weryfikację dwuetapową konta (np. zgubiony telefon). Uruchamiane NA SERWERZE:
//   cd /opt/license-server && sudo -u license node src/reset-2fa.mjs <email>
// Po zalogowaniu panel od razu poprosi o ponowne dodanie konta w aplikacji z kodami.
import { resetTotp } from "./customers.js";

const [email] = process.argv.slice(2);
if (!email) {
    console.error("Usage: node src/reset-2fa.mjs <email>");
    process.exit(1);
}
if (!resetTotp(email)) {
    console.error(`No account with email ${email}.`);
    process.exit(1);
}
console.log(`2FA reset for ${email} - set it up again at the next sign-in.`);
