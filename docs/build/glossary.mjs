export const glossary=[
 ['HMAC','A code computed from data and a secret key. Changing the amount changes the expected code. It does not encrypt the data.'],
 ['Canonicalization','An unambiguous conversion of fields into bytes. Each v1 element has a field ID, a type and a byte length.'],
 ['Issuance receipt','Independent evidence of exactly what the key service authenticated. It commits before the MAC returns.'],
 ['Settlement','Transfer execution: the protected database changes balances and commits both postings in one transaction.'],
 ['TOCTOU','Time of check / time of use: data changes between checking and using it. A final comparison and execution of the same checked snapshot address this gap.'],
 ['Idempotency','Repeating the same request returns the same result without another debit. It does not mean ignoring every new operation with similar fields.'],
 ['Outbox / inbox','Durably stored delivery work. It survives process restarts and supports retries. A message alone does not authorize payment.'],
 ['Merkle tree','A hash tree that commits records to one root. An inclusion proof connects a particular leaf to that root.'],
 ['Ed25519 checkpoint','A signature on a checkpoint that binds log identity, tree size and root. The verifier must obtain the expected public key through a trusted channel.'],
 ['Trust domain','A boundary with separate authority. The demo uses separate PostgreSQL instances and credentials, while trusting the common Windows administrator.'],
 ['KMS / HSM','Key management service / hardware security module. This version uses software keys. A real provider adapter remains future work.'],
 ['Quarantine','The operation receives no financial postings and requires investigation. It does not automatically freeze the recipient account.']
];
