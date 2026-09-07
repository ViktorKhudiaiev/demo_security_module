import assert from 'node:assert/strict';

// Reserve each returned ID before polling so concurrent requests cannot count
// the same protected operation twice, even when their final balances cancel out.
export function claimLoadOperationId(seenIds, id) {
  assert.equal(typeof id, 'string', 'Submission response has no operation ID.');
  assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    'Submission response has an invalid operation UUID.');
  const normalized = id.toLowerCase();
  assert.ok(!seenIds.has(normalized), `Duplicate load operation ID: ${normalized}`);
  seenIds.add(normalized);
  return normalized;
}

export function assertLoadCompletion(expected, state) {
  const context = `Operation ${expected.operationId}`;
  assert.equal(state?.status, 'COMPLETED', `${context}: not completed.`);
  assert.equal(state.outcomeRelayed, true, `${context}: audit outcome is not relayed.`);
  assert.equal(state.id, expected.operationId, `${context}: protected response ID mismatch.`);
  // Processor.operation exposes the persisted Operation DTO and postings whose
  // operation_id is selected by the response ID. Posting rows have no separate ID.
  const operation = state.operation;
  assert.ok(operation, `${context}: protected operation payload is missing.`);
  const fields = {
    id: expected.operationId,
    type: 'TRANSFER',
    fromAccountId: expected.fromAccountId,
    toAccountId: expected.toAccountId,
    amountMinor: expected.amountCents,
    currency: expected.currency,
    idempotencyKey: expected.idempotencyKey,
    relatedOperationId: null,
  };
  for (const [field, value] of Object.entries(fields)) {
    assert.equal(operation[field], value, `${context}: protected operation ${field} mismatch.`);
  }
  assert.ok(Array.isArray(state.postings) && state.postings.length === 2,
    `${context}: expected exactly two protected postings.`);
  const actualPostings = state.postings.map(posting => ({
    leg: posting?.leg, accountId: posting?.accountId, amountCents: posting?.amountCents,
  })).sort((left, right) => left.leg - right.leg);
  assert.deepEqual(actualPostings, [
    { leg: 0, accountId: expected.fromAccountId, amountCents: -expected.amountCents },
    { leg: 1, accountId: expected.toAccountId, amountCents: expected.amountCents },
  ], `${context}: protected postings do not match the requested debit and credit.`);
}
