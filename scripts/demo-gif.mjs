// demo-gif.sh가 임시 디렉터리에서 실행한다. 링크를 열어 자동 실행을 기다리고, 전략만 바꿔 한 번 더 돌린다.
import { chromium } from 'playwright';

const BASE = process.env.BASE, dir = process.env.VIDEO_DIR;
const link = `${BASE}/?seatCount=100&userCount=1000&appInstances=2&raceWindowMs=20&seed=42&oversell=NONE&doubleBooking=NONE&cacheConsistency=NONE`;
const size = { width: 900, height: 1100 };

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: size, recordVideo: { dir, size } });

// 헤드리스 녹화 영상에는 실제 커서가 찍히지 않는다. mousemove를 따라다니는 점을 페이지에 심는다.
await page.addInitScript(() => {
  const dot = document.createElement('div');
  dot.style.cssText = 'position:fixed;top:0;left:0;width:20px;height:20px;margin:-10px 0 0 -10px;'
    + 'border-radius:50%;background:rgba(220,38,38,.6);border:2px solid #fff;'
    + 'box-shadow:0 1px 4px rgba(0,0,0,.5);pointer-events:none;z-index:2147483647';
  let x = 0, y = 0;
  const draw = (scale) => { dot.style.transform = `translate(${x}px,${y}px) scale(${scale})`; };
  addEventListener('mousemove', (e) => { x = e.clientX; y = e.clientY; draw(1); }, true);
  addEventListener('mousedown', () => draw(0.5), true);
  addEventListener('mouseup', () => draw(1), true);
  addEventListener('DOMContentLoaded', () => { document.body.append(dot); draw(1); });
});

const running = () => page.waitForFunction(() => document.getElementById('verdict').textContent === 'RUNNING', null, { timeout: 10_000 });
const done = () => page.waitForFunction(() => !['idle', 'RUNNING'].includes(document.getElementById('verdict').textContent), null, { timeout: 180_000 });
// 클릭 대상까지 커서를 끌고 간다. steps가 있어야 중간 mousemove가 나와 영상에 궤적이 남는다.
const glideTo = async (selector) => {
  const target = page.locator(selector);
  await target.scrollIntoViewIfNeeded();
  const box = await target.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 25 });
  await page.waitForTimeout(300);
};

await page.goto(link);
await page.mouse.move(size.width / 2, 100, { steps: 5 });
await done();
await page.waitForTimeout(1500);

await glideTo('[name=oversell][value=CONDITIONAL_UPDATE]');
await page.check('[name=oversell][value=CONDITIONAL_UPDATE]');
await glideTo('[name=doubleBooking][value=UNIQUE_CONSTRAINT]');
await page.check('[name=doubleBooking][value=UNIQUE_CONSTRAINT]');
await glideTo('form button:not([type])');
await page.click('form button:not([type])');
await running();
await done();
await page.locator('#cmp').scrollIntoViewIfNeeded();
await page.waitForTimeout(2500);

await page.close();
await browser.close();
