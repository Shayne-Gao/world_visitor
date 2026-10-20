const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const root = path.join(__dirname, '../app/src/main/java/io/shayne/fogvisitor/storage');
const database = fs.readFileSync(path.join(root, 'FogVisitorDatabase.kt'), 'utf8');
const dao = fs.readFileSync(path.join(root, 'TrackDao.kt'), 'utf8');
const sql = prefix => {
    const match = database.match(new RegExp(`db\\.execSQL\\("(${prefix}[^"]+)"`));
    assert.ok(match, `Migration SQL missing: ${prefix}`);
    return match[1];
};
const query = method => {
    const pattern = new RegExp('@Query\\("([^"]+)"\\)\\s+fun ' + method + '\\(');
    const match = dao.match(pattern);
    assert.ok(match, `DAO SQL missing: ${method}`);
    return match[1];
};
const initial = `
CREATE TABLE track_segments(id TEXT NOT NULL PRIMARY KEY, timestamp INTEGER NOT NULL,
source TEXT NOT NULL, brush_radius_km REAL NOT NULL, encoded_path TEXT NOT NULL, bbox_json TEXT);
CREATE TABLE draft_points(sequence INTEGER NOT NULL PRIMARY KEY, lng REAL NOT NULL, lat REAL NOT NULL);
INSERT INTO track_segments VALUES('old', 9999999999999, 'auto', 0.02, 'path', NULL);
INSERT INTO draft_points VALUES(0, 103.8, 1.3);
${sql('ALTER TABLE')};
${sql('CREATE TABLE IF NOT EXISTS')};
${sql('INSERT OR IGNORE').replace('?', "'epoch-a'")};
`;
function run(extra) {
    return execFileSync('/usr/bin/sqlite3', ['-batch', ':memory:'], {
        input: `.bail on\n${initial}\n${extra}`, encoding: 'utf8'
    }).trim();
}

test('version one migration retains both tracks and draft coordinates', () => {
    assert.equal(run(`
SELECT id, timestamp, encoded_path, revision FROM track_segments;
SELECT sequence, lng, lat FROM draft_points;
SELECT epoch, revision, reset_revision FROM archive_sync;
`), 'old|9999999999999|path|0\n0|103.8|1.3\nepoch-a|1|1');
});

test('revision query finds current-time inserts after a future-time archive', () => {
    const result = run(`
BEGIN;
${query('advanceRevision')};
INSERT INTO track_segments VALUES('new', 1000, 'auto', 0.02, 'new-path', NULL,
(SELECT revision FROM archive_sync WHERE id = 1));
COMMIT;
${query('getTracksAfterRevision').replace(':afterRevision', '1')};
`);
    assert.equal(result, 'new|1000|auto|0.02|new-path||2');
});

test('transaction rollback keeps the prior trajectory draft and revision', () => {
    assert.equal(run(`
BEGIN;
${query('advanceRevision')};
${query('markResetRevision')};
${query('deleteAllTracks')};
DELETE FROM draft_points;
ROLLBACK;
SELECT COUNT(*) FROM track_segments;
SELECT COUNT(*) FROM draft_points;
SELECT revision, reset_revision FROM archive_sync;
`), '1\n1\n1|1');
});

test('replacement advances the reset watermark even when count is unchanged', () => {
    assert.equal(run(`
BEGIN;
${query('advanceRevision')};
${query('markResetRevision')};
${query('deleteAllTracks')};
INSERT INTO track_segments VALUES('replacement', 500, 'auto', 0.02, 'replacement-path', NULL, 2);
COMMIT;
SELECT COUNT(*) FROM track_segments;
SELECT revision, reset_revision FROM archive_sync;
`), '1\n2|2');
});
