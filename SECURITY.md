# Security policy

CP Companion is an unofficial beta. Security fixes target the latest maintained beta and the current development branch; older builds may not receive backports. There is no guaranteed response time.

## Report privately

Use GitHub's **Security > Advisories > Report a vulnerability** for this repository:
https://github.com/oEscal/my-cp-companion/security/advisories/new

Private vulnerability reporting must be enabled by the repository owner before public launch. The setup check on 7 September 2026 returned HTTP 404 from GitHub while the repository was private, so this channel is not yet verified as available. If the button is missing, do not post the vulnerability publicly: open an issue containing only a request for a private security contact, with no technical details or passenger data. Wait for a private route before sending the report.

Include the affected version, Android version, impact, and a minimal reproduction using synthetic data. Do not send real tickets, names, SMS bodies, QR codes, tokens, or signing material. Coordinate disclosure after the report is acknowledged and a fix is available.

## Scope

Relevant boundaries include exported intents, SMS/notification imports, encryption and storage, notification visibility, background activation, and CP network requests. Do not probe CP accounts, payments, or infrastructure when testing this project. Use local fixtures for malformed responses and rate limits.
