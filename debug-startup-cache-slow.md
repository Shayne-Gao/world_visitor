# [OPEN] startup-cache-slow

## Symptom
- Android APK 启动时长时间停留在“准备数据”，打开很慢。
- 用户提供的 `v0.1.76` 诊断日志显示：本地主存读取很快，但后续链路明显拖慢首屏可用时间。

## Scope
- APK 主线
- `apk/app/src/main/assets/web/index.html`
- `apk/app/src/main/java/io/shayne/fogvisitor/`

## Initial Evidence
- `web_init_data_loaded.loadMs = 51`
- `web_heavy_init_done.totalMs = 97`
- `web_native_hydration_done.durationMs = 16621`
- 诊断中 `trackCount = 557`

## Hypotheses
- H1: 首次打开慢的主要耗时不在 Web 本地缓存读取，而在原生 `native hydration` 把大量轨迹重新回灌到 Web。
- H2: 页面虽然已有本地快照，但启动时仍会无条件重建图层或重复渲染，导致“缓存命中但仍慢”。
- H3: 原生状态轮询或同步摘要逻辑在启动阶段触发了多次重渲染，放大了可见卡顿。
- H4: “准备数据”遮罩的关闭时机依赖原生 hydration 完成，而不是依赖本地缓存已可用，导致用户感知上被 16s 阻塞。
- H5: 当前缓存只缓存原始数据，没有缓存“可直接渲染的派生结果”，因此每次打开仍要做一遍昂贵的恢复或对账。

## Next Step
- 先核对前端初始化、原生 hydration、缓存/快照使用与阻塞遮罩的代码路径。
- 在拿到证据前不修改业务逻辑。

## Findings
- 日志显示主存读取很快：`web_init_data_loaded.loadMs = 51ms`。
- 日志显示 Web 首轮初始化很快：`web_heavy_init_done.totalMs = 97ms`。
- 真正热点是原生回灌：`web_native_hydration_done.durationMs = 16621ms`。
- `index.html` 中 `hydrateNativeRenderCacheAfterBoot()` 会在 APK 模式下显示“正在准备地图数据”遮罩，并调用 `AndroidBridge.exportNativeArchiveJson()` 后重建探索区域。
- `NativeTrackStore.exportArchiveJson()` 当前仅导出 `metadata + sourceOfTruth.tracks`，不导出 `renderCache`。
- 因为原生导出缺少 `renderCache`，Web 侧 `normalizeNativeArchiveForWeb()` / `rebuildRenderCacheProgressively()` 会从全部轨迹重新 buffer/union，轨迹量到 `557` 条时就会形成秒级到十几秒级耗时。

## Preliminary Conclusion
- H1 confirmed: 慢点主要在 native hydration，而不是本地缓存读取。
- H4 confirmed: 阻塞遮罩与 native hydration 生命周期绑定，导致用户感知被 16s 阻塞。
- H5 likely confirmed: 当前缺的是“可直接渲染缓存”或“跳过不必要 hydration”的策略，不是缺原始数据缓存。

## Implemented Fix
- APK 启动时优先检查本地 `fog_of_world_data_v5` 是否已经包含可直接渲染的 `renderCache`。
- 如果本地缓存与原生摘要一致，则直接使用本地缓存启动，并跳过阻塞式 native hydration。
- 如果原生摘要比本地缓存更新，则先用本地缓存完成首屏显示，再后台执行 native hydration，不再用“准备数据”遮罩阻塞首屏。
- 原生增量同步改为无阻塞应用，避免同步时再次弹出全屏遮罩。
