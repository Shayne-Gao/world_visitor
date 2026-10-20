const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.join(__dirname, '../app/src/main/assets/web');
const turf = require(path.join(root, 'vendor/turf/turf.min.js'));
const worker = { turf, self: { postMessage() {} } };
vm.createContext(worker);
vm.runInContext(fs.readFileSync(path.join(root, 'geometry-worker.js'), 'utf8'), worker);

function encode(points) {
    let lat = 0, lng = 0, result = '';
    function append(delta) {
        let value = delta < 0 ? ~(delta << 1) : delta << 1;
        while (value >= 32) { result += String.fromCharCode((32 | (value & 31)) + 63); value >>>= 5; }
        result += String.fromCharCode(value + 63);
    }
    points.forEach(([x, y]) => {
        const nextLat = Math.round(y * 1e5), nextLng = Math.round(x * 1e5);
        append(nextLat - lat); append(nextLng - lng);
        lat = nextLat; lng = nextLng;
    });
    return result;
}
const track = (points, time = 1700000000000) => ({
    timestamp: time, brushRadiusKm: 0.02, encodedPath: encode(points)
});
const compute = job => worker.computeGeometry(job, () => {});

test('empty archive remains fogged', () => {
    const result = compute({ tracks: [] });
    assert.equal(result.globalExplored, null);
    assert.equal(result.totalAreaKm2, 0);
    assert.ok(turf.booleanPointInPolygon([103.8, 1.3], result.globalFog));
});

test('balanced rebuild preserves sequential union area and exploration', () => {
    const tracks = Array.from({ length: 32 }, (_, i) =>
        track([[103.8 + i * 0.0001, 1.3], [103.8002 + i * 0.0001, 1.3002]]));
    const before = JSON.stringify(tracks);
    const result = compute({ tracks });
    let expected = null;
    tracks.forEach(t => {
        const buffer = turf.buffer(turf.lineString(worker.decodePath(t.encodedPath)), 0.02, { units: 'kilometers' });
        expected = expected ? turf.union(expected, buffer) : buffer;
    });
    assert.ok(Math.abs(turf.area(expected) / 1e6 - result.totalAreaKm2) < 1e-8);
    assert.equal(JSON.stringify(tracks), before);
    assert.ok(turf.booleanPointInPolygon([103.8, 1.3], result.globalExplored));
    assert.ok(!turf.booleanPointInPolygon([103.8, 1.3], result.globalFog));
});

test('incremental cache matches complete rebuild including day buckets', () => {
    const first = track([[103.8, 1.3], [103.801, 1.301]]);
    const second = track([[103.801, 1.301], [103.802, 1.302]], 1700100000000);
    const cache = compute({ tracks: [first] });
    const result = compute({ tracks: [second], cache });
    const full = compute({ tracks: [first, second] });
    assert.ok(Math.abs(result.totalAreaKm2 - full.totalAreaKm2) < 1e-8);
    assert.equal(Object.keys(result.dailyExplored).length, 2);
});

test('worker fails explicitly on malformed paths instead of dropping tracks', () => {
    assert.throws(() => compute({ tracks: [{ encodedPath: '~' }] }), /Invalid polyline/);
});

test('2000 segments finish without losing explored centers', () => {
    const tracks = Array.from({ length: 2000 }, (_, i) => {
        const x = 103.8 + (i % 50) * 0.0005, y = 1.3 + Math.floor(i / 50) * 0.0005;
        return track([[x, y], [x + 0.0002, y + 0.0002]]);
    });
    const result = compute({ tracks });
    assert.ok(result.totalAreaKm2 > 0);
    assert.ok(turf.booleanPointInPolygon([103.8, 1.3], result.globalExplored));
    assert.ok(turf.booleanPointInPolygon([103.8245, 1.3195], result.globalExplored));
});

const resident = job => worker.updateResidentGeometry(job, () => {});

test('resident updates remove the old day when an existing id crosses midnight', () => {
    const first = { ...track([[103.8, 1.3], [103.801, 1.301]], Date.UTC(2026, 9, 19, 23, 59, 50)), id: 'same' };
    const updated = { ...first, timestamp: Date.UTC(2026, 9, 20, 0, 0, 10) };
    resident({ mode: 'reset', token: 10, tracks: [first] });
    const result = resident({ mode: 'update', token: 10, tracks: [updated] });
    assert.equal(result.dailyPatch['2026-10-19'], null);
    assert.ok(result.dailyPatch['2026-10-20']);
    const expected = compute({ tracks: [updated] });
    assert.ok(Math.abs(result.totalAreaKm2 - expected.totalAreaKm2) < 1e-8);
    assert.equal(result.globalFog, null);
});

test('resident extension sends only changed days and matches a rebuild', () => {
    const first = { ...track([[103.8, 1.3], [103.801, 1.301]]), id: 'extend' };
    const other = { ...track([[104, 1.5], [104.001, 1.501]], 1700100000000), id: 'other' };
    resident({ mode: 'reset', token: 11, tracks: [first, other] });
    const updated = { ...first, encodedPath: encode([[103.8, 1.3], [103.801, 1.301], [103.802, 1.302]]) };
    const result = resident({ mode: 'update', token: 11, tracks: [updated] });
    assert.equal(Object.keys(result.dailyPatch).length, 1);
    assert.equal(result.dailyExplored, undefined);
    const expected = compute({ tracks: [updated, other] });
    assert.ok(Math.abs(result.totalAreaKm2 - expected.totalAreaKm2) < 1e-7);
});

test('resident rejects stale tokens and does not commit failed updates', () => {
    const first = { ...track([[103.8, 1.3], [103.801, 1.301]]), id: 'valid' };
    const initial = resident({ mode: 'reset', token: 12, tracks: [first] });
    assert.throws(() => resident({ mode: 'update', token: 11, tracks: [] }), /token mismatch/);
    assert.throws(() => resident({ mode: 'update', token: 12, tracks: [{ ...first, encodedPath: '~' }] }), /Invalid polyline/);
    const result = resident({ mode: 'update', token: 12, tracks: [first] });
    assert.ok(Math.abs(result.totalAreaKm2 - initial.totalAreaKm2) < 1e-8);
    assert.equal(resident({ mode: 'update', token: 12, tracks: [] }).unchanged, true);
});

test('resident replacement removes the previous footprint', () => {
    const first = { ...track([[103.8, 1.3], [103.801, 1.301]]), id: 'replace' };
    const updated = { ...track([[104, 1.5], [104.001, 1.501]]), id: 'replace' };
    resident({ mode: 'reset', token: 13, tracks: [first] });
    const result = resident({ mode: 'update', token: 13, tracks: [updated] });
    assert.equal(turf.booleanPointInPolygon([103.8, 1.3], result.globalExplored), false);
    assert.equal(turf.booleanPointInPolygon([104, 1.5], result.globalExplored), true);
});
