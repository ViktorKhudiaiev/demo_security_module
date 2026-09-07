import assert from 'node:assert/strict';
import test from 'node:test';
import { assertLoadCompletion, claimLoadOperationId } from './load-evidence.mjs';

const expected = {
  operationId: 'a0000000-0000-0000-0000-000000000001',
  fromAccountId: 'b0000000-0000-0000-0000-000000000001',
  toAccountId: 'b0000000-0000-0000-0000-000000000002',
  amountCents: 1,
  currency: 'USD',
  idempotencyKey: 'load-test-0',
};

// Shape and field names match Processor.operation() and the persisted Operation DTO.
function completion(request = expected) {
  return {
    id: request.operationId,
    status: 'COMPLETED',
    outcomeRelayed: true,
    operation: {
      id: request.operationId, schemaVersion: 1,
      ledgerId: 'c0000000-0000-0000-0000-000000000001',
      type: 'TRANSFER', fromAccountId: request.fromAccountId, toAccountId: request.toAccountId,
      amountMinor: request.amountCents, currency: request.currency,
      idempotencyKey: request.idempotencyKey, relatedOperationId: null, createdAtMicros: 1,
    },
    postings: [
      { accountId: request.fromAccountId, amountCents: -request.amountCents, leg: 0 },
      { accountId: request.toAccountId, amountCents: request.amountCents, leg: 1 },
    ],
  };
}

test('accepts a distinct ID and exact durable transfer evidence', () => {
  const seenIds = new Set();
  assert.equal(claimLoadOperationId(seenIds, expected.operationId), expected.operationId);
  assert.doesNotThrow(() => assertLoadCompletion(expected, completion()));
});

test('rejects an ID already claimed by an in-flight request, including UUID case variants', () => {
  const seenIds = new Set();
  claimLoadOperationId(seenIds, expected.operationId);
  assert.throws(() => claimLoadOperationId(seenIds, expected.operationId), /Duplicate load operation ID/);
  assert.throws(() => claimLoadOperationId(seenIds, expected.operationId.toUpperCase()), /Duplicate load operation ID/);
  assert.equal(seenIds.size, 1);
});

test('rejects replayed circular transfers even when their net balance change is zero', () => {
  const seenIds = new Set();
  const outward = { ...expected };
  const returning = {
    ...expected, operationId: 'a0000000-0000-0000-0000-000000000002',
    fromAccountId: expected.toAccountId, toAccountId: expected.fromAccountId,
    idempotencyKey: 'load-test-1',
  };
  for (const request of [outward, returning]) {
    claimLoadOperationId(seenIds, request.operationId);
    assertLoadCompletion(request, completion(request));
  }
  const replay = { ...outward, idempotencyKey: 'load-test-2' };
  assert.throws(() => claimLoadOperationId(seenIds, replay.operationId), /Duplicate load operation ID/);
  assert.throws(() => assertLoadCompletion(replay, completion(outward)), /idempotencyKey mismatch/);
});

for (const [name, mutate, message] of [
  ['HTTP acceptance without completion', state => { state.status = 'PENDING'; }, /not completed/],
  ['missing durable audit', state => { state.outcomeRelayed = false; }, /audit outcome/],
  ['wrong response operation ID', state => { state.id = expected.fromAccountId; }, /response ID mismatch/],
  ['wrong payload operation ID', state => { state.operation.id = expected.fromAccountId; }, /operation id mismatch/],
  ['wrong transfer account', state => { state.operation.toAccountId = expected.fromAccountId; }, /toAccountId mismatch/],
  ['wrong transfer amount', state => { state.operation.amountMinor = 2; }, /amountMinor mismatch/],
  ['wrong operation type', state => { state.operation.type = 'FUNDING'; }, /type mismatch/],
  ['wrong currency', state => { state.operation.currency = 'EUR'; }, /currency mismatch/],
  ['missing operation payload', state => { delete state.operation; }, /payload is missing/],
  ['missing posting', state => { state.postings.pop(); }, /exactly two/],
  ['extra posting', state => { state.postings.push({ ...state.postings[0] }); }, /exactly two/],
  ['wrong posting account', state => { state.postings[0].accountId = expected.toAccountId; }, /requested debit and credit/],
  ['wrong posting amount', state => { state.postings[1].amountCents = 2; }, /requested debit and credit/],
  ['zero-value postings', state => { state.postings.forEach(posting => { posting.amountCents = 0; }); }, /requested debit and credit/],
  ['reversed debit sign', state => { state.postings[0].amountCents = 1; }, /requested debit and credit/],
  ['duplicate legs', state => { state.postings[1].leg = 0; }, /requested debit and credit/],
  ['non-numeric amount', state => { state.postings[1].amountCents = '1'; }, /requested debit and credit/],
]) {
  test(`rejects ${name}`, () => {
    const state = completion();
    mutate(state);
    assert.throws(() => assertLoadCompletion(expected, state), message);
  });
}
