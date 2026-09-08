# Integrity incident email notifications

## Purpose and scope

The processor can alert a configured operations/security contact when it records an `INTEGRITY_INCIDENT`, such as a fabricated Primary row, changed authenticated content or an issued record that is missing when reconciliation checks it. The appropriate term is **suspected integrity incident**: an observation is not proof of fraud, attribution to a person or a verdict that an account owner acted maliciously.

Email is an asynchronous reporting channel. It is not an execution permission, an additional database of authoritative balances or an automatic account-freezing mechanism. The existing verification and protected-execution checks decide whether a pending operation can proceed. An incident detected after settlement does not imply the original authenticated operation was never settled; review the protected outcome and audit history before acting.

## Durable notification flow

```text
Processor detects an integrity discrepancy
  -> Audit/Protected SQL transaction
       append INTEGRITY_INCIDENT to audit_events
       insert first notification for the operation in notification_outbox
  -> dedicated asynchronous email dispatcher
       claim a pending notification with an expiring lease
       send a minimal plain-text email to configured recipients
       record SMTP acceptance, or retain the notification for retry
  -> local Mailpit inbox (demo), or an explicitly configured SMTP service
```

The incident and initial notification are committed together in Audit/Protected, outside the Main DBA's control. Repeated observations of the same operation do not create a new notification each time; the first notification is unique per operation. Later audit events remain available for investigation even when no additional email is created.

The email dispatcher has its own execution path, bounded SMTP timeouts, retries and a send-rate cap. It does not make an SMTP call while checking or settling a transfer. SMTP failure leaves delivery pending and does not make a rejected operation valid or suspend financial processing. Failure to commit protected incident evidence is a separate storage failure; email decoupling does not make Audit/Protected optional.

## What the recipient receives

Messages are deliberately plain text and minimal: an incident reference, operation UUID and observation time, together with instructions to inspect trusted audit evidence. Recipient addresses come only from administrator configuration, never from an untrusted transaction row. The notification does not include account names, balances, transfer amounts, source payloads, HMACs, signing material or secret keys. It does not attach runtime reports or expose private database contents.

An operation UUID is still an internal correlation identifier. Use an approved mailbox with appropriate access and retention, and avoid forwarding operational alerts into public issue trackers. Email recipients can correlate the reference with protected audit history using separately authorized access; receiving an email does not grant application access.

## Local demonstration: Mailpit, not Gmail delivery

