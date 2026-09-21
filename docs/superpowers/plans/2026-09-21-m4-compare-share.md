# M4 비교·공유·해설 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 시드와 파라미터를 담은 공유 링크, 직전 실행과의 비교 표, 전략 11종의 접이식 해설(영어 기본·한국어 토글), 데모 GIF와 README를 추가한다. 서버 로직은 바뀌지 않는다.

**Architecture:** UI의 순수 함수(링크 직렬화, 비교 행, 판정 이유, 문자열 사전)를 ES 모듈 `static/lib.js`로 빼고 `index.html`은 `<script type="module">`로 불러온다. 같은 파일을 `node --test`가 import한다. 공유 링크는 `RunSpec` 여덟 키의 쿼리스트링이고, 페이지를 열 때 있으면 폼을 채워 자동 실행한다. 비교는 브라우저가 직전 리포트를 변수로 기억한다. 정적 텍스트는 `<span lang="en">`/`<span lang="ko">` 쌍과 CSS 두 줄로, 동적 텍스트는 사전 `T`로 언어를 바꾼다. GIF는 Playwright `recordVideo`로 녹화하고 `ffmpeg`로 변환한다.

**Tech Stack:** 브라우저 ES 모듈(빌드 없음), Node 22 `node:test`, Playwright(GIF 생성 시에만 임시 설치), ffmpeg. 서버는 Kotlin/Spring Boot 그대로.

**Spec:** `docs/superpowers/specs/2026-09-21-m4-design.md` (결정 근거 `docs/decisions.md`)

## Global Constraints

- 서버 코드 변경은 `RunController.kt`의 주석 한 줄 삭제뿐. 새 런타임 의존성 없음. `./gradlew test`는 그대로 통과해야 한다(`JAVA_HOME=/opt/homebrew/opt/openjdk@21`).
- 링크 쿼리 키는 정확히 `seatCount, userCount, appInstances, raceWindowMs, seed, oversell, doubleBooking, cacheConsistency`. 언어는 링크에 넣지 않는다.
- 시드는 UI가 `Math.floor(Math.random() * 2 ** 31)`로 만들어 항상 보낸다. 입력란은 실행 뒤에도 값을 유지한다. 재실행 버튼은 없다.
- 번역하지 않는 것: 전략 이름, 파라미터 필드명, 지표 키, `PASS`/`DEGRADED`/`FAIL`/`RUNNING`/`idle`, 페이지 제목, 서버 오류 메시지. 지표 이름은 번역 뒤에 키를 괄호로 붙인다.
- 기본 언어 `en`. 선택은 `localStorage.lang`. `localStorage` 접근은 try/catch.
- `<details>`는 기본 접힘. 실행이 끝나면 고른 전략 3개를 `open`으로 바꾸고, 이미 열린 것은 건드리지 않는다(스펙 그대로. 직전 실행의 해설이 비교 표 옆에 남는 것이 의도).
- 코드 주석은 한국어. 커밋은 `type: English description` 한 줄. 본문·Co-Authored-By 없음.

## File Structure

| 파일 | 변경 |
| --- | --- |
| `src/main/resources/static/lib.js` (신규) | `flat`, `toQuery`, `fromQuery`, `compareRows`, `changedParams`, `verdictReason`, `label`, `T`, `PARAMS` |
| `src/test/js/ui.test.mjs` (신규) | 위 함수의 `node:test` 검증 |
| `src/main/resources/static/index.html` | 전면 재작성: 언어 토글, 시드, 파라미터·축 설명, 해설 11개, 범례, 판정 이유, 비교 표, 링크 복사, 자동 실행 |
| `src/main/kotlin/lab/web/RunController.kt` | "영속화는 공유 링크(M4)에서" 주석 삭제 |
| `scripts/demo-gif.sh`, `scripts/demo-gif.mjs` (신규) | Playwright 녹화 → `ffmpeg` → `docs/demo.gif` |
| `docs/demo.gif` (신규) | 데모 |
| `README.md` | M4 문단, GIF, 링크 예시, 실행법, 구조, 로드맵, 문서 링크 |

---

### Task 1: `lib.js` 순수 함수 (TDD)

**Files:**
- Create: `src/main/resources/static/lib.js`
- Test: `src/test/js/ui.test.mjs`

