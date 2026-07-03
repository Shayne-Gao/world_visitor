# [OPEN] debug-startup-empty-lag

## Symptoms
- `v0.1.61` 启动后，页面先以空数据进入，再在大约 19 秒后才同步出真实轨迹。
- 点击“开始标记”后，进入可绘制状态前有明显迟滞。

## Hypotheses
1. WebView 启动时未能立刻从原生 truth 读取到 Room 中的真实数据，先走了 `empty` 路径。
2. 原生到页面的增量同步定时器仍然是主要刷新来源，导致首屏真实数据要等到下一个轮询周期才出现。
3. 进入标记前暂停原生记录或随后的增量同步与页面渲染冲突，造成交互迟滞。
4. `saveData()` 的本地缓存写入仍可能在某些路径阻塞 UI，放大“点击标记后才能开始画”的等待感。
5. 页面在编辑态下仍被原生增量同步打断，导致标记模式切入不稳定。

## Current evidence
- 首屏 `web_init_data_loaded.source = empty`，`trackCount = 0`
- 约 19 秒后出现 `web_native_archive_incremental_sync.trackCount = 579`
- 手动标记进入事件与后续可绘制之间存在体感迟滞，但现有日志缺少“暂停原生记录耗时”和“进入绘制就绪时刻”的专门打点

## Next step
- 先基于现有日志确认根因优先级，再决定是否增加更细粒度打点。
