#!/usr/bin/env node
/*
 * Smoke test for the WebView side (app/src/main/assets/web + bundled Verovio),
 * run in headless Chromium at phone size with a stand-in for the Kotlin host.
 *
 *   node tools/web-smoke-test.js [screenshotDir]
 *
 * Needs the `playwright` package (preinstalled in Claude Code cloud sessions;
 * elsewhere: `npm install playwright && npx playwright install chromium`).
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ASSETS = path.resolve(__dirname, '../app/src/main/assets');
const SHOTS = process.argv[2] ? path.resolve(process.argv[2]) : null;
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.musicxml': 'application/xml' };

function serve() {
  const server = http.createServer((req, res) => {
    const file = path.join(ASSETS, decodeURIComponent(new URL(req.url, 'http://x').pathname));
    if (!file.startsWith(ASSETS) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404).end();
      return;
    }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

function check(condition, message) {
  if (!condition) throw new Error('FAILED: ' + message);
  console.log('ok   ' + message);
}

async function waitForEvent(page, name, after = 0) {
  await page.waitForFunction(
    ([n, a]) => (window.grullaEvents || []).slice(a).some((e) => e.name === n),
    [name, after],
    { timeout: 30000 },
  );
  return page.evaluate(([n, a]) => window.grullaEvents.slice(a).filter((e) => e.name === n).pop(), [name, after]);
}

const eventCount = (page) => page.evaluate(() => window.grullaEvents.length);

async function shot(page, name) {
  if (!SHOTS) return;
  fs.mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({ path: path.join(SHOTS, name + '.png') });
}

(async () => {
  const server = await serve();
  const base = 'http://127.0.0.1:' + server.address().port;
  const browser = await chromium.launch();
  try {
    const context = await browser.newContext({ viewport: { width: 412, height: 860 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));

    await page.goto(base + '/web/index.html');
    const ready = await waitForEvent(page, 'onReady');
    check(/^6\.3\.0/.test(ready.args[0]), 'Verovio starts (' + ready.args[0] + ')');

    let mark = await eventCount(page);
    await page.evaluate(() => window.grulla.load('/samples/ode-to-joy.musicxml', { scale: 40, mode: 'continuous' }));
    const loaded = await waitForEvent(page, 'onScoreLoaded', mark);
    const result = JSON.parse(loaded.args[0]);
    check(result.timemap.length > 50, 'timemap returned (' + result.timemap.length + ' entries)');
    check(result.midi.startsWith('TVRoZA'), 'MIDI returned as base64 SMF');
    check(result.timemap.filter((e) => e.measureOn).length === 16, '16 measures in the timemap');
    check(await page.locator('svg').count() >= 1, 'SVG rendered');
    await shot(page, '1-continuous');

    // Highlight the notes sounding in measure 5.
    const entry = result.timemap.find((e) => e.measureOn && e.tstamp >= 10000);
    await page.evaluate((ids) => window.grulla.highlight(ids, true), entry.on);
    check(await page.locator('.playing').count() === entry.on.length, 'highlight marks ' + entry.on.length + ' notes');
    check(await page.locator('#cursor').isVisible(), 'cursor visible');
    await page.waitForTimeout(400);
    await shot(page, '2-highlight');

    // Tap a measure (the 3rd) to seek.
    const measureIds = result.timemap.filter((e) => e.measureOn).map((e) => e.measureOn);
    const box = await page.locator('#' + measureIds[2]).boundingBox();
    await page.evaluate(() => window.scrollTo(0, 0));
    const box2 = await page.locator('#' + measureIds[2]).boundingBox();
    mark = await eventCount(page);
    await page.mouse.click(box2.x + box2.width / 2, box2.y + box2.height * 0.9);
    const tapped = await waitForEvent(page, 'onMeasureTapped', mark);
    check(tapped.args[0] === measureIds[2], 'tapping measure 3 reports its id');
    check(box !== null, 'measure has a box');

    // Every note in the timemap has a MIDI pitch (for listening mode).
    const noteIds = new Set(result.timemap.flatMap((e) => e.on || []));
    check(noteIds.size > 0 && [...noteIds].every((id) => typeof result.pitches[id] === 'number'), 'pitches for all ' + noteIds.size + ' notes');
    check(result.pitches[result.timemap[0].on[0]] === 76 || result.pitches[result.timemap[0].on[0]] === 48, 'first chord pitch is E5 or C3');

    // Long press (right click with a mouse) on measure 4 reports it; the loop shades measures 2–4.
    mark = await eventCount(page);
    const box4 = await page.locator('#' + measureIds[3]).boundingBox();
    await page.mouse.click(box4.x + box4.width / 2, box4.y + box4.height * 0.9, { button: 'right' });
    const pressed = await waitForEvent(page, 'onMeasureLongPressed', mark);
    check(pressed.args[0] === measureIds[3], 'long press on measure 4 reports its id');
    await page.evaluate(([a, b]) => window.grulla.setLoop(a, b), [measureIds[1], measureIds[3]]);
    const bands = await page.locator('.loop-band').count();
    check(bands >= 1, 'loop shading drawn (' + bands + ' band(s))');
    await shot(page, '2b-loop');
    await page.evaluate(() => window.grulla.setLoop(null));
    check(await page.locator('.loop-band').count() === 0, 'loop cleared');

    // Thumbnail of page 1 as a PNG (the canvas must not be tainted by the SVG).
    mark = await eventCount(page);
    await page.evaluate(() => window.grulla.thumbnail(240));
    const thumb = await waitForEvent(page, 'onThumbnail', mark);
    check(thumb.args[0].startsWith('data:image/png;base64,') && thumb.args[0].length > 5000, 'thumbnail PNG (' + thumb.args[0].length + ' chars)');
    if (SHOTS) fs.writeFileSync(path.join(SHOTS, 'thumbnail.png'), Buffer.from(thumb.args[0].split(',')[1], 'base64'));

    // Pinch out with two fingers through CDP: expect a re-layout at a larger scale.
    const cdp = await context.newCDPSession(page);
    const touch = (type, spread) => cdp.send('Input.dispatchTouchEvent', {
      type,
      touchPoints: type === 'touchEnd' ? [] : [{ x: 206 - spread, y: 400 }, { x: 206 + spread, y: 400 }],
    });
    mark = await eventCount(page);
    await touch('touchStart', 50);
    for (let s = 60; s <= 100; s += 10) await touch('touchMove', s);
    await touch('touchEnd', 0);
    const zoom = await waitForEvent(page, 'onZoomChanged', mark);
    check(zoom.args[0] === 80, 'pinch zoom x2 reports scale 80 (got ' + zoom.args[0] + ')');
    await shot(page, '3-pinched');

    // Page mode with a large scale: several pages; highlighting a later note turns the page.
    mark = await eventCount(page);
    await page.evaluate(() => window.grulla.setLayout({ mode: 'page', scale: 70 }));
    const paged = await waitForEvent(page, 'onPageChanged', mark);
    const pageCount = paged.args[1];
    check(pageCount > 1, 'page mode paginates (' + pageCount + ' pages)');
    const last = result.timemap.filter((e) => e.on && e.on.length).pop();
    mark = await eventCount(page);
    await page.evaluate((ids) => window.grulla.highlight(ids, true), last.on);
    const turned = await waitForEvent(page, 'onPageChanged', mark);
    check(turned.args[0] === pageCount, 'highlighting the last note turns to page ' + pageCount);
    check(await page.locator('.playing').count() === last.on.length, 'highlight survives the page turn');
    await page.evaluate(() => window.grulla.countIn(2, 4));
    check(await page.locator('#count-in').isVisible(), 'count-in badge visible');
    await shot(page, '4-page-mode');

    // Clearing and bad input.
    await page.evaluate(() => { window.grulla.countIn(0, 4); window.grulla.clearHighlight(); });
    check(await page.locator('.playing').count() === 0, 'clearHighlight removes highlight');
    mark = await eventCount(page);
    await page.evaluate(() => window.grulla.load('/samples/missing.musicxml', {}));
    const error = await waitForEvent(page, 'onError', mark);
    check(/404/.test(error.args[0]), 'missing file reports an error');

    check(errors.length === 0, 'no uncaught page errors ' + JSON.stringify(errors));
    console.log('\nWeb smoke test passed.');
  } finally {
    await browser.close();
    server.close();
  }
})().catch((e) => {
  console.error(e.message || e);
  process.exit(1);
});