**Interfaces:**
- Produces (모두 `export`):
  - `PARAMS: string[]` — `['seatCount','userCount','appInstances','raceWindowMs','seed','oversell','doubleBooking','cacheConsistency']`
  - `flat(spec) → { seatCount, userCount, appInstances, raceWindowMs, seed, oversell, doubleBooking, cacheConsistency }` — `spec.strategies`를 편 객체
  - `toQuery(spec) → string` — `'?seatCount=…&…'`
  - `fromQuery(search: string) → spec | null` — 키 누락·범위 밖·모르는 enum이면 `null`
  - `compareRows(prev: report | null, cur: report) → { key, prev, cur, diff, pct }[]` — 판정 1행 + 정합성 5행 + 성능 10행 순서
  - `changedParams(prev: report | null, cur: report) → string[]` — `PARAMS` 중 `spec`이 달라진 키
  - `verdictReason(report, lang) → string`
  - `label(key, lang) → string` — 지표 이름. `verdict`는 키 없이, 나머지는 `"이름 (key)"`
  - `T: { en: {...}, ko: {...} }` — `run`, `copy`, `copied`, `prev`, `cur`, `diff`, `viewer(v, a)`, `stale`, `reason.{PASS,DEGRADED,FAIL}`, `metric.{key}`

- [x] **Step 1: 실패하는 테스트 작성**

`src/test/js/ui.test.mjs`:

```js
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
});
```

- [x] **Step 2: 실패 확인**

Run: `node --test src/test/js/ui.test.mjs`
Expected: `Cannot find module '.../static/lib.js'`로 실패.

- [x] **Step 3: `lib.js` 작성**

`src/main/resources/static/lib.js`:

```js
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
    run: 'Run', copy: 'Copy link', copied: 'Copied', prev: 'previous', cur: 'current', diff: 'diff',
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
    run: '실행', copy: '링크 복사', copied: '복사됨', prev: '직전', cur: '이번', diff: '차이',
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
```

- [x] **Step 4: 통과 확인**

Run: `node --test src/test/js/ui.test.mjs`
Expected: 9 tests pass, 0 fail.

- [x] **Step 5: 커밋**

```bash
git add src/main/resources/static/lib.js src/test/js/ui.test.mjs
git commit -m "feat: add UI pure functions for share links, comparison and i18n"
```

---

### Task 2: `index.html` 재작성과 컨트롤러 주석 삭제

**Files:**
- Modify: `src/main/resources/static/index.html` (전면 재작성)
- Modify: `src/main/kotlin/lab/web/RunController.kt:22`

**Interfaces:**
- Consumes: Task 1의 `lib.js` export 전부.
- Produces: DOM id `f`, `lang`, `random`, `hot`, `legend`, `stock`, `grid`, `verdict`, `copy`, `reason`, `cmp`. 라디오는 `.opt` 안에 `<label><input></label> <details>` 순서. Task 3의 녹화 스크립트가 `#verdict` 텍스트와 `[name=oversell][value=…]` 셀렉터, `form button:not([type])`에 의존한다.

- [x] **Step 1: `index.html` 작성**

전체를 아래로 바꾼다.

