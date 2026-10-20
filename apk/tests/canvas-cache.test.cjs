const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/web/index.html'), 'utf8');
const source = html.slice(html.indexOf('    const FogCanvasLayer ='),
    html.indexOf('    function buildTimeline()'));

function setup() {
    const layers = new Set();
    const context = {
        L: {
            setOptions(layer, options) { Object.assign(layer.options, options); },
            Layer: { extend(methods) {
                function Layer(...args) { this.options = {}; methods.initialize.apply(this, args); }
                Object.assign(Layer.prototype, methods, {
                    addTo() { layers.add(this); return this; }
                });
                return Layer;
            } }
        },
        performance, map: { getZoom: () => 10, removeLayer: layer => layers.delete(layer) },
        appData: { sourceOfTruth: { tracks: [] }, renderCache: { globalExplored: { type: 'Polygon' } } },
        window: { __fogTrackingDebugCompact: true },
        fogLayer: null, exploredLayer: null, savedTrackDebugLayer: null,
        isAutoRecording: false, isEditMode: false, viewTodayOnly: false,
        getDisplayGeometry: (_key, geometry) => geometry,
        getRenderSimplifyTolerance: () => 0,
        reportDebugEvent() {}
    };
    vm.createContext(context);
    vm.runInContext(source, context);
    return { context, layers };
}

test('normal refresh reuses both layer objects without detaching the fog', () => {
    const { context, layers } = setup();
    context.renderLayers();
    const fog = context.fogLayer, explored = context.exploredLayer;
    fog._projectedZoom = 10;
    context.renderLayers();
    assert.equal(context.fogLayer, fog);
    assert.equal(context.exploredLayer, explored);
    assert.equal(fog._projectedZoom, 10);
    assert.equal(layers.has(fog), true);
    context.appData.renderCache.globalExplored = { type: 'MultiPolygon' };
    context.renderLayers();
    assert.equal(context.fogLayer, fog);
    assert.equal(fog._projectedZoom, null);
});

test('timeline fog never reuses the separate explored layer', () => {
    const { context } = setup();
    context.renderLayers();
    const explored = context.exploredLayer;
    const history = context.createExploredCanvasLayer({ type: 'Polygon' }, 10, {
        cacheKey: 'fog', fill: true, stroke: false
    });
    assert.equal(history, context.fogLayer);
    assert.notEqual(history, explored);
    assert.equal(explored.options.stroke, true);
});

test('edit transition removes fog and returning restores the same layer', () => {
    const { context, layers } = setup();
    context.renderLayers();
    const fog = context.fogLayer;
    context.isEditMode = true;
    context.renderLayers();
    assert.equal(layers.has(fog), false);
    context.isEditMode = false;
    context.renderLayers();
    assert.equal(context.fogLayer, fog);
    assert.equal(layers.has(fog), true);
});
