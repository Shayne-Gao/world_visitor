# [OPEN] debug-apk-ui-storage-regression

## Symptoms
- APK 打开仍有明显卡顿，用户感知约 3-10 秒。
- 图标有阶段性恢复，但当前仍存在显示异常回归。
- 导入/合并存档后，地图上的记录不显示，且缺少稳定成功/失败提示。

## Current hypotheses
1. `file://` 下本地图标字体 URL 在 Android WebView 中仍存在解析或访问限制。
2. 导入逻辑只更新了原生存档，没有稳定刷新网页内 `appData/renderCache`，导致地图不显示。
3. 导入反馈 UI 被 reload、异常吞掉或执行时机错误，导致用户看不到结果。
4. 首屏卡顿主要来自页面初始化时的同步重计算，而不是外部 CDN。

## Evidence plan
- 为 APK WebView 注入启动耗时打点，记录关键阶段耗时。
- 为原生导入链路记录：文件读取、解析、写入原生存档、同步到页面、页面刷新结果。
- 为图标加载链路记录：CSS 注入完成、字体可用性检测、关键图标是否被解析为方块。

## Status
- Evidence collected:
  - Empty-data startup is fast: `web_init_data_loaded loadMs=15/165`, `web_heavy_init_done totalMs=27/180`.
  - This falsifies the “page shell/CDN alone is the main blocker” hypothesis.
  - Confirmed likely root cause A: startup slowness comes from rebuilding native archive into renderable cache when track data exists.
  - Confirmed likely root cause B: import success path wrote raw native archive to page storage without stable renderable application path.
- Moving to minimal fix based on evidence.