```html
<!doctype html>
<html lang="en">
<meta charset="utf-8">
<title>concurrency-ticketing-lab</title>
<style>
body{font:14px system-ui;max-width:720px;margin:2rem auto}
html[lang=en] [lang=ko],html[lang=ko] [lang=en]{display:none}
h1{display:flex;justify-content:space-between;align-items:baseline}
#lang{font:13px system-ui}
label{display:block;margin:.4rem 0}
small{color:#666}
fieldset{border:1px solid #ddd;margin:.6rem 0;padding:.4rem .8rem}
.opt{margin:.3rem 0}
.opt label{display:inline}
details{display:inline;font-size:13px;color:#444}
summary{cursor:pointer;color:#06c;display:inline;margin-left:.4rem}
details p{margin:.2rem 0 .4rem 1.4rem}
select.hot{outline:3px solid #f80}
#hot{display:none;color:#f80;margin-left:.6rem}
#hot.on{display:inline}
#legend{margin:1rem 0 0}
#legend span{margin-right:1rem}
#legend i,#grid i{display:inline-block;width:12px;height:12px;background:#ccc;vertical-align:middle}
#grid{display:grid;grid-template-columns:repeat(auto-fill,12px);gap:2px;margin:.5rem 0 1rem}
#grid i{display:block}
i.ok{background:#3a3!important}i.over{background:#f80!important}i.dup{background:#d33!important}
#verdict{font:bold 24px system-ui;padding:.5rem 1rem;display:inline-block;color:#fff;background:#888}
#verdict.PASS{background:#3a3}#verdict.DEGRADED{background:#ea0}#verdict.FAIL{background:#d33}
#copy{display:none;margin-left:1rem}
#reason{margin:.4rem 0}
#stock{margin:.5rem 0;font-weight:bold}
#stale{color:#d33}
table{border-collapse:collapse;margin-top:1rem;font-size:13px}
td,th{border:1px solid #ddd;padding:.2rem .5rem;text-align:right}
th:first-child,td:first-child{text-align:left}
tr.changed td{font-weight:bold;background:#fff3e0}
</style>
<h1>concurrency-ticketing-lab <button id="lang" type="button">한국어</button></h1>
<form id="f">
  <label>seatCount <input name="seatCount" type="number" value="100" min="1" max="1000">
    <small><span lang="en">seats N</span><span lang="ko">좌석 수 N</span></small></label>
  <label>userCount <input name="userCount" type="number" value="1000" min="1" max="10000">
    <small><span lang="en">concurrent users M</span><span lang="ko">동시 사용자 M</span></small></label>
  <label>appInstances <select name="appInstances"><option>1</option><option selected>2</option></select>
    <span id="hot"><span lang="en">a JVM lock is per instance; two instances race each other</span><span lang="ko">JVM 락은 인스턴스마다 따로라 2대에서는 서로 경쟁한다</span></span>
    <small><span lang="en">app servers</span><span lang="ko">앱 서버 대수</span></small></label>
  <label>raceWindowMs <input name="raceWindowMs" type="number" value="20" min="0" max="200">
    <small><span lang="en">delay injected between read and write</span><span lang="ko">읽기와 쓰기 사이에 넣는 지연</span></small></label>
  <label>seed <input name="seed" type="number" step="1" placeholder="random">
    <button type="button" id="random"><span lang="en">Random</span><span lang="ko">랜덤</span></button>
    <small><span lang="en">same seed = same seat picks; kept after a run, so Run again replays it</span><span lang="ko">같은 시드 = 같은 좌석 선택. 실행 뒤에도 남으므로 다시 실행하면 같은 시드로 돈다</span></small></label>

  <fieldset><legend>oversell <small><span lang="en">sells more tickets than seats</span><span lang="ko">좌석보다 표를 더 파는 문제</span></small></legend>
    <div class="opt"><label><input type="radio" name="oversell" value="NONE" checked> NONE</label>
      <details><summary><span lang="en">why?</span><span lang="ko">왜?</span></summary>
        <p lang="en">Read the count, sleep, write count minus one. Another request reads the same value in between and both write the same result, so one sale is lost from the ledger. This is a lost update.</p>
        <p lang="ko">잔여석을 읽고, 잠들고, 하나 뺀 값을 쓴다. 그 사이 다른 요청이 같은 값을 읽어 둘 다 같은 결과를 쓰므로 판매 하나가 원장에서 사라진다. lost update다.</p></details></div>
    <div class="opt"><label><input type="radio" name="oversell" value="LOCAL_LOCK"> LOCAL_LOCK</label>
      <details><summary><span lang="en">why?</span><span lang="ko">왜?</span></summary>
        <p lang="en">A ReentrantLock in the JVM serializes requests, so one instance balances the counter exactly. Two instances each serialize only their own half and the halves race each other, so it oversells almost as badly as NONE while paying the lock's latency.</p>
        <p lang="ko">JVM 안의 ReentrantLock이 요청을 직렬화하므로 앱 1대면 카운터가 정확히 맞는다. 2대면 각자 자기 절반만 직렬화하고 두 절반이 서로 경쟁해, 락의 지연은 치르면서 NONE만큼 오버셀한다.</p></details></div>
    <div class="opt"><label><input type="radio" name="oversell" value="CONDITIONAL_UPDATE"> CONDITIONAL_UPDATE</label>
      <details><summary><span lang="en">why?</span><span lang="ko">왜?</span></summary>
        <p lang="en">UPDATE ... SET remaining = remaining - 1 WHERE remaining > 0. The database checks and decrements in one statement, so there is no window to sleep in. Exact without a lock, and the fastest of the consistent strategies.</p>
        <p lang="ko">UPDATE ... SET remaining = remaining - 1 WHERE remaining > 0. DB가 한 문장 안에서 검사하고 차감하므로 잠들 틈이 없다. 락 없이 정확하고, 정합한 전략 중 가장 빠르다.</p></details></div>
    <div class="opt"><label><input type="radio" name="oversell" value="PESSIMISTIC"> PESSIMISTIC</label>
      <details><summary><span lang="en">why?</span><span lang="ko">왜?</span></summary>
        <p lang="en">SELECT ... FOR UPDATE locks the row for the whole transaction, sleep included. Exact, but every waiting transaction holds a connection, so the pool size becomes the throughput ceiling and p99 climbs to seconds.</p>
        <p lang="ko">SELECT ... FOR UPDATE가 트랜잭션 내내, 잠드는 동안에도 행을 잠근다. 정확하지만 기다리는 트랜잭션마다 커넥션을 붙들어 풀 크기가 처리량 상한이 되고 p99가 초 단위로 올라간다.</p></details></div>
    <div class="opt"><label><input type="radio" name="oversell" value="OPTIMISTIC"> OPTIMISTIC</label>
      <details><summary><span lang="en">why?</span><span lang="ko">왜?</span></summary>
        <p lang="en">Write only if the version is still the one you read, otherwise read again. Exact and faster than either lock because nothing waits, but under this contention it retries tens of thousands of times.</p>
        <p lang="ko">읽었을 때의 버전이 그대로일 때만 쓰고, 아니면 다시 읽는다. 아무도 기다리지 않아 정확하면서 락보다 빠르지만, 이 정도 경합에서는 재시도가 수만 번이다.</p></details></div>
  </fieldset>

  <fieldset><legend>doubleBooking <small><span lang="en">one seat to two users</span><span lang="ko">한 좌석을 두 사람에게</span></small></legend>
    <div class="opt"><label><input type="radio" name="doubleBooking" value="NONE" checked> NONE</label>
      <details><summary><span lang="en">why?</span><span lang="ko">왜?</span></summary>
        <p lang="en">The app checks whether the seat is taken, then inserts. Between the check and the insert another request takes the same seat. Even with an exact counter, seats overlap and the run fails.</p>
        <p lang="ko">앱이 좌석이 찼는지 조회하고 나서 삽입한다. 조회와 삽입 사이에 다른 요청이 같은 좌석을 잡는다. 카운터가 정확해도 좌석이 겹쳐 실행은 FAIL이다.</p></details></div>
    <div class="opt"><label><input type="radio" name="doubleBooking" value="UNIQUE_CONSTRAINT"> UNIQUE_CONSTRAINT</label>
      <details><summary><span lang="en">why?</span><span lang="ko">왜?</span></summary>
        <p lang="en">A unique index on (event_id, seat_no) rejects the second insert, whatever the code above it did. The rejections show up as duplicate key errors; that count is the cost of the last line of defense.</p>
        <p lang="ko">(event_id, seat_no) 유니크 인덱스가 위의 코드가 무엇을 했든 두 번째 삽입을 거절한다. 거절은 중복 키 오류로 나타나고, 그 수가 마지막 방어선의 비용이다.</p></details></div>
  </fieldset>

  <fieldset><legend>cacheConsistency <small><span lang="en">what the stock viewer sees after sell-out; no strategy makes it zero</span><span lang="ko">매진 뒤 조회자가 보는 잔여석. 어떤 전략도 0으로 만들지 못한다</span></small></legend>
    <div class="opt"><label><input type="radio" name="cacheConsistency" value="NONE" checked> NONE</label>
      <details><summary><span lang="en">why?</span><span lang="ko">왜?</span></summary>
        <p lang="en">Cache-aside with a 60 s TTL. The first viewer fills the cache and everyone sees that value until it expires. The run is shorter than the TTL, so the stale count stays to the end.</p>
        <p lang="ko">TTL 60초의 cache-aside. 첫 조회가 캐시를 채우면 만료될 때까지 모두 그 값을 본다. 실행이 TTL보다 짧아 옛값이 끝까지 남는다.</p></details></div>
    <div class="opt"><label><input type="radio" name="cacheConsistency" value="TTL_SHORT"> TTL_SHORT</label>
      <details><summary><span lang="en">why?</span><span lang="ko">왜?</span></summary>
        <p lang="en">Same, with a 1 s TTL. The stale window shrinks to the TTL but never closes. The TTL that closes it is zero, which is no cache at all, and the DB read count shows what the shorter TTL costs.</p>
        <p lang="ko">같은 방식에 TTL 1초. stale 창이 TTL 이하로 줄지만 닫히지는 않는다. 창을 닫는 TTL은 0, 즉 캐시를 없앤 것이고, DB 조회 수가 짧은 TTL의 비용을 보여준다.</p></details></div>
    <div class="opt"><label><input type="radio" name="cacheConsistency" value="INVALIDATE_ON_WRITE"> INVALIDATE_ON_WRITE</label>
      <details><summary><span lang="en">why?</span><span lang="ko">왜?</span></summary>
        <p lang="en">Delete the key after every sale. A viewer that missed the cache, read the DB and slept can write its old value after the last sale's delete, and nothing deletes it again. Raise raceWindowMs to see it every run.</p>
        <p lang="ko">판매마다 키를 지운다. 캐시 미스로 DB를 읽고 잠든 조회자가 마지막 판매의 삭제 뒤에 옛값을 쓰면 다시 지워질 기회가 없다. raceWindowMs를 올리면 매 실행 재현된다.</p></details></div>
    <div class="opt"><label><input type="radio" name="cacheConsistency" value="REDIS_AS_SOT"> REDIS_AS_SOT</label>
      <details><summary><span lang="en">why?</span><span lang="ko">왜?</span></summary>
        <p lang="en">The counter lives in Redis and DECR decides, so there is no second copy to go stale. What remains is transport delay: a value is already the past by the time you look at it. The oversell strategy is ignored here.</p>
        <p lang="ko">카운터가 Redis에 있고 DECR이 결정하므로 옛값이 될 두 번째 복사본이 없다. 남는 것은 전송 지연뿐이라 읽은 값은 보는 순간 이미 과거다. 여기서는 oversell 전략이 무시된다.</p></details></div>
  </fieldset>

  <button><span lang="en">Run</span><span lang="ko">실행</span></button>
</form>

<div id="legend">
  <span><i></i> <span lang="en">unsold</span><span lang="ko">미판매</span></span>
  <span><i class="ok"></i> <span lang="en">sold</span><span lang="ko">판매</span></span>
  <span><i class="dup"></i> <span lang="en">double booked</span><span lang="ko">중복 배정</span></span>
  <span><i class="over"></i> <span lang="en">over capacity</span><span lang="ko">정원 초과</span></span>
</div>
<div id="stock"></div>
<div id="grid"></div>
<div id="verdict">idle</div><button id="copy" type="button"></button>
<div id="reason"></div>
<table id="cmp"></table>

<script type="module">
import { toQuery, fromQuery, flat, compareRows, changedParams, verdictReason, label, T, PARAMS } from './lib.js';
const $ = id => document.getElementById(id);
const f = $('f'), grid = $('grid'), verdict = $('verdict'), stock = $('stock'), reason = $('reason'), cmp = $('cmp'), copy = $('copy');
const btn = f.querySelector('button:not([type])'), sel = f.querySelector('[name=appInstances]');
let lang = 'en', prev = null, cur = null, live = null; // prev/cur: 비교용 리포트. live: 언어를 바꿀 때 잔여석 줄을 다시 그리기 위한 진행 값.

function setLang(l) {
  lang = l;
  document.documentElement.lang = l;
  try { localStorage.lang = l; } catch {}
  $('lang').textContent = l === 'en' ? '한국어' : 'EN';
  render();
}
$('lang').onclick = () => setLang(lang === 'en' ? 'ko' : 'en');
$('random').onclick = () => { f.seed.value = ''; };

// LOCAL_LOCK + 앱 2대: 로컬 락이 무너지는 조합을 강조. REDIS_AS_SOT: Redis가 카운터라 DB 오버셀 전략은 무의미하다.
f.onchange = () => {
  const hot = f.oversell.value === 'LOCAL_LOCK' && sel.value === '2';
  sel.classList.toggle('hot', hot);
  $('hot').classList.toggle('on', hot);
  const sot = f.cacheConsistency.value === 'REDIS_AS_SOT';
  f.querySelectorAll('[name=oversell]').forEach(r => r.disabled = sot);
};

// 시드는 브라우저가 만든다. 서버 Long은 JS 숫자 정밀도를 넘어 링크가 깨진다.
function specFromForm() {
  if (!f.seed.value) f.seed.value = Math.floor(Math.random() * 2 ** 31);
  const d = Object.fromEntries(new FormData(f));
  return { seatCount: +d.seatCount, userCount: +d.userCount, appInstances: +d.appInstances, raceWindowMs: +d.raceWindowMs, seed: +d.seed,
           strategies: { oversell: d.oversell ?? 'NONE', doubleBooking: d.doubleBooking, cacheConsistency: d.cacheConsistency } };
}
function fillForm(spec) {
  for (const [k, v] of Object.entries(flat(spec))) f[k].value = v;
  f.onchange();
}

// 칸 i = 좌석 i+1. seats[i]가 0이면 회색, 1이면 초록, 2 이상이면 빨강(중복 배정). N을 넘는 판매분은 주황 칸으로 뒤에 붙인다.
function draw(n, ok, seats) {
  const over = Math.max(0, ok - n);
  grid.innerHTML = Array.from({ length: n + over }, (_, i) =>
    `<i class="${i >= n ? 'over' : (seats[i] ?? 0) >= 2 ? 'dup' : seats[i] === 1 ? 'ok' : ''}"></i>`).join('');
}
// 조회자가 마지막으로 본 잔여석과 실제 값(N − ok). 매진 뒤에도 왼쪽이 남아 있으면 그것이 stale read다.
function showStock({ n, ok, lastView }) {
  stock.innerHTML = T[lang].viewer(lastView < 0 ? '-' : lastView, n - ok) +
    (ok >= n && lastView > 0 ? ` <span id="stale">${T[lang].stale}</span>` : '');
}

// 행 = 파라미터 8개 + 지표 16개, 열 = 직전 / 이번 / 차이. 직전이 없으면 이번 열만.
function table() {
  const t = T[lang], changed = changedParams(prev, cur), fp = prev && flat(prev.spec), fc = flat(cur.spec);
  const fmt = v => v == null ? '' : Number.isFinite(v) && !Number.isInteger(v) ? v.toFixed(0) : v;
  const cells = (p, c, d) => (prev ? `<td>${fmt(p)}</td>` : '') + `<td>${fmt(c)}</td>` + (prev ? `<td>${d}</td>` : '');
  const params = PARAMS.map(k => `<tr class="${changed.includes(k) ? 'changed' : ''}"><td>${k}</td>${cells(fp?.[k], fc[k], '')}</tr>`);
  const metrics = compareRows(prev, cur).map(r => {
    const sign = v => (v > 0 ? '+' : '') + fmt(v);
    const d = r.diff == null ? '' : sign(r.diff) + (r.pct == null ? '' : ` (${sign(Math.round(r.pct * 100))}%)`);
    return `<tr><td>${label(r.key, lang)}</td>${cells(r.prev, r.cur, d)}</tr>`;
  });
  return `<tr><th></th>${prev ? `<th>${t.prev}</th>` : ''}<th>${t.cur}</th>${prev ? `<th>${t.diff}</th>` : ''}</tr>` + params.join('') + metrics.join('');
}

// 언어에 따라 달라지는 동적 텍스트를 전부 다시 그린다. 정적 텍스트는 CSS가 lang 속성으로 바꾼다.
function render() {
  copy.textContent = T[lang].copy;
  if (live) showStock(live);
  reason.textContent = cur ? verdictReason(cur, lang) : reason.textContent;
  cmp.innerHTML = cur ? table() : '';
}

f.onsubmit = async e => {
  e.preventDefault();
  btn.disabled = true;
  const spec = specFromForm();
  live = { n: spec.seatCount, ok: 0, lastView: -1 };
  draw(spec.seatCount, 0, []);
  showStock(live);
  verdict.className = ''; verdict.textContent = 'RUNNING'; reason.textContent = ''; copy.style.display = 'none';
  const res = await fetch('/api/runs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(spec) });
  const { runId, error } = await res.json();
  if (error) { verdict.textContent = 'ERROR'; reason.textContent = error; btn.disabled = false; return; }
  const timer = setInterval(async () => {
    const s = await (await fetch('/api/runs/' + runId)).json();
    live = { n: spec.seatCount, ok: s.progress.ok, lastView: s.progress.lastView };
    draw(spec.seatCount, s.progress.ok, s.progress.seats);
    showStock(live);
    if (s.status === 'RUNNING') return;
    clearInterval(timer);
    btn.disabled = false;
    verdict.className = s.report?.verdict ?? ''; verdict.textContent = s.report?.verdict ?? s.status;
    if (!s.report) { reason.textContent = s.error ?? ''; return; }
    prev = cur; cur = s.report;
    history.replaceState(null, '', toQuery(cur.spec)); // 주소창이 곧 공유 링크
    copy.style.display = 'inline';
    for (const [k, v] of Object.entries(cur.spec.strategies)) // 이번에 고른 전략의 해설만 펼친다
      f.querySelector(`[name=${k}][value=${v}]`).closest('.opt').querySelector('details').open = true;
    render();
  }, 200);
};
copy.onclick = async () => {
  await navigator.clipboard.writeText(location.href);
  copy.textContent = T[lang].copied;
  setTimeout(render, 1500);
};

let saved = null;
try { saved = localStorage.lang; } catch {}
setLang(saved === 'ko' ? 'ko' : 'en');
const linked = fromQuery(location.search); // 링크로 열었으면 폼을 채우고 바로 실행
if (linked) { fillForm(linked); f.requestSubmit(); }
</script>
```

