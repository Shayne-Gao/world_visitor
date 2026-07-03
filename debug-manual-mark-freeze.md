# [OPEN] debug-manual-mark-freeze

## Symptoms
- 在 APK 中手动标记后点击“完成标记”，页面会卡很久。
- 用户体感是点击后长时间无响应，之后才恢复。
- 该问题影响手动标记基本可用性，属于高优先级交互阻塞问题。

## Current hypotheses
1. `processTrackAndSave()` 中的 Turf 几何计算在主线程执行时间过长。
2. `DataManager.saveData()` 对大型 `appData/renderCache` 的深拷贝与 localforage 持久化造成明显阻塞。
3. 保存后的原生桥接同步 `AndroidBridge.importNativeArchiveJson(...)` 仍在主线程上阻塞。
4. `renderLayers()` / `buildTimeline()` 在“完成标记”路径中触发了重渲染峰值，导致长时间卡顿。
5. 偶发卡顿与数据量相关，只有在当前存档足够大时才会放大。

## Evidence plan
- 对 `endActionBtn` 点击进入、`processTrackAndSave()`、`saveData()`、原生同步、`renderLayers()` 打阶段耗时日志。
- 在 APK 中重现场景，让用户导出运行时日志。
- 根据具体耗时分布决定最小修复点，而不是继续盲改。

## Status
- Waiting for instrumentation.
