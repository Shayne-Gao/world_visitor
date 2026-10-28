// Runs inside TrackingFlowTest against the real JsBridge, Service and Room.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright-core');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const assert = require('node:assert/strict');
const root = path.join(__dirname, '..');
const activity = fs.readFileSync(path.join(root, 'app/src/main/java/io/shayne/fogvisitor/MainActivity.kt'), 'utf8');
const injected = activity.slice(activity.indexOf('        val script = """', activity.indexOf('private fun injectNativeArchiveSync')) + '        val script = """'.length);
const nativeScript = injected.slice(0, injected.indexOf('""".trimIndent()')).trim();
const methods = [...fs.readFileSync(path.join(root, 'app/src/main/java/io/shayne/fogvisitor/JsBridge.kt'), 'utf8')
    .matchAll(/@JavascriptInterface\s+fun (\w+)/g)].map(m => m[1]);
const endpoint = process.env.P0_BRIDGE_URL;

(async () => {
    const browser = await chromium.launch({
        executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
        headless: true, args: ['--allow-file-access-from-files']
    });
    const context = await browser.newContext();
    await context.addInitScript(({ endpoint, methods }) => {
        window.AndroidBridge = Object.fromEntries(methods.map(name => [name, (...args) => {
            const xhr = new XMLHttpRequest();
            xhr.open('POST', `${endpoint}/${name}`, false);
            xhr.send(JSON.stringify(args));
            if (xhr.status !== 200) throw new Error(xhr.responseText);
            return xhr.responseText;
        }]));
        window.__fogP0Debug = (event, payload) => window.p0Evidence(event, payload);
        // Match the device failure: Blob workers cannot import APK file URLs.
        // Keep real Worker execution; only remove the unsupported resource capability.
        const NativeWorker = window.Worker;
        window.Worker = class extends NativeWorker {
            constructor(url, options) {
                const xhr = new XMLHttpRequest();
                xhr.open('GET', url, false);
                xhr.send();
                const guard = `const originalImportScripts = self.importScripts;
                    self.importScripts = (...urls) => {
                        if (urls.some(url => String(url).startsWith('file:'))) {
                            throw new DOMException("Failed to execute 'importScripts': APK file script failed to load", 'NetworkError');
                        }
                        return originalImportScripts(...urls);
                    };`;
                const guardedUrl = URL.createObjectURL(new Blob([guard, '\n', xhr.responseText], { type: 'text/javascript' }));
                super(guardedUrl, options);
                URL.revokeObjectURL(guardedUrl);
            }
        };
    }, { endpoint, methods });
    await context.exposeBinding('p0Evidence', async (_, event, payload) => {
        if (!process.env.FOG_DEBUG_URL) return;
        await fetch(process.env.FOG_DEBUG_URL, {
            method: 'POST', body: JSON.stringify({ sessionId: 'p0-recording-loss',
                runId: process.env.DEBUG_RUN_ID || 'pre-fix', hypothesisId: 'A-C',
                msg: event, data: payload })
        }).catch(() => {});
    });
    await context.route(/^https?:/, route => route.request().url().startsWith(endpoint)
        ? route.continue() : route.abort());
    async function open() {
        await fetch(`${endpoint}/openActivity`, { method: 'POST', body: '[]' });
        const page = await context.newPage();
        page.on('dialog', dialog => dialog.dismiss());
        page.on('pageerror', error => process.stderr.write(`PAGE ERROR ${error}\n`));
        page.on('console', message => {
            if ((message.type() === 'warning' || message.type() === 'error') &&
                !message.text().includes('net::ERR_FAILED')) {
                process.stderr.write(`PAGE ${message.type()} ${message.text()}\n`);
            }
        });
        await page.goto(pathToFileURL(path.join(root, 'app/src/main/assets/web/index.html')).href);
        await page.waitForFunction(() => typeof DataManager !== 'undefined' && window.appData);
        await page.evaluate(nativeScript);
        await page.waitForFunction(() => window.__fogNativeTrackingUiBound);
        await page.waitForFunction(() => !window.__fogHydrationInFlight);
        return page;
    }
    const failures = [];
    async function expectExploredPixel(lng, lat) {
        const pixels = await page.evaluate(async ([lng, lat]) => {
            map.setView([lat, lng], 17, { animate: false });
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            if (!fogLayer?._canvas?.isConnected) return { connected: false };
            const alpha = (latitude, longitude) => {
                const point = map.latLngToLayerPoint([latitude, longitude]).subtract(L.DomUtil.getPosition(fogLayer._canvas));
                const ratio = Math.min(devicePixelRatio, 2);
                return fogLayer._canvas.getContext('2d').getImageData(Math.round(point.x * ratio), Math.round(point.y * ratio), 1, 1).data[3];
            };
            return { connected: true, explored: alpha(lat, lng), fog: alpha(lat - 0.001, lng - 0.001) };
        }, [lng, lat]);
        assert.equal(pixels.connected, true);
        assert.equal(pixels.explored, 0, 'saved coordinate must be visibly revealed, not just present in geometry');
        assert.ok(pixels.fog > 150, 'unexplored surroundings must remain fogged');
    }
    async function check(name, fn) {
        try { await fn(); process.stdout.write(`PASS ${name}\n`); }
        catch (error) { failures.push(`${name}: ${error}`); process.stderr.write(`FAIL ${name}: ${error}\n`); }
    }
    let page;
    try {
        page = await open();
        await check('Worker starts when file imports are denied', async () => {
            const cache = await page.evaluate(() => runGeometryJob({ mode: 'reset', tracks: [], token: -1 }));
            assert.equal(cache.totalAreaKm2, 0);
        });
        if (failures.length) throw new Error(failures.join('\n'));
        await check('manual mark is durable before page close', async () => {
            const counts = await page.evaluate(async () => {
                const before = JSON.parse(AndroidBridge.getNativeArchiveSummary()).trackCount;
                document.getElementById('manualDrawBtnFloating').click();
                const stopped = !JSON.parse(AndroidBridge.getNativeTrackingStatus()).shouldTrack;
                startDraw(L.latLng(1.34, 103.81));
                moveDraw(L.latLng(1.3403, 103.8103));
                await endDraw(true);
                document.getElementById('manualDrawBtnFloating').click();
                return { before, after: JSON.parse(AndroidBridge.getNativeArchiveSummary()).trackCount, stopped };
            });
            assert.equal(counts.after, counts.before + 1);
            assert.equal(counts.stopped, true, 'manual marking must really pause native tracking');
        });
        await page.close();
        page = await open();
        await check('manual mark visible after cold page reopen', async () => {
            await page.waitForFunction(() => appData.sourceOfTruth.tracks.some(t => t.source === 'web_draw') &&
                turf.booleanPointInPolygon([103.81, 1.34], appData.renderCache.globalExplored), null, { timeout: 20000 });
            await expectExploredPixel(103.81, 1.34);
        });
        await check('foreground samples visible through real native sync', async () => {
            await fetch(`${endpoint}/deliver`, { method: 'POST', body: '[1000,200]' });
            await page.waitForFunction(() => appData.sourceOfTruth.tracks.some(t => t.source === 'android_background_track_segment') &&
                turf.booleanPointInPolygon([103.81 + 1020 / 111290, 1.34], appData.renderCache.globalExplored),
                null, { timeout: 25000 });
            await page.waitForFunction(() => !nativeIncrementalUiFlushTimer, null, { timeout: 20000 });
            await expectExploredPixel(103.81 + 1020 / 111290, 1.34);
        });
        await page.close();
        await fetch(`${endpoint}/deliver`, { method: 'POST', body: '[3000,400]' });
        page = await open();
        await check('background samples visible after page reopen', async () => {
            await page.waitForFunction(() => appData.renderCache.globalExplored &&
                turf.booleanPointInPolygon([103.81 + 3020 / 111290, 1.34], appData.renderCache.globalExplored),
                null, { timeout: 25000 });
            await page.waitForFunction(() => !nativeIncrementalUiFlushTimer, null, { timeout: 20000 });
            await expectExploredPixel(103.81 + 3020 / 111290, 1.34);
        });
        await check('manual persistence independent of rendering failure', async () => {
            const counts = await page.evaluate(async () => {
                const before = JSON.parse(AndroidBridge.getNativeArchiveSummary()).trackCount;
                const original = turf.difference;
                const originalJob = runGeometryJob;
                turf.difference = () => { throw new Error('controlled geometry failure'); };
                runGeometryJob = async () => { throw new Error('controlled geometry failure'); };
                try { await processTrackAndSave([[103.9, 1.4], [103.901, 1.401]], 'web_draw'); }
                catch (_) {}
                finally { turf.difference = original; runGeometryJob = originalJob; }
                return { before, after: JSON.parse(AndroidBridge.getNativeArchiveSummary()).trackCount };
            });
            assert.equal(counts.after, counts.before + 1, 'valid raw coordinates must be durable even when geometry fails');
        });
        await page.close();
        page = await open();
        await check('saved mark recovers after failed rendering and reopen', async () => {
            await page.waitForFunction(() => appData.renderCache.globalExplored &&
                turf.booleanPointInPolygon([103.9, 1.4], appData.renderCache.globalExplored), null, { timeout: 20000 });
        });
        await check('native write failure is not reported as saved', async () => {
            const state = await page.evaluate(async () => {
                const before = JSON.parse(AndroidBridge.getNativeArchiveSummary()).trackCount;
                const beforePage = appData.sourceOfTruth.tracks.length;
                const append = AndroidBridge.appendNativeTrackJson;
                AndroidBridge.appendNativeTrackJson = () => JSON.stringify({ ok: false, reason: 'controlled database failure' });
                try {
                    const saved = await processTrackAndSave([[104.5, 3.0]], 'web_draw');
                    return { saved, before, after: JSON.parse(AndroidBridge.getNativeArchiveSummary()).trackCount,
                        beforePage, afterPage: appData.sourceOfTruth.tracks.length };
                } finally { AndroidBridge.appendNativeTrackJson = append; }
            });
            assert.equal(state.saved, false);
            assert.equal(state.before, state.after);
            assert.equal(state.beforePage, state.afterPage);
        });
        await check('large archive marking persists and renders', async () => {
            await page.evaluate(async () => {
                const tracks = Array.from({ length: 2000 }, (_, i) => {
                    const x = 103.8 + (i % 50) * 0.0005, y = 1.3 + Math.floor(i / 50) * 0.0005;
                    return { id: `fixture-${i}`, timestamp: 1700000000000 + i, source: 'web_draw',
                        brushRadiusKm: 0.02, encodedPath: PolylineUtil.encode([[x, y], [x + 0.0002, y + 0.0002]]) };
                });
                const raw = JSON.stringify({ version: '2.0.0', sourceOfTruth: { tracks } });
                AndroidBridge.importNativeArchiveJson(raw, true);
                await rebuildRenderCacheProgressively(appData);
                await processTrackAndSave([[103.8001, 1.3001], [103.801, 1.301]], 'web_draw');
            });
            const state = await page.evaluate(() => ({
                native: JSON.parse(AndroidBridge.getNativeArchiveSummary()).trackCount,
                page: appData.sourceOfTruth.tracks.length,
                visible: turf.booleanPointInPolygon([103.8001, 1.3001], appData.renderCache.globalExplored)
            }));
            assert.equal(state.native, state.page);
            assert.ok(state.native >= 2005);
            assert.equal(state.visible, true);
        });
        await check('stale 1646-track cache recovers all 1920 native tracks', async () => {
            await page.evaluate(async () => {
                const tracks = Array.from({ length: 1920 }, (_, i) => {
                    const x = i === 1919 ? 105 : 104.0 + (i % 48) * 0.0005;
                    const y = i === 1919 ? 3 : 2.0 + Math.floor(i / 48) * 0.0005;
                    return { id: `recovery-${i}`, timestamp: 1700000000000 + i, source: 'web_draw',
                        brushRadiusKm: 0.02, encodedPath: PolylineUtil.encode([[x, y], [x + 0.0002, y + 0.0002]]) };
                });
                AndroidBridge.importNativeArchiveJson(JSON.stringify({ version: '2.0.0',
                    sourceOfTruth: { tracks: tracks.slice(0, 1646) } }), false);
                await window.__fogApplyNormalizedArchiveToPage(JSON.parse(AndroidBridge.exportNativeArchiveJson()));
                AndroidBridge.importNativeArchiveJson(JSON.stringify({ version: '2.0.0',
                    sourceOfTruth: { tracks } }), false);
                const primary = await localforage.getItem(STORAGE_KEY);
                if (primary.sourceOfTruth.tracks.length !== 1646) throw new Error('stale cache fixture was not established');
            });
            await page.close();
            page = await open();
            await page.waitForFunction(() => appData.sourceOfTruth.tracks.length === 1920 &&
                !window.__fogHydrationInFlight, null, { timeout: 30000 });
            const state = await page.evaluate(async () => ({
                ids: appData.sourceOfTruth.tracks.map(t => t.id).sort(),
                stored: (await localforage.getItem(STORAGE_KEY)).sourceOfTruth.tracks.length,
                native: JSON.parse(AndroidBridge.getNativeArchiveSummary()).trackCount
            }));
            assert.deepEqual(state.ids, Array.from({ length: 1920 }, (_, i) => `recovery-${i}`).sort());
            assert.equal(state.stored, 1920);
            assert.equal(state.native, 1920);
            await expectExploredPixel(105, 3);
        });
        await check('diagnostic export includes native truth and page synchronization', async () => {
            const diagnostic = await page.evaluate(async () => {
                let copied = '';
                const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
                Object.defineProperty(navigator, 'clipboard', { configurable: true,
                    value: { writeText: async text => { copied = text; } } });
                try { await exportDiagnosticInfo(); }
                finally {
                    if (original) Object.defineProperty(navigator, 'clipboard', original);
                    else delete navigator.clipboard;
                }
                return JSON.parse(copied);
            });
            assert.equal(diagnostic.native.archive.trackCount, 1920);
            assert.equal(diagnostic.pageSync.trackCount, 1920);
            assert.equal(diagnostic.pageSync.revision, diagnostic.native.archive.revision);
            assert.ok(Array.isArray(diagnostic.native.tracking.debugEvents));
        });
    } finally {
        await browser.close();
    }
    assert.deepEqual(failures, [], failures.join('\n'));
})().catch(error => { console.error(error); process.exitCode = 1; });