- [x] **Step 2: 컨트롤러 주석 삭제**

`src/main/kotlin/lab/web/RunController.kt`에서

```kotlin
    private val runs = ConcurrentHashMap<String, RunState>() // ponytail: 메모리 보관, 영속화는 공유 링크(M4)에서
```

를

```kotlin
    private val runs = ConcurrentHashMap<String, RunState>() // 메모리 보관. 공유 링크는 결과가 아니라 파라미터를 담으므로 영속화하지 않는다.
```

로 바꾼다.

- [x] **Step 3: 빌드와 기존 테스트**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test -q && node --test src/test/js/ui.test.mjs`
Expected: 둘 다 통과.

- [x] **Step 4: 스택을 올리고 브라우저로 확인**

Run: `docker compose up -d --build` 후 `curl -s http://localhost:8080/lib.js | head -3`로 모듈이 서빙되는지 본다(`// 브라우저(index.html)와 ...` 첫 줄).

브라우저(Playwright MCP `browser_navigate`/`browser_snapshot`/`browser_click`가 있으면 그것으로, 없으면 직접)에서 다음을 확인한다.

1. `http://localhost:8080/`: 영어 UI, `<details>` 모두 접힘, 범례 4칸, 판정 `idle`. "한국어" 버튼을 누르면 라벨·설명·요약이 한국어로 바뀌고 새로고침해도 유지된다. 다시 "EN".
2. `LOCAL_LOCK`을 고르면(앱 2대) select에 주황 테두리와 옆의 한 줄 문구가 나온다. `REDIS_AS_SOT`를 고르면 oversell 라디오가 비활성화된다.
3. 시드를 비운 채 Run: 시드 입력란이 채워지고, 끝나면 주소창이 `?seatCount=100&…&seed=<그 값>&oversell=NONE&…`가 되며 "Copy link" 버튼이 보이고, 판정 아래 이유 한 줄(`FAIL: oversoldCount, ledgerMismatch, doubleBookedSeats are not 0` 형태), 표에 `current` 열만, 고른 전략 3개의 `<details>`만 열림. 매진 뒤 잔여석 줄에 `← stale read`.
4. `CONDITIONAL_UPDATE` + `UNIQUE_CONSTRAINT`로 바꾸고 Run(시드 유지): 표에 `previous / current / diff` 세 열, `oversell`·`doubleBooking` 행이 굵게, 처리량 행에 `(+NN%)`. 표의 `seed` 행은 두 열이 같다.
5. 3의 주소를 새 탭에 붙여 넣으면 폼이 채워지고 자동 실행된다. `?seed=abc`처럼 깨진 링크는 기본 폼을 보이고 실행하지 않는다.

