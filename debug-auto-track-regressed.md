# [OPEN] auto-track-regressed

## Symptom
- 用户反馈：之前版本还会自动记录，当前 `v0.1.82` 改完之后完全不会自动记录。
- 同时用户反馈：屏幕下方的日志框也没有了。

## Scope
- `apk/app/src/main/assets/web/index.html`
- `apk/app/src/main/java/io/shayne/fogvisitor/MainActivity.kt`
- `apk/app/src/main/java/io/shayne/fogvisitor/TrackingForegroundService.kt`
- `apk/app/src/main/java/io/shayne/fogvisitor/NativeTrackStore.kt`

## Known Context
- 本次回归发生在一次集中修复多个 P0 缺口之后。
- 用户明确表示：之前版本“至少还会自动记录”，说明存在明显回归。

## Next Step
- 先检查 `v0.1.82` 中自动记录状态机、首屏状态轮询、日志面板显隐条件和渲染条件。
- 在证据明确前，不继续修改业务逻辑。

## Findings
- `v0.1.82` 中 `ensureAutoTrackingStarted()` 被改成了：只有 `shouldTrack = true` 且 `isTracking = false` 时才启动服务。
- 这会导致以前依赖“打开 App 自动续跑”的路径，在 `shouldTrack` 没有提前置真的情况下完全不启动自动记录服务。
- 页面里的 tracking panel 显隐又依赖 `status.isTracking || status.shouldTrack || __fogEditModeActive`，因此服务没起时，日志面板也一起被隐藏。

## Working Conclusion
- 自动记录回归的主根因是 `ensureAutoTrackingStarted()` 启动条件改错。
- 日志框“消失”不是独立问题，而是同一个状态机回归带出来的结果。

## Additional Finding
- `clearArchive()`、`replaceTracksJson()`、`importParsedArchive()` 这几条“重置地图 / 加载地图 / 替换轨迹”的原生入口，会把 `isTracking` 和 `shouldTrack` 一起写成 `false`。
- 这意味着用户只是重置地图或加载存档，也会顺手把正在进行的自动记录停掉，这属于不合理的状态耦合。
