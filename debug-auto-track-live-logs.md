# [OPEN] auto-track-live-logs

## Symptom
- 用户不相信当前自动记录修复结果。
- 需要把自动记录的每一步实时日志直接打到屏幕下方现有“正在记录”浮窗里，滚动显示。
- 重点包括：是否收到新定位点、是否进入 draft、是否触发 4 点 checkpoint、是否因为已探索区域而拒绝落库、是否合并到上一段、页面图层是否已刷新。

## Hypotheses
- H1: 自动记录服务实际上有收到定位点，但点被服务层过滤掉了，因此 draft 和 segment 都不增长。
- H2: 定位点已经进入 draft，但在 `checkpointDraftToTrackIfNeeded()` 或 `persistTrackCandidate()` 被拒绝，导致页面看起来没记录。
- H3: 原生已经成功写入轨迹，但页面增量同步或图层刷新没有及时反映，所以用户看到“没有自动记录”。
- H4: 服务状态显示“正在记录”，但连续定位回调本身已经中断，只剩下状态没有真实位置流。
- H5: 候选轨迹确实形成了，但由于已探索区域去重或自动段合并，最终没有新增段数，造成认知偏差。

## Instrumentation Goal
- 在不改变业务语义的前提下，把自动记录关键链路实时输出到屏幕下方 tracking panel。
- 输出内容覆盖：服务启动、定位回调、过滤原因、draft 点数、checkpoint 触发、persist 结果、页面增量同步、页面图层刷新。

## Next Step
- 第一处代码修改只增加 instrumentation 和前端日志展示，不改业务判定逻辑。

## Instrumentation Implemented
- `trackingStatusPanel` 已新增滚动日志区域，用于直接显示自动记录链路的实时事件。
- 原生 `TrackingForegroundService`、`MainActivity`、`NativeTrackStore` 的关键 debug event 会写入原生环形缓冲区。
- 页面在轮询 `getNativeTrackingStatus()` 时会把原生日志刷到浮窗，同时把关键 Web 事件也同步打到同一个日志区域。