- [x] **Step 5: 커밋**

```bash
git add src/main/resources/static/index.html src/main/kotlin/lab/web/RunController.kt
git commit -m "feat: share links, run comparison and bilingual strategy notes in the UI"
```

---

### Task 3: 데모 GIF 스크립트

**Files:**
- Create: `scripts/demo-gif.sh`, `scripts/demo-gif.mjs`
- Create: `docs/demo.gif` (스크립트 출력)

**Interfaces:**
- Consumes: Task 2의 DOM(`#verdict` 텍스트, `[name=oversell][value=…]`, `form button:not([type])`, `#cmp`).
- Produces: `docs/demo.gif`. README(Task 4)가 같은 링크를 예시로 쓴다.

- [x] **Step 1: 셸 스크립트**

`scripts/demo-gif.sh`:

```bash
#!/usr/bin/env bash
# 공유 링크 하나로 NONE을 돌리고, 같은 시드로 CONDITIONAL_UPDATE + UNIQUE_CONSTRAINT를 돌려 비교 표까지 녹화해 docs/demo.gif를 만든다.
# Compose 스택이 떠 있어야 한다. Node, ffmpeg 필요. Playwright는 임시 디렉터리에 설치한다.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
npm init -y >/dev/null
npm install --silent --no-audit --no-fund playwright
npx playwright install chromium
BASE="$BASE" VIDEO_DIR="$WORK/video" node "$HERE/demo-gif.mjs"
ffmpeg -y -loglevel error -i "$WORK"/video/*.webm \
  -vf "fps=10,scale=720:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse" "$HERE/../docs/demo.gif"
ls -l "$HERE/../docs/demo.gif"
```

