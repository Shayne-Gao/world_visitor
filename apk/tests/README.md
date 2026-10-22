# APK 回归验证

本目录测试的是 APK 原生逻辑及打包进 APK 的页面资源，不是仓库根目录的旧 Web 版本。

## 自动检查

- 在 `apk/` 下执行 `./gradlew testDebugUnitTest lintDebug assembleDebug`。
- 在仓库根目录执行 `node --test apk/tests/geometry-worker.test.cjs`。
- 执行 `node --test apk/tests/native-sync.test.cjs apk/tests/canvas-cache.test.cjs apk/tests/storage-sql.test.cjs`。
- `storage-sql.test.cjs` 使用本机 `/usr/bin/sqlite3` 验证源码中的迁移和查询 SQL，不等价于 Android Room 仪器测试；原生 JVM 测试另覆盖 revision 重置规则及 UTC 日期边界。
- 常驻 Worker 回归覆盖同 ID 延长、跨日迁移、路径替换、旧 token 拒绝和失败后缓存保持；同步回归覆盖未来业务时间、同数量更新、重置和过期结果。
- `map-browser.test.cjs` 只检查 APK 内嵌资源的文件 URL 加载、Worker、雾层像素和投影复用，不作为 Android 流畅度或后台保活的验收结果。
- `TrackingFlowTest` 使用 Robolectric API 28/35 和真实 Room 数据库执行 9 个场景（共 18 次），仅 mock GMS 定位客户端。执行：`./gradlew :app:testDebugUnitTest --tests io.shayne.fogvisitor.TrackingFlowTest`。测试存档位于隔离测试环境，不写入用户手机。
- 原生回放覆盖 9 米连续小步累积、原地抖动有界草稿、批量排序/重复/精度/速度过滤、2 分钟/1km 断轨、暂停 UI、Activity 退后台后屏幕关闭、停止旧回调、服务重建和 watchdog 订阅失败重试。屏幕状态由 shadow 设置，回调由测试注入，不能据此证明系统在 Doze 下仍交付回调。
- `zoom-browser.test.cjs` 驱动 APK 自带 Leaflet 的双指处理器，逐帧断言放大/缩小、锚点和位图复用。设置 `PLAYWRIGHT_PATH` 指向已有 `playwright-core` 后执行 `node apk/tests/zoom-browser.test.cjs`；它不测 Android WebView 性能。
- 本轮调试的可选日志环境变量：`FOG_DEBUG_URL=http://127.0.0.1:7777/event`、`DEBUG_RUN_ID=pre-fix` 或 `post-fix`。未设置日志地址时，测试不依赖调试服务器。

## 111 自查结果

修复前，13 个每隔 10 秒、相距 9 米的有效点全部被服务接受，但存储反复丢弃短段，累计 108 米仍为 0 条轨迹。修复后保留全部起终点并保存轨迹，原地小范围抖动仍不保存为轨迹。双指缩放修复前地图比例为 1.3/1.7/2.2 时雾层始终为 1；修复后逐帧变换与地图一致。

这些自动回放可以承担后续业务回归，不需要每次让使用者外出。以下真机场景用于系统调度和厂商限制验收，不能由 JVM 回放替代。

## 必须使用 Android 真机的检查

测试前导出备份，不清除现有数据。记录手机型号、Android 版本、精确/后台定位权限、电池限制及前台服务通知是否存在。

1. 打开 APK 开始记录，直接锁屏，移动 15 分钟，解锁后检查轨迹与日志。
2. 同样路线与时长，先按 Home 切到其他 App，再锁屏；比较两种路径的采点间隔。
3. 断记时先记录时间和常驻通知是否消失，再回到 APK。重点查看 `service_subscription_failed`、`service_location_watchdog_triggered`、`service_location_persisted`；watchdog 包含设备休眠、电池优化和后台定位权限状态。
4. 点击“停并关”，确认服务通知消失，不再产生定位记录；再次手动打开 APK 应恢复自动记录。不要把系统“强行停止”或用户主动停并关后的停止采点当作保活失败。
5. 保留约 2000 段真实存档，测试导入、冷启动、记录中新增点时拖图、连续拖图和缩放。雾层不能闪成全亮；导入时不能把“已有轨迹数量”误当作“几何计算已完成”。
6. 验证手动标记、导入替换、合并导入后面积和轨迹不丢失，旧的异步计算不能覆盖刚导入的数据。
7. 从 108 覆盖安装 110 后，确认原有轨迹、草稿与设置仍在；初次启动会重建新版绘图缓存。不要直接降级安装旧包读取新版数据库，先保留可恢复的导出备份。
8. 断记诊断同时检查 `lastCallbackAt`、`lastPersistedAt`、`lastServiceStartAt`、`lastExplicitStopAt`；诊断事件保留最近 120 条，磁盘快照通常每 30 秒写入，失败、停止和 watchdog 事件提前触发写入。异常终止仍可能丢失最后一小段尚未写入的日志。

没有实机复现和日志前，不能断言已解决所有厂商的锁屏/后台限制；单元测试不覆盖 Android 服务调度、Doze 或厂商省电策略。
