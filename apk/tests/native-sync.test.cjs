const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/web/index.html'), 'utf8');
const body = html.slice(html.indexOf('    async function syncNativeArchiveIncrementally('),
    html.indexOf('    window.syncNativeArchiveIncrementally ='));

function setup(changes) {
    const appData = {
        metadata: { nativeEpoch: 'db-a', nativeRevision: 3 },
        sourceOfTruth: { tracks: [{ id: 'future', timestamp: 9999999999999 }] },
        renderCache: { dailyExplored: {}, globalExplored: null }
    };
    const queries = [];
    const context = {
        appData, window: {}, document: { visibilityState: 'visible' },
        performance, geometryOwner: appData, geometryOwnerRevision: 0, geometryToken: 1,
        AndroidBridge: { getNativeArchiveChanges(epoch, revision) {
            queries.push({ epoch, revision });
            return JSON.stringify(changes);
        } },
        reportDebugEvent() {}, scheduleNativeIncrementalUiFlush() {},
        async flushNativeIncrementalUi() {},
        async runGeometryJob() { return { dailyPatch: {}, globalExplored: null, totalAreaKm2: 0 }; },
        getTrackCountFromData(data) { return data.sourceOfTruth.tracks.length; },
        upsertTrackIntoSourceOfTruth(track) {
            const i = appData.sourceOfTruth.tracks.findIndex(t => t.id === track.id);
            if (i < 0) appData.sourceOfTruth.tracks.push(track);
            else appData.sourceOfTruth.tracks[i] = track;
        }
    };
    vm.createContext(context);
    vm.runInContext(body, context);
    return { context, queries, appData };
}

test('future business timestamps do not block revision-based synchronization', async () => {
    const { context, queries, appData } = setup({
        epoch: 'db-a', revision: 4, trackCount: 2, tracks: [{ id: 'current', timestamp: 1000 }]
    });
    await context.syncNativeArchiveIncrementally({}, 9999999999999, { deferUi: true });
    assert.deepEqual(queries, [{ epoch: 'db-a', revision: 3 }]);
    assert.equal(appData.sourceOfTruth.tracks.length, 2);
    assert.equal(appData.metadata.nativeRevision, 4);
    assert.equal(context.window.__fogIncrementalInFlight, false);
});

test('same-count updates replace the existing id and advance revision', async () => {
    const { context, appData } = setup({
        epoch: 'db-a', revision: 4, trackCount: 1, tracks: [{ id: 'future', timestamp: 500 }]
    });
    await context.syncNativeArchiveIncrementally({}, 9999999999999, { deferUi: true });
    assert.equal(appData.sourceOfTruth.tracks.length, 1);
    assert.equal(appData.sourceOfTruth.tracks[0].timestamp, 500);
    assert.equal(appData.metadata.nativeRevision, 4);
});

test('reset and count mismatch require hydration without acknowledging revision', async () => {
    for (const changes of [
        { epoch: 'db-b', revision: 9, resetRequired: true },
        { epoch: 'db-a', revision: 9, trackCount: 2, tracks: [] }
    ]) {
        const { context, appData } = setup(changes);
        await context.syncNativeArchiveIncrementally({}, 0, { deferUi: true });
        assert.equal(context.window.__fogPendingNativeHydration, true);
        assert.equal(appData.metadata.nativeRevision, 3);
    }
});

test('edit while worker is running discards stale results', async () => {
    const { context, appData } = setup({
        epoch: 'db-a', revision: 4, trackCount: 2, tracks: [{ id: 'current', timestamp: 1000 }]
    });
    context.runGeometryJob = async () => {
        context.window.__fogArchiveRevision = 1;
        return { dailyPatch: {}, totalAreaKm2: 42 };
    };
    await context.syncNativeArchiveIncrementally({}, 0, { deferUi: true });
    assert.equal(appData.metadata.nativeRevision, 3);
    assert.equal(appData.sourceOfTruth.tracks.length, 1);
    assert.equal(context.window.__fogPendingNativeHydration, true);
});

test('worker failure releases in-flight flag and requests a rebuild', async () => {
    const { context } = setup({ epoch: 'db-a', revision: 4, trackCount: 1, tracks: [] });
    context.runGeometryJob = async () => { throw new Error('worker failed'); };
    await assert.rejects(context.syncNativeArchiveIncrementally({}, 0), /worker failed/);
    assert.equal(context.window.__fogIncrementalInFlight, false);
    assert.equal(context.window.__fogPendingNativeHydration, true);
    assert.equal(context.geometryOwner, null);
});
