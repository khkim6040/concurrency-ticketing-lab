// 브라우저(index.html)와 node --test가 같이 쓰는 순수 함수. DOM·fetch 없음.
export const OVERSELL = ['NONE', 'LOCAL_LOCK', 'CONDITIONAL_UPDATE', 'PESSIMISTIC', 'OPTIMISTIC'];
export const DOUBLE_BOOKING = ['NONE', 'UNIQUE_CONSTRAINT'];
export const CACHE = ['NONE', 'TTL_SHORT', 'INVALIDATE_ON_WRITE', 'REDIS_AS_SOT'];
export const PARAMS = ['seatCount', 'userCount', 'appInstances', 'raceWindowMs', 'seed', 'oversell', 'doubleBooking', 'cacheConsistency'];

// 서버 RunSpec의 require 범위와 같다. seed는 정수이기만 하면 된다.
const RANGE = { seatCount: [1, 1000], userCount: [1, 10_000], appInstances: [1, 2], raceWindowMs: [0, 200],
                seed: [Number.MIN_SAFE_INTEGER, Number.MAX_SAFE_INTEGER] };
const ENUMS = { oversell: OVERSELL, doubleBooking: DOUBLE_BOOKING, cacheConsistency: CACHE };

export const flat = spec => ({ seatCount: spec.seatCount, userCount: spec.userCount, appInstances: spec.appInstances,
  raceWindowMs: spec.raceWindowMs, seed: spec.seed, ...spec.strategies });

export const toQuery = spec => '?' + new URLSearchParams(Object.entries(flat(spec)).map(([k, v]) => [k, String(v)]));

// 여덟 키가 모두 있고 범위 안일 때만 spec. 아니면 null이고 UI는 기본 폼을 보인다.
export function fromQuery(search) {
  const p = new URLSearchParams(search);
  const spec = { strategies: {} };
  for (const [k, [lo, hi]] of Object.entries(RANGE)) {
    const v = Number(p.get(k));
    if (!p.has(k) || !Number.isInteger(v) || v < lo || v > hi) return null;
    spec[k] = v;
  }
  for (const [k, names] of Object.entries(ENUMS)) {
    const v = p.get(k);
    if (!names.includes(v)) return null;
    spec.strategies[k] = v;
  }
  return spec;
}

const CONSISTENCY = ['oversoldCount', 'ledgerMismatch', 'doubleBookedSeats', 'phantomStockViews', 'staleWindowMs'];
const PERFORMANCE = ['throughput', 'p50Ms', 'p95Ms', 'p99Ms', 'errorCount', 'retryCount', 'dbConnectionPeak', 'duplicateKeyCount', 'viewCount', 'viewDbReads'];
const pick = (r, k) => r == null ? null : k === 'verdict' ? r.verdict : CONSISTENCY.includes(k) ? r.consistency[k] : r.performance[k];

// 비교 표의 행. prev가 없으면 prev·diff·pct는 null. pct는 처리량에만.
export const compareRows = (prev, cur) => ['verdict', ...CONSISTENCY, ...PERFORMANCE].map(key => {
  const p = pick(prev, key), c = pick(cur, key);
  const numeric = key !== 'verdict' && p != null;
  return { key, prev: p, cur: c, diff: numeric ? c - p : null, pct: numeric && key === 'throughput' && p > 0 ? (c - p) / p : null };
});

export const changedParams = (prev, cur) =>
  prev == null ? [] : PARAMS.filter(k => flat(prev.spec)[k] !== flat(cur.spec)[k]);

export const T = {
  en: {
    copy: 'Copy link', copied: 'Copied', prev: 'previous', cur: 'current', diff: 'diff',
    viewer: (v, a) => `viewer sees ${v} / actual ${a}`, stale: '← stale read',
    reason: {
      PASS: () => 'PASS: all three consistency metrics are 0',
      DEGRADED: r => `DEGRADED: ${Math.round(r * 100)}% of the NONE baseline throughput`,
      FAIL: ks => `FAIL: ${ks.join(', ')} are not 0`,
    },
    metric: {
      verdict: 'Verdict', oversoldCount: 'Oversold', ledgerMismatch: 'Ledger mismatch', doubleBookedSeats: 'Double booked seats',
      phantomStockViews: 'Stock views after sell-out', staleWindowMs: 'Stale window (ms)',
      throughput: 'Throughput (req/s)', p50Ms: 'p50 (ms)', p95Ms: 'p95 (ms)', p99Ms: 'p99 (ms)', errorCount: 'Errors',
      retryCount: 'Retries', dbConnectionPeak: 'DB connection peak', duplicateKeyCount: 'Duplicate key rejections',
      viewCount: 'Stock views', viewDbReads: 'Views that hit the DB',
    },
  },
  ko: {
    copy: '링크 복사', copied: '복사됨', prev: '직전', cur: '이번', diff: '차이',
    viewer: (v, a) => `조회자가 보는 잔여석 ${v} / 실제 ${a}`, stale: '← stale read',
    reason: {
      PASS: () => 'PASS: 정합성 지표 세 개가 모두 0',
      DEGRADED: r => `DEGRADED: 같은 조건 NONE 처리량의 ${Math.round(r * 100)}%`,
      FAIL: ks => `FAIL: ${ks.join(', ')}이(가) 0이 아님`,
    },
    metric: {
      verdict: '판정', oversoldCount: '정원 초과 판매', ledgerMismatch: '원장 불일치', doubleBookedSeats: '중복 배정 좌석',
      phantomStockViews: '매진 후 잔여석 노출', staleWindowMs: 'stale 창 (ms)',
      throughput: '처리량 (req/s)', p50Ms: 'p50 (ms)', p95Ms: 'p95 (ms)', p99Ms: 'p99 (ms)', errorCount: '오류',
      retryCount: '재시도', dbConnectionPeak: 'DB 커넥션 최대', duplicateKeyCount: '중복 키 거절',
      viewCount: '잔여석 조회', viewDbReads: 'DB까지 간 조회',
    },
  },
};

export const label = (key, lang) => key === 'verdict' ? T[lang].metric.verdict : `${T[lang].metric[key]} (${key})`;

export function verdictReason(r, lang) {
  const t = T[lang].reason;
  if (r.verdict === 'FAIL') return t.FAIL(['oversoldCount', 'ledgerMismatch', 'doubleBookedSeats'].filter(k => r.consistency[k] !== 0));
  if (r.verdict === 'DEGRADED') return t.DEGRADED(r.performance.throughput / r.baselineThroughput);
  return t.PASS();
}