`chmod +x scripts/demo-gif.sh`.

- [x] **Step 2: Node 스크립트**

`scripts/demo-gif.mjs`:

```js
// demo-gif.sh가 임시 디렉터리에서 실행한다. 링크를 열어 자동 실행을 기다리고, 전략만 바꿔 한 번 더 돌린다.
import { chromium } from 'playwright';

const BASE = process.env.BASE, dir = process.env.VIDEO_DIR;
const link = `${BASE}/?seatCount=100&userCount=1000&appInstances=2&raceWindowMs=20&seed=42&oversell=NONE&doubleBooking=NONE&cacheConsistency=NONE`;
const size = { width: 900, height: 1100 };

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: size, recordVideo: { dir, size } });
const running = () => page.waitForFunction(() => document.getElementById('verdict').textContent === 'RUNNING', null, { timeout: 10_000 });
const done = () => page.waitForFunction(() => !['idle', 'RUNNING'].includes(document.getElementById('verdict').textContent), null, { timeout: 180_000 });

await page.goto(link);
await done();
await page.waitForTimeout(1500);

await page.check('[name=oversell][value=CONDITIONAL_UPDATE]');
await page.check('[name=doubleBooking][value=UNIQUE_CONSTRAINT]');
await page.click('form button:not([type])');
await running();
await done();
await page.locator('#cmp').scrollIntoViewIfNeeded();
await page.waitForTimeout(2500);

await page.close();
await browser.close();
```