Normal local startup uses Mailpit as an SMTP capture service. [Mailpit](https://mailpit.axllent.org/docs/) receives test messages and displays them in a local web inbox; it does not deliver them to the configured public recipient address. Recipients are BCCed to avoid exposing the stakeholder list in message headers. No Gmail account, app password, cloud account or publicly accessible SMTP relay is required.

The local endpoints are:

| Endpoint | Purpose |
|---|---|
| `127.0.0.1:1025` | SMTP capture used by the processor |
| `http://127.0.0.1:8025` | Browser inbox for inspecting captured messages |

These endpoints must remain loopback-only. Mailpit is local test infrastructure, not a third PostgreSQL database or a production notification service. No SMTP forwarding or relaying should be enabled for this demonstration. Existing historical incidents are not automatically replayed into email when the feature is introduced; create a new, fixture-scoped discrepancy to demonstrate a fresh alert.

Direct Java startup defaults to notifications disabled; the project launcher explicitly selects the local capture configuration. Private recipient and SMTP settings belong under ignored `.local` or in the process environment, not in tracked source, presentation files or the documentation ZIP. Changing settings requires restarting the processor; reusing an already running JVM does not change its environment.

## Configuration

The launcher reads optional `.local/notifications.json`, a flat object using the environment-variable names below. Recognized process environment variables override the file. Unknown setting names are rejected to catch configuration mistakes. Without overrides, the launcher selects `mailpit`, uses `record-integrity@example.test` as the sender and `security@example.test` as the recipient. These are demonstration addresses, not external mailboxes.

A local capture configuration can contain:

```json
{
  "NOTIFICATION_MODE": "mailpit",
  "NOTIFICATION_FROM": "record-integrity@example.test",
  "NOTIFICATION_RECIPIENTS": "security@example.test"
}
```

Use the startup command in the [runbook](running.md) after saving local configuration. The Mailpit Compose profile is started only when the launcher selects `mailpit`; `smtp` does not launch a local capture server.

| Setting | Meaning and validation |
|---|---|
| `NOTIFICATION_MODE` | `disabled`, `mailpit` or `smtp`; Java default `disabled`, normal launcher default `mailpit` |
| `NOTIFICATION_FROM` | Sender address; required when delivery is enabled |
| `NOTIFICATION_RECIPIENTS` | Comma-separated configured recipients; required when enabled, at most 20 |
| `NOTIFICATION_SMTP_HOST` | Default `127.0.0.1`; capture mode permits only literal `127.0.0.1` or `::1` |
| `NOTIFICATION_SMTP_PORT` | SMTP port; default `1025` for local capture; set the provider's port explicitly for real SMTP |
| `NOTIFICATION_SMTP_USERNAME` | Required for real SMTP; not used in Mailpit capture mode |
| `NOTIFICATION_SMTP_PASSWORD` | Required for real SMTP; private secret, not used in Mailpit capture mode |
| `NOTIFICATION_SMTP_TLS` | `starttls` or `implicit` for real SMTP; default `starttls`; capture uses the loopback-only unencrypted connection |
| `NOTIFICATION_MAX_PER_MINUTE` | Maximum outbound attempts per minute for one dispatcher; default 30, maximum 60 |

The dispatcher runs separately once per second and respects the processor's existing scheduling-enabled setting. Connection, read and write timeouts are each five seconds. A claim expires after 300 seconds; failed sends retry with exponential delay from one second up to 300 seconds. The rate cap and scheduler are throttles, not email-capacity measurements or a delivery-time guarantee.

Disabling delivery stops dispatch, not incident recording or notification persistence. Existing pending notifications remain in protected storage; enabling delivery later can send that backlog. This is different from automatically creating alerts for audit incidents recorded before notification support was installed.

## Real email delivery is a separate configuration step

To send to an actual mailbox, use an approved SMTP provider with a verified sender and credentials supplied privately. Enable authenticated TLS and certificate/hostname verification, configure finite connection/read/write timeouts, and test only with authorized recipients. Do not use an open relay or paste SMTP passwords into chat, source code or reports.

Select `NOTIFICATION_MODE=smtp`, set the real host/port, sender, recipients and credentials, and choose the provider's required TLS mode. TLS with certificate/hostname verification and SMTP authentication are mandatory in this mode; there is no plaintext or certificate-verification bypass option. Keep real secrets in a private process environment or an appropriately protected local configuration file; production should inject them from an approved secret store.

[Spring's email documentation](https://docs.spring.io/spring-boot/reference/io/email.html) describes the mail-sender abstraction and warns that some default SMTP timeouts are infinite. This implementation configures its sender explicitly; use the project settings rather than assuming arbitrary `spring.mail.*` settings will override it.

An SMTP server accepting a message is not a guarantee that the destination inbox received it, that it avoided spam filtering or that a person read it. Provider reputation, bounce processing, recipient policies and operational escalation are deployment concerns.

## Delivery guarantees and operational limits

Operator-only `GET /internal/notifications/status` reports the mode, pending and SMTP-accepted counts, and the oldest pending timestamp. Use the processor administrator credential through an authorized client; the application credential is denied. The response exposes no recipients, relay settings or credentials. The persisted `delivered_at_micros` field means SMTP acceptance, not confirmed inbox delivery or reading.

- Delivery attempts are at least once, not exactly once. If SMTP accepts a message and the processor stops before recording success, retry can send a duplicate. A stable incident reference lets the recipient recognize that duplicate.
- A lease permits retry after a worker interruption; a send-rate cap limits one dispatcher's outbound rate, not the number of incidents an attacker can cause with distinct operation IDs.
- An unavailable SMTP service creates a durable backlog. Monitor backlog size, age of the oldest pending notification, failed attempts and provider rejection/bounce signals in production. Indefinite delivery success is not promised.
- Deduplication is per operation, not an incident-management system. A materially new discrepancy on the same operation can add audit evidence without sending another initial alert.
- Recipients are selected by trusted deployment configuration. Notification routing by customer, account ownership or an identity provider is not implemented.
- An attacker who also controls Audit/Protected, the trusted processor or host is outside the stated Primary-only threat model. SMTP and a local inbox do not add an independently administered security boundary.
- The existing recorded throughput measurements predate this feature. They do not establish the throughput, email capacity or production reliability of this notification revision. The latest strict 20 TPS acceptance failure remains disclosed in the repository README; adding alerts does not change that result.

## Demonstration and review checklist

1. Start the local stack with its capture configuration and open `http://127.0.0.1:8025`.
2. Submit a legitimate fixture transaction and confirm its protected outcome. A normal transfer should not generate an integrity-incident email.
3. Create a fresh fabricated Primary row using the bounded Live Lab scenario, then wait for detection and protected audit evidence.
4. Inspect the captured message and correlate its operation/incident reference with that evidence. Check that the pending forged operation has no financial effect; email appearance alone is not proof of protection.
5. Observe repeated reconciliation of the same fixture. It should not create a second notification row for the same operation, though an interrupted SMTP acknowledgement can still cause duplicate delivery.
6. Test mail-service outage/retry separately from a presentation or throughput run. Retain failed as well as successful observations; do not delete existing audit evidence to obtain a clean inbox or passing result.

The article, presentation and downloadable release package are dated artifacts and are not silently regenerated by this feature. When preparing a new publication, update those artifacts explicitly and validate their claims against new test evidence.

## Verification on September 8, 2026

The notification revision passed the full offline Maven `verify` build: 137 Java tests in 19 suites, with zero failures, errors or skips. This includes atomic audit/outbox rollback, concurrent and repeated incident deduplication, post-settlement tampering, message privacy, lease fencing and restart recovery, retry/rate controls, SMTP configuration validation, and operator-only status authorization. A real JavaMail SMTP exchange with an ephemeral loopback receiver confirmed envelope recipients, suppressed BCC headers, a stable message identifier and plain-text content. It did not deliver email externally.

All 44 Node checks passed, including private-configuration validation and precedence, existing offline/live demo tests and evidence utilities. Compose configuration and PowerShell syntax checks passed. Project content validation passed with no findings.

The initial full-stack attempt was blocked by Docker Desktop's own startup failure. After Docker became available, normal startup applied the additive notification schema without resetting existing databases, volumes or history. All 18 PostgreSQL role-isolation checks passed. Unauthenticated and ordinary application requests to the notification status endpoint returned HTTP 401; the administrator credential could read status.

The subsequent [PostgreSQL-to-Mailpit integration run](../evidence/notification-verification-2026-09-08.json), `2026-09-08T15-49-53.807Z-28014d6c`, passed all four fixture cases:

- A valid funded transfer completed and produced no incident notification.
- A fresh forged Primary row was quarantined without a financial effect and produced one protected notification and one captured email.
- Repeating that forged identity retained the same notification row and a single captured email over a five-second observation. This does not remove the documented at-least-once crash window.
- Tampering with the Primary MAC of an already completed fixture produced a separate captured alert, while its original protected result, balances and postings remained unchanged.

Both captured messages matched their incident IDs and safe reason descriptions and contained no financial payload, account identifiers, MACs or service secrets. The harness preserves its isolated fixtures, evidence and messages, and does not replace the interactive Live Lab's fixture state. It refuses real SMTP mode and verifies that the Mailpit container has no forwarding/relay configuration before creating fixtures.

To repeat with the healthy local capture stack running, use PowerShell:

```powershell
. .\scripts\common.ps1
& (Get-DemoNode) .\scripts\verify-notifications.mjs
```

No new throughput result, Gmail inbox delivery, real-provider TLS handshake, or email availability guarantee is established by this integration run. SMTP retry/restart and rollback behavior is covered by the automatic tests above; this full-stack run did not interrupt the mail service or test host power loss.
