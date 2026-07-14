# [OPEN] foreground-after-background-stall

## Symptom
- App 在后台放一段时间（约 20 分钟）后切回前台，仍然会卡很久。
- 该现象需要与“首次冷启动 hydration 补缓存导致的首次慢启动”区分。

## Scope
- `apk/app/src/main/assets/web/index.html`
- `apk/app/src/main/java/io/shayne/fogvisitor/MainActivity.kt`
- `apk/app/src/main/java/io/shayne/fogvisitor/TrackingForegroundService.kt`
- `apk/app/src/main/java/io/shayne/fogvisitor/NativeTrackStore.kt`

## Goal
- 判断前台恢复卡顿是否由后台期间新增轨迹导致的整份同步、区域统计、图层重建或其他恢复逻辑触发。

## Findings
- 现有日志已证明：后台期间原生轨迹从 585 增长到 586 后，页面会在恢复活跃时通过前台 JS 轮询发现 `trackCount/latestTimestamp` 变化。
- `syncNativeArchiveIfAdvanced()` 之前会把这次变化统一调度成 `web_native_hydration_start`，即使只新增 1 条轨迹，也会整份 archive 重建。
- 这轮整份 hydration 在实际日志里耗时约 13.78 秒，是前台恢复卡顿的主因。
- 之前没有看到 blocking overlay，不是漏了，而是该路径使用了 `background=true`，故意压掉了全局阻断通知。

## Fix
- 新增按时间戳获取“新增轨迹”的原生桥接接口，给前台恢复提供增量同步能力。
- 对“仅少量新增轨迹”的场景，改成只增量应用新增轨迹，不再整份 hydration。
- 当前台已可见时，不再沿用后台模式压掉 overlay，而是显示进度。