- [x] **Step 3: 실행**

Run: `docker compose ps`로 스택이 떠 있는지 본 뒤 `./scripts/demo-gif.sh`
Expected: 마지막 줄에 `docs/demo.gif` 크기(수백 KB에서 2 MB 사이). `open docs/demo.gif`로 확인: 영어 UI, 첫 실행 `FAIL`, 두 번째 실행 `PASS`, 비교 표에 `oversell`·`doubleBooking` 행이 굵고 `Oversold` 행의 `diff`가 음수.

`npm install`이나 `playwright install`이 네트워크로 실패하면 오류를 그대로 보고하고 멈춘다. GIF가 5 MB를 넘으면 `fps=8`로 낮춘다.

- [x] **Step 4: 커밋**

```bash
git add scripts/demo-gif.sh scripts/demo-gif.mjs docs/demo.gif
git commit -m "feat: add demo GIF recorder and the recorded demo"
```

---

### Task 4: README와 문서

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-09-21-m4-compare-share.md` (체크박스)

- [x] **Step 1: README 갱신**

`README.md`를 다음과 같이 고친다.

1. 제목 문단 다음, "## What works today" 앞에:

```markdown
![Two runs with the same seed: oversell NONE fails, CONDITIONAL_UPDATE with a unique index passes, compared side by side](docs/demo.gif)
```

2. 제목을 `## What works today (M0 to M4)`로 바꾸고 목록 끝에 세 항목 추가:

