# [OPEN] debug-large-data-mark-lag

## Symptoms
- 大数据量下，点击“完成标记”后仍会卡顿一会儿。
- 用户希望点击版本号即可复制诊断日志，不再要求长按。

## Hypotheses
1. 完成标记后仍然会触发多次全量 `renderLayers()` / `buildTimeline()`，在大数据下叠加造成体感卡顿。
2. 完成标记路径上仍存在一次或多次不必要的 `saveData()`，即使 APK 模式已跳过缓存写入，也会有深拷贝或额外调度成本。
3. 结束标记后的“恢复原生记录”与页面退出编辑态存在时序竞争，导致重复刷新或重复保存。
4. 大数据下 `__fogApplyNormalizedArchiveToPage()` 或 related 全量 page state 替换仍可能在完成标记后被触发。
5. 版本号长按复制本身没问题，但交互发现成本高；改成点击即可复制不会影响调试能力。

## Current evidence
- 已有日志证明 APK 模式下曾出现 `web_save_data_storage_done = 8582ms`，说明大对象缓存路径会放大卡顿。
- 新版已跳过 APK 环境缓存写入，但用户仍反馈“大数据下完成标记后卡顿一会儿”，说明还存在额外的大数据路径。

## Next step
- 对完成标记后的退出编辑、恢复原生记录、二次 render/save 路径继续加最小耗时打点，再基于证据做最小修复。
