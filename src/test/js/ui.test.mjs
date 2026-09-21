import { test } from 'node:test';
import assert from 'node:assert/strict';
import { flat, toQuery, fromQuery, compareRows, changedParams, verdictReason, label, PARAMS, T } from '../../main/resources/static/lib.js';

const spec = { seatCount: 100, userCount: 1000, appInstances: 2, raceWindowMs: 20, seed: 42,
               strategies: { oversell: 'CONDITIONAL_UPDATE', doubleBooking: 'UNIQUE_CONSTRAINT', cacheConsistency: 'NONE' } };
const report = (spec, verdict, over = {}) => ({
  runId: 'r', spec, verdict, baselineThroughput: null,
  consistency: { oversoldCount: 0, ledgerMismatch: 0, doubleBookedSeats: 0, phantomStockViews: 0, staleWindowMs: 0, ...over.consistency },
  performance: { throughput: 1000, p50Ms: 1, p95Ms: 2, p99Ms: 3, errorCount: 0, retryCount: 0, dbConnectionPeak: 20, duplicateKeyCount: 0, viewCount: 0, viewDbReads: 0, ...over.performance },
  ...over.top,
});

test('toQuery then fromQuery round-trips', () => {
  const q = toQuery(spec);
  assert.equal(q, '?seatCount=100&userCount=1000&appInstances=2&raceWindowMs=20&seed=42&oversell=CONDITIONAL_UPDATE&doubleBooking=UNIQUE_CONSTRAINT&cacheConsistency=NONE');
  assert.deepEqual(fromQuery(q), spec);
});

test('fromQuery rejects missing keys, out-of-range numbers and unknown strategies', () => {
  assert.equal(fromQuery(''), null);
  assert.equal(fromQuery(toQuery(spec).replace('&seed=42', '')), null);
  assert.equal(fromQuery(toQuery({ ...spec, seatCount: 0 })), null);
  assert.equal(fromQuery(toQuery({ ...spec, raceWindowMs: 201 })), null);
  assert.equal(fromQuery(toQuery({ ...spec, seed: 1.5 })), null);
  assert.equal(fromQuery(toQuery({ ...spec, strategies: { ...spec.strategies, oversell: 'BOGUS' } })), null);
});

test('flat merges strategies into one level in PARAMS order', () => {
  assert.deepEqual(Object.keys(flat(spec)), PARAMS);
  assert.equal(flat(spec).oversell, 'CONDITIONAL_UPDATE');
});

test('compareRows with no previous run has current only', () => {
  const rows = compareRows(null, report(spec, 'PASS'));
  assert.equal(rows.length, 16);
  assert.deepEqual(rows[0], { key: 'verdict', prev: null, cur: 'PASS', diff: null, pct: null });
  assert.deepEqual(rows.find(r => r.key === 'throughput'), { key: 'throughput', prev: null, cur: 1000, diff: null, pct: null });
});

test('compareRows diffs numbers and gives throughput a ratio', () => {
  const prev = report(spec, 'FAIL', { consistency: { oversoldCount: 90 }, performance: { throughput: 4000 } });
  const rows = compareRows(prev, report(spec, 'PASS'));
  assert.deepEqual(rows[0], { key: 'verdict', prev: 'FAIL', cur: 'PASS', diff: null, pct: null });
  assert.deepEqual(rows.find(r => r.key === 'oversoldCount'), { key: 'oversoldCount', prev: 90, cur: 0, diff: -90, pct: null });
  assert.deepEqual(rows.find(r => r.key === 'throughput'), { key: 'throughput', prev: 4000, cur: 1000, diff: -3000, pct: -0.75 });
});

test('changedParams lists only the keys that differ', () => {
  const prev = report({ ...spec, strategies: { ...spec.strategies, oversell: 'NONE', doubleBooking: 'NONE' } }, 'FAIL');
  assert.deepEqual(changedParams(prev, report(spec, 'PASS')), ['oversell', 'doubleBooking']);
  assert.deepEqual(changedParams(null, report(spec, 'PASS')), []);
});

test('verdictReason names the non-zero metrics, the baseline ratio, or all clear', () => {
  const fail = report(spec, 'FAIL', { consistency: { oversoldCount: 3, doubleBookedSeats: 2 } });
  assert.equal(verdictReason(fail, 'en'), 'FAIL: oversoldCount, doubleBookedSeats are not 0');
  const degraded = report(spec, 'DEGRADED', { performance: { throughput: 380 }, top: { baselineThroughput: 1000 } });
  assert.equal(verdictReason(degraded, 'en'), 'DEGRADED: 38% of the NONE baseline throughput');
  assert.equal(verdictReason(report(spec, 'PASS'), 'en'), 'PASS: all three consistency metrics are 0');
  assert.equal(verdictReason(report(spec, 'PASS'), 'ko'), 'PASS: 정합성 지표 세 개가 모두 0');
});

test('label keeps the metric key next to the translated name', () => {
  assert.equal(label('oversoldCount', 'en'), 'Oversold (oversoldCount)');
  assert.equal(label('oversoldCount', 'ko'), '정원 초과 판매 (oversoldCount)');
  assert.equal(label('verdict', 'en'), 'Verdict');
});

test('T has the same keys in both languages', () => {
  assert.deepEqual(Object.keys(T.ko).sort(), Object.keys(T.en).sort());
  assert.deepEqual(Object.keys(T.ko.metric).sort(), Object.keys(T.en.metric).sort());
  assert.deepEqual(Object.keys(T.ko.reason).sort(), Object.keys(T.en.reason).sort());
});