```markdown
- Share links. The address bar becomes `?seatCount=…&seed=…&oversell=…` after every run, and opening such a link fills the form and runs it. The seed fixes which seat each user picks, so the link reproduces the experiment; the oversold count and the throughput still depend on timing and vary from run to run. The seed field keeps its value after a run, so pressing Run again replays the same seed.
- A comparison table. The browser remembers the previous run and shows previous, current and diff columns, with the parameters that changed in bold. Run `NONE` first, change one strategy, run again, and the throughput cost of that strategy is one row.
- A "why?" note next to every strategy, two or three sentences each, collapsed until a run finishes and then opened for the strategies that ran. The UI is English by default with a Korean toggle; strategy names, field names and metric keys stay as they are in the code.
```

3. "## Running it"의 코드 블록을:

```bash
docker compose up -d --build
open http://localhost:8080      # parameter form and results
./scripts/dod.sh                # reproduces the tables above
./scripts/demo-gif.sh           # re-records docs/demo.gif (needs Node and ffmpeg)
```

그 아래 문단을:

```markdown
`./gradlew test` runs the unit tests and `node --test src/test/js/ui.test.mjs` the UI functions, both without Docker.

A link that reproduces the second half of the GIF on your own stack:

```
http://localhost:8080/?seatCount=100&userCount=1000&appInstances=2&raceWindowMs=20&seed=42&oversell=CONDITIONAL_UPDATE&doubleBooking=UNIQUE_CONSTRAINT&cacheConsistency=NONE
```
```

4. "## How it is put together"의 구조 목록에 `web/RunController.kt` 아래 두 줄 추가:

```
  static/index.html         form, seat grid, comparison table, bilingual notes
  static/lib.js             share-link encoding, comparison rows, verdict reason, strings; also run by node --test
```

5. 로드맵: `- [x] M4 Side-by-side comparison, seed-based share links, per-strategy explanations`.

6. 문서 목록 끝에:

```markdown
- [M4 design](docs/superpowers/specs/2026-09-21-m4-design.md) and [M4 implementation plan](docs/superpowers/plans/2026-09-21-m4-compare-share.md) (Korean)
```

- [x] **Step 2: 계획 체크박스**

이 파일의 완료한 `- [ ]`를 `- [x]`로 바꾼다.

- [x] **Step 3: 커밋**

```bash
git add README.md docs/superpowers/plans/2026-09-21-m4-compare-share.md
git commit -m "docs: describe M4 in the README and mark the roadmap"
```
