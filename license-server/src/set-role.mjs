// Nadaje / odbiera rolę zespołu wsparcia. Uruchamiane NA SERWERZE (nie ma do tego API):
//   cd /opt/license-server && sudo -u license node src/set-role.mjs <email> admin
//   ... <email> support      (to samo co admin w panelu wsparcia)
//   ... <email> customer     (odebranie roli)
// Konto musi już istnieć (rejestracja w aplikacji). Po nadaniu roli ta osoba loguje się
// swoim zwykłym e-mailem i hasłem na https://<API_HOST>/admin.
import { setRole } from "./customers.js";

const [email, role] = process.argv.slice(2);
if (!email || !["admin", "support", "customer"].includes(role)) {
    console.error("Usage: node src/set-role.mjs <email> admin|support|customer");
    process.exit(1);
}
const updated = setRole(email, role);
if (!updated) {
    console.error(`No account with email ${email} - register it in the app first.`);
    process.exit(1);
}
console.log(`${updated.email} is now: ${updated.role}`);
