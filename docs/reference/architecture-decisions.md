# Architecture: Current Code and Production Target

Current decision: **Main + Audit/Protected, preserving authenticated-execution guarantees, with embedded ActiveMQ inside the processor JVM.**

The [current architecture and acceptance checklist](architecture.md) defines database roles, table ownership, delivery acknowledgment order, exact-snapshot execution, migration and production limits.

Open [Architecture and Sequence](../index.html) for the numbered two-database diagrams. The production target is a separate expandable diagram with independent KMS/HSM, IAM, retention and recovery requirements; it does not depict deployed cloud services.

The former three-database decision and its measurements remain in the [historical September 5 record](../history/architecture-2026-09-05.md). Its counts and performance numbers describe that version, not the current broker path.

Unchanged guarantee: control of Main/Primary alone cannot authenticate a new operation, replace independent evidence, edit protected balances, or force duplicate financial execution. The trusted application, processor, key service, protected database and host remain outside attacker scope. No confidentiality, destruction-prevention or production-compliance guarantee is claimed.
