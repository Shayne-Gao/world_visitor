// PLAYWRIGHT_PATH points to a locally installed playwright-core package.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright-core');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
    const browser = await chromium.launch({
        executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
        headless: true,
        args: ['--allow-file-access-from-files']
    });
    try {
        const page = await browser.newPage({ viewport: { width: 412, height: 850 }, deviceScaleFactor: 2 });
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.route(/^https?:/, route => route.abort());
        await page.goto(pathToFileURL(path.join(__dirname, '../app/src/main/assets/web/index.html')).href);
        await page.waitForFunction(() => typeof runGeometryJob === 'function' && typeof FogCanvasLayer !== 'undefined');
        const result = await page.evaluate(async () => {
            if (currentMapLayer) map.removeLayer(currentMapLayer);
            const tracks = Array.from({ length: 2000 }, (_, i) => {
                const x = 103.81 + (i % 50) * 0.0005, y = 1.34 + Math.floor(i / 50) * 0.0005;
                return { id: `test_${i}`, timestamp: 1700000000000 + i, brushRadiusKm: 0.02,
                    encodedPath: PolylineUtil.encode([[x, y], [x + 0.0002, y + 0.0002]]) };
            });
            let ticks = 0, maxGap = 0, previous = performance.now();
            const start = previous;
            const timer = setInterval(() => {
                const now = performance.now();
                maxGap = Math.max(maxGap, now - previous);
                previous = now;
                ticks++;
            }, 16);
            const data = await rebuildRenderCacheProgressively({ version: '2.0.0', sourceOfTruth: { tracks } });
            clearInterval(timer);
            const computeMs = performance.now() - start;
            setAppData(data);
            map.setView([1.34, 103.81], 17, { animate: false });
            renderLayers();
            const layer = fogLayer;
            function alphaAt(latlng) {
                const p = map.latLngToLayerPoint(latlng).subtract(L.DomUtil.getPosition(layer._canvas));
                const ratio = Math.min(devicePixelRatio, 2);
                return layer._canvas.getContext('2d').getImageData(Math.round(p.x * ratio), Math.round(p.y * ratio), 1, 1).data[3];
            }
            const centerAlpha = alphaAt([1.34, 103.81]);
            const fogAlpha = alphaAt([1.339, 103.809]);
            const projected = layer._projected;
            const panStart = performance.now();
            map.panBy([30, 20], { animate: false });
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            return { computeMs, ticks, maxGap, centerAlpha, fogAlpha,
                panMs: performance.now() - panStart, sameProjection: layer._projected === projected,
                centerAfterPan: alphaAt([1.34, 103.81]), area: data.metadata.totalAreaKm2 };
        });
        assert.ok(result.ticks > 5, 'page must keep ticking while geometry is computed');
        assert.equal(result.centerAlpha, 0, 'explored center must be transparent');
        assert.ok(result.fogAlpha > 150, 'unexplored surroundings must remain fogged');
        assert.equal(result.centerAfterPan, 0);
        assert.equal(result.sameProjection, true, 'panning must reuse projected paths');
        assert.deepEqual(errors, []);
        console.log(JSON.stringify(result, null, 2));
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
