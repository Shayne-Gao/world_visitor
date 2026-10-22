// Exercise the APK's actual Leaflet touch handler, not a substitute event emitter.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright-core');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
    const browser = await chromium.launch({
        executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
        headless: true,
        args: ['--allow-file-access-from-files']
    });
    try {
        const page = await browser.newPage({ viewport: { width: 412, height: 850 }, hasTouch: true });
        const runId = process.env.DEBUG_RUN_ID || 'pre-fix';
        await page.exposeFunction('zoomEvidence', async (msg, data) => {
            if (!process.env.FOG_DEBUG_URL) return;
            await fetch(process.env.FOG_DEBUG_URL, {
                method: 'POST',
                body: JSON.stringify({ sessionId: 'tracking-zoom', runId, hypothesisId: 'D',
                    location: 'FogCanvasLayer', msg: `[DEBUG] ${msg}`, data })
            });
        });
        await page.addInitScript(() => { window.__fogZoomDebug = (msg, data) => window.zoomEvidence(msg, data); });
        await page.route(/^https?:/, route => route.abort());
        await page.goto(pathToFileURL(path.join(__dirname, '../app/src/main/assets/web/index.html')).href);
        await page.waitForFunction(() => typeof FogCanvasLayer !== 'undefined' && typeof map !== 'undefined');
        const result = await page.evaluate(async () => {
            await new Promise(resolve => setTimeout(resolve, 300));
            map.setView([1.34, 103.81], 16, { animate: false });
            const layer = new FogCanvasLayer(null, { mode: 'fogMask' }).addTo(map);
            const reference = L.imageOverlay(
                'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg"/>',
                [[1.339, 103.809], [1.341, 103.811]]
            ).addTo(map);
            const before = layer._canvas.style.transform;
            const initialReference = reference.getElement().style.transform;
            const rect = map.getContainer().getBoundingClientRect();
            const touch = (x, y) => ({ clientX: rect.left + x, clientY: rect.top + y });
            const handler = map.touchZoom;
            handler._onTouchStart({ touches: [touch(150, 400), touch(250, 400)], preventDefault() {} });
            const frames = [];
            for (const width of [130, 170, 220, 170, 90, 60]) {
                handler._onTouchMove({ touches: [touch(200 - width / 2, 400), touch(200 + width / 2, 400)], preventDefault() {} });
                await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                const matrix = new DOMMatrix(getComputedStyle(layer._canvas).transform);
                const actualScale = matrix.a;
                const expectedOffset = map._latLngToNewLayerPoint(layer._drawTopLeft, map.getZoom(), map.getCenter());
                frames.push({ zoom: map.getZoom(), expectedScale: map.getZoomScale(map.getZoom(), layer._drawZoom),
                    actualScale, offsetError: Math.hypot(matrix.e - expectedOffset.x, matrix.f - expectedOffset.y),
                    drawZoom: layer._drawZoom, transform: layer._canvas.style.transform });
            }
            const referenceMoved = reference.getElement().style.transform !== initialReference;
            handler._onTouchEnd();
            await new Promise(resolve => setTimeout(resolve, 350));
            const finalScale = new DOMMatrix(getComputedStyle(layer._canvas).transform).a;
            await window.zoomEvidence('pinch-summary', { before, frames, referenceMoved, finalScale });
            layer.remove();
            reference.remove();
            return { frames, referenceMoved, finalScale };
        });
        assert.equal(result.referenceMoved, true, 'Leaflet reference overlay must move during pinch');
        for (const frame of result.frames) {
            assert.ok(Math.abs(frame.actualScale - frame.expectedScale) < 0.001,
                `fog scale ${frame.actualScale} must follow map scale ${frame.expectedScale}`);
            assert.ok(frame.offsetError < 1, 'pinch anchor must match Leaflet coordinates');
            assert.equal(frame.drawZoom, 16, 'pinch must reuse the original bitmap');
        }
        assert.equal(result.finalScale, 1, 'final redraw must reset the bitmap transform');
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
