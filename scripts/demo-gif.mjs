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
