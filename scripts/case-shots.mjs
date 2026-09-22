// case-shots.sh가 임시 디렉터리에서 실행한다. 조합마다 공유 링크를 열어 자동 실행을 기다리고, 결과 구간만 잘라 PNG로 남긴다.
import { chromium } from 'playwright';

const BASE = process.env.BASE, OUT = process.env.OUT_DIR;
const FIXED = { seatCount: 100, userCount: 1000, raceWindowMs: 20, seed: 42 };

// 38가지. 5×2×4×2=80에서 두 번 접는다.
// REDIS_AS_SOT는 카운터가 Redis라 oversell 전략이 무시되므로 조합당 한 장이면 된다.
// 앱 대수로 결과가 갈리는 전략은 JVM 락인 LOCAL_LOCK뿐이고, 나머지 방어는 DB·Redis에 있어 1대와 2대가 같다.
const cases = [];
for (const doubleBooking of ['NONE', 'UNIQUE_CONSTRAINT']) {
  for (const cacheConsistency of ['NONE', 'TTL_SHORT', 'INVALIDATE_ON_WRITE']) {
    for (const oversell of ['NONE', 'CONDITIONAL_UPDATE', 'PESSIMISTIC', 'OPTIMISTIC'])
      cases.push({ oversell, doubleBooking, cacheConsistency, appInstances: 2 });
    for (const appInstances of [1, 2])
      cases.push({ oversell: 'LOCAL_LOCK', doubleBooking, cacheConsistency, appInstances });
  }
  cases.push({ oversell: 'NONE', doubleBooking, cacheConsistency: 'REDIS_AS_SOT', appInstances: 2 });
}

const name = c => `${c.oversell}-${c.doubleBooking}-${c.cacheConsistency}-${c.appInstances}app`.toLowerCase();
const link = c => `${BASE}/?${new URLSearchParams({ ...FIXED, ...c })}`;

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 900, height: 1100 } });
const done = () => page.waitForFunction(
  () => !['idle', 'RUNNING'].includes(document.getElementById('verdict').textContent), null, { timeout: 180_000 });

for (const [i, c] of cases.entries()) {
  await page.goto(link(c)); // 링크로 열면 폼이 채워지고 바로 실행된다
  await done();
  await page.waitForTimeout(300); // 전략 해설 details가 열리며 레이아웃이 밀린 뒤에 잰다
  // 범례부터 비교 표 끝까지. 문서 좌표라 뷰포트보다 길어도 fullPage clip으로 한 번에 담긴다.
  const clip = await page.evaluate(() => {
    const top = document.getElementById('legend').getBoundingClientRect();
    const bottom = document.getElementById('cmp').getBoundingClientRect();
    const body = document.body.getBoundingClientRect();
    return { x: body.x + scrollX, y: top.y + scrollY, width: body.width, height: bottom.bottom - top.top };
  });
  await page.screenshot({ path: `${OUT}/${name(c)}.png`, fullPage: true, clip });
  const verdict = await page.locator('#verdict').textContent();
  console.log(`${String(i + 1).padStart(2)}/${cases.length} ${verdict.padEnd(8)} ${name(c)}`);
}

await browser.close();
