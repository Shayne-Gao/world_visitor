# [OPEN] large-archive-stall

## Symptom
- 导入一个 500 多条轨迹的存档后，App 无论是冷启动还是从后台切回前台，都会卡一段时间才有反应。

## Scope
- `apk/app/src/main/assets/web/index.html`
- `apk/app/src/main/java/io/shayne/fogvisitor/MainActivity.kt`
- `apk/app/src/main/java/io/shayne/fogvisitor/NativeTrackStore.kt`

## Constraints
- 在拿到证据前，不先改业务逻辑。
- 优先确认卡顿发生在：
  - 原生 truth 导出/同步
  - Web 侧重建 render cache
  - 区域统计嗅探
  - 图层渲染 / 调试层渲染

## Next Step
- 先核查冷启动与前台恢复时的重活路径和是否存在“每次都全量重建”的实现。

## Findings
- 冷启动日志已经直接证明：`web_native_hydration_done.durationMs = 9104`，主耗时发生在 native archive hydration。
- 当前 Web 本地缓存可能仍是旧的空世界，但原生 truth 已经增长到 500+ 条轨迹；启动时页面发现不一致，于是触发全量 hydration。
- `DataManager.saveData()` 在 `native_truth_mode` 下直接跳过本地缓存写入，导致 hydration 成果没有稳定回写到本地缓存，下次冷启动还会继续补课。
- 前台检测到原生新增轨迹时，`syncNativeArchiveIfAdvanced()` 之前会直接做 `exportNativeArchiveJson + normalize + apply`，这会把“后台新增记录”的代价直接压到前台恢复瞬间。
- `renderLayers()` 里的正式轨迹调试层对大存档会放大渲染成本，虽然不是 9 秒主因，但会继续加重卡顿。

## Fix Direction
- hydration 完成后回写可渲染本地缓存，避免下次冷启动重复补课。
- 前台检测到原生新增轨迹时，不再立刻整份同步，改成调度后台 hydration。
- 大存档下收紧正式轨迹调试层阈值。
