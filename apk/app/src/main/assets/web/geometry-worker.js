/* Runs only in a Worker: no geometry boolean operations on the map thread. */
if (typeof turf === 'undefined') importScripts('./vendor/turf/turf.min.js');

function decodePath(str) {
    let index = 0, lat = 0, lng = 0;
    const points = [];
    function delta() {
        let result = 0, shift = 0, byte;
        do {
            if (index >= str.length || shift > 30) throw new Error('Invalid polyline');
            byte = str.charCodeAt(index++) - 63;
            result |= (byte & 31) << shift;
            shift += 5;
        } while (byte >= 32);
        return result & 1 ? ~(result >> 1) : result >> 1;
    }
    while (index < str.length) {
        lat += delta();
        lng += delta();
        points.push([lng / 1e5, lat / 1e5]);
    }
    return points;
}

function unionFeatures(left, right) {
    try {
        return turf.union(left, right);
    } catch (error) {
        // Near-coincident buffer edges can break polygon-clipping's sweep tree.
        // Retry at roughly millimetre precision without changing stored track coordinates.
        return turf.union(
            turf.truncate(left, { precision: 8, coordinates: 2 }),
            turf.truncate(right, { precision: 8, coordinates: 2 })
        );
    }
}

function unionBalanced(features) {
    let level = features.filter(Boolean);
    while (level.length > 1) {
        const next = [];
        for (let i = 0; i < level.length; i += 2) {
            next.push(level[i + 1] ? unionFeatures(level[i], level[i + 1]) : level[i]);
        }
        level = next;
    }
    return level[0] || null;
}

function computeGeometry(job, progress) {
    const world = turf.polygon([[[-180,-90],[180,-90],[180,90],[-180,90],[-180,-90]]]);
    const cache = job.cache || {};
    const masks = [];
    const dailyMasks = {};
    const tracks = job.tracks || [];
    for (let i = 0; i < tracks.length; i++) {
        const track = tracks[i];
        const coords = decodePath(track.encodedPath || '');
        if (coords.length) {
            const mask = turf.buffer(coords.length === 1 ? turf.point(coords[0]) : turf.lineString(coords),
                Number(track.brushRadiusKm) || 0.02, { units: 'kilometers' });
            if (mask) {
                masks.push(mask);
                const day = new Date(track.timestamp || Date.now()).toISOString().split('T')[0];
                (dailyMasks[day] ||= []).push(mask);
            }
        }
        if ((i + 1) % 32 === 0) progress(i + 1, tracks.length);
    }
    // A balanced reduction avoids repeatedly unioning every small segment with the whole world.
    const added = unionBalanced(masks);
    const explored = cache.globalExplored && added
        ? unionFeatures(cache.globalExplored, added) : added || cache.globalExplored || null;
    const daily = { ...(cache.dailyExplored || {}) };
    Object.entries(dailyMasks).forEach(([day, parts]) => {
        const part = unionBalanced(parts);
        daily[day] = daily[day] ? unionFeatures(daily[day], part) : part;
    });
    progress(tracks.length, tracks.length);
    return {
        globalExplored: explored,
        globalFog: job.omitFog ? null : (explored ? turf.difference(world, explored) : world),
        dailyExplored: daily,
        totalAreaKm2: explored ? turf.area(explored) / 1e6 : 0
    };
}

let resident = null;

function updateResidentGeometry(job, progress) {
    if (job.mode === 'reset') {
        const result = computeGeometry({ tracks: job.tracks, omitFog: true }, progress);
        resident = {
            token: job.token,
            tracks: new Map(job.tracks.map(track => [track.id, track])),
            globalExplored: result.globalExplored,
            dailyExplored: result.dailyExplored
        };
        return { ...result, token: job.token, full: true };
    }
    if (!resident || resident.token !== job.token) throw new Error('Geometry cache token mismatch');
    const nextTracks = new Map(resident.tracks);
    const changedDays = new Set();
    const additions = [];
    const dailyAdditions = {};
    let appendOnly = true;
    const dayOf = track => new Date(track.timestamp).toISOString().slice(0, 10);
    for (const track of job.tracks) {
        const previous = nextTracks.get(track.id);
        const day = dayOf(track);
        changedDays.add(day);
        if (previous) {
            changedDays.add(dayOf(previous));
            // An extended line preserves its old footprint. Replacements do not.
            if (dayOf(previous) !== day || previous.brushRadiusKm !== track.brushRadiusKm ||
                !track.encodedPath.startsWith(previous.encodedPath)) appendOnly = false;
        }
        nextTracks.set(track.id, track);
        const coords = decodePath(track.encodedPath);
        if (coords.length) {
            const mask = turf.buffer(coords.length === 1 ? turf.point(coords[0]) : turf.lineString(coords),
                Number(track.brushRadiusKm) || 0.02, { units: 'kilometers' });
            if (mask) {
                additions.push(mask);
                (dailyAdditions[day] ||= []).push(mask);
            }
        }
    }
    if (!job.tracks.length) return { token: job.token, unchanged: true };
    const daily = { ...resident.dailyExplored };
    if (appendOnly) {
        for (const [day, masks] of Object.entries(dailyAdditions)) {
            const added = unionBalanced(masks);
            daily[day] = daily[day] ? unionFeatures(daily[day], added) : added;
        }
    } else {
        const affected = [...nextTracks.values()].filter(track => changedDays.has(dayOf(track)));
        const rebuilt = computeGeometry({ tracks: affected, omitFog: true }, progress);
        for (const day of changedDays) {
            if (rebuilt.dailyExplored[day]) daily[day] = rebuilt.dailyExplored[day];
            else delete daily[day];
        }
    }
    const added = unionBalanced(additions);
    const explored = appendOnly
        ? (resident.globalExplored && added ? unionFeatures(resident.globalExplored, added) : added || resident.globalExplored)
        : unionBalanced(Object.values(daily));
    const patch = {};
    for (const day of changedDays) patch[day] = daily[day] || null;
    // Publish only after every boolean operation succeeds.
    resident = { token: job.token, tracks: nextTracks, globalExplored: explored, dailyExplored: daily };
    return {
        token: job.token, full: false, globalExplored: explored, globalFog: null,
        dailyPatch: patch, totalAreaKm2: explored ? turf.area(explored) / 1e6 : 0
    };
}

self.onmessage = ({ data }) => {
    try {
        const progress = (done, total) => self.postMessage({ id: data.id, done, total });
        const result = data.job.mode
            ? updateResidentGeometry(data.job, progress)
            : computeGeometry(data.job, progress);
        self.postMessage({ id: data.id, result });
    } catch (error) {
        self.postMessage({ id: data.id, error: String(error) });
    }
};
