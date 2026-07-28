package io.shayne.fogvisitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import io.shayne.fogvisitor.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var nativeTrackStore: NativeTrackStore
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingImportMergeMode = false
    private var importPickerActive = false
    private var pendingExportFileName = "fog_apk_export.json"

    private val foregroundPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                hasForegroundLocationPermission()

            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoOrigin = null
            pendingGeoCallback = null
            if (granted) {
                ensureAutoTrackingStarted()
                requestBackgroundLocationIfNeeded()
            }
        }

    private val backgroundPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            reportDebugEvent(
                "native_background_location_permission_result",
                mapOf("granted" to granted.toString())
            )
        }

    private val importArchiveLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val shouldHandle = importPickerActive
            importPickerActive = false
            if (uri != null && shouldHandle) {
                handleImportedArchiveUri(uri, pendingImportMergeMode)
            } else if (uri != null && !shouldHandle) {
                reportDebugEvent(
                    "native_import_result_ignored",
                    mapOf("reason" to "picker_not_active", "uri" to uri.toString())
                )
            }
        }

    private val exportArchiveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) {
                handleExportArchiveUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        nativeTrackStore = NativeTrackStore(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.statusBanner.visibility = View.GONE

        requestRuntimePermissions()
        setupWebView()
        ensureAutoTrackingStarted()
    }

    override fun onResume() {
        super.onResume()
        reportDebugEvent(
            "native_activity_resume",
            mapOf("hasFine" to hasForegroundLocationPermission().toString())
        )
        ensureAutoTrackingStarted()
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needRequest) {
            foregroundPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            requestBackgroundLocationIfNeeded()
        }
    }

    private fun ensureAutoTrackingStarted() {
        if (!hasForegroundLocationPermission()) return
        startNativeTrackingService()
    }

    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.setGeolocationEnabled(true)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val targetUrl = request?.url?.toString() ?: return false
                if (isTrustedWebUrl(targetUrl) || targetUrl == "about:blank") {
                    return false
                }
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!isTrustedWebUrl(url)) {
                    reportDebugEvent(
                        "native_untrusted_page_blocked",
                        mapOf("url" to (url ?: "null"))
                    )
                    return
                }
                //#region debug-point apk-ui-storage-regression-native-page-finished
                reportDebugEvent(
                    "native_page_finished",
                    mapOf("url" to (url ?: "null"))
                )
                //#endregion
                injectNativeArchiveSync(webView)
                binding.statusBanner.visibility = View.GONE
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (origin == null || callback == null) return
                if (!isTrustedWebOrigin(origin)) {
                    callback.invoke(origin, false, false)
                    return
                }

                if (hasForegroundLocationPermission()) {
                    callback.invoke(origin, true, false)
                    return
                }

                pendingGeoOrigin = origin
                pendingGeoCallback = callback
                foregroundPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
        webView.addJavascriptInterface(
            JsBridge(
                this,
                onStartTracking = { startNativeTrackingService() },
                onStopTracking = { stopNativeTrackingService() },
                onPickExport = { launchExportPicker() },
                onPickImportReplace = { launchImportPicker(false) },
                onPickImportMerge = { launchImportPicker(true) }
            ),
            "AndroidBridge"
        )

        webView.loadUrl("file:///android_asset/web/index.html")
    }

    private fun injectNativeArchiveSync(webView: WebView) {
        val script = """
            (async function () {
              if (!window.AndroidBridge || !window.localforage) return;
              if (window.__fogNativeSyncInstalled) return;
              window.__fogNativeSyncInstalled = true;

              const normalizeNativeArchiveForWeb = (archive) => {
                archive.metadata = archive.metadata || { totalAreaKm2: 0, createdAt: Date.now() };
                archive.sourceOfTruth = archive.sourceOfTruth || { tracks: [] };
                archive.renderCache = archive.renderCache || { globalFog: null, globalExplored: null, dailyExplored: {}, regionsCache: {} };
                archive.renderCache.dailyExplored = archive.renderCache.dailyExplored || {};
                archive.renderCache.regionsCache = archive.renderCache.regionsCache || {};

                if (archive.renderCache.globalFog || archive.renderCache.globalExplored) {
                  return archive;
                }

                let cumulativeExplored = null;
                const tracks = [...(archive.sourceOfTruth.tracks || [])].sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0));

                for (const track of tracks) {
                  if (!track || !track.encodedPath) continue;
                  let coords = PolylineUtil.decode(track.encodedPath);
                  if (!coords || !coords.length) continue;
                  const radius = track.brushRadiusKm || 0.02;
                  let mask;
                  try {
                    if (coords.length === 1) mask = turf.buffer(turf.point(coords[0]), radius, { units: 'kilometers' });
                    else mask = turf.buffer(turf.lineString(coords), radius, { units: 'kilometers' });
                    cumulativeExplored = cumulativeExplored ? turf.union(cumulativeExplored, mask) : mask;
                  } catch (e) {
                    console.warn('Failed to rebuild track mask', e, track);
                  }
                }

                archive.renderCache.globalExplored = cumulativeExplored;
                archive.renderCache.globalFog = cumulativeExplored
                  ? turf.difference(turf.polygon(worldCoords), cumulativeExplored)
                  : turf.polygon(worldCoords);
                archive.metadata.totalAreaKm2 = cumulativeExplored ? turf.area(cumulativeExplored) / 1000000 : 0;
                return archive;
              };
              window.__fogNormalizeNativeArchiveForWeb = normalizeNativeArchiveForWeb;

              const syncNativeArchiveToLocal = async () => {
                const nativeSummary = JSON.parse(AndroidBridge.getNativeArchiveSummary());
                const current = await localforage.getItem('fog_of_world_data_v5');
                const currentTrackCount = current?.sourceOfTruth?.tracks?.length || 0;
                const currentLatestTimestamp = (current?.sourceOfTruth?.tracks || []).reduce((max, t) => Math.max(max, t.timestamp || 0), 0);
                const currentHasRenderableCache = !!(current?.renderCache && (current.renderCache.globalFog || current.renderCache.globalExplored));

                const shouldReplaceLocal =
                  !current ||
                  nativeSummary.trackCount > currentTrackCount ||
                  nativeSummary.latestTimestamp > currentLatestTimestamp;

                if (currentHasRenderableCache && shouldReplaceLocal) {
                  window.__fogPendingNativeHydration = true;
                  window.__fogHydrateNativeInBackground = true;
                  return false;
                }

                if (!shouldReplaceLocal) {
                  return false;
                }

                const nativeArchive = normalizeNativeArchiveForWeb(JSON.parse(AndroidBridge.exportNativeArchiveJson()));
                if (shouldReplaceLocal) {
                  await localforage.setItem('fog_of_world_data_v5', nativeArchive);
                  return true;
                }
                return false;
              };

              try {
                await syncNativeArchiveToLocal();

                const installSaveHook = () => {
                  if (!window.DataManager || !window.DataManager.saveData || window.__fogSaveHookInstalled) return false;
                  const originalSave = window.DataManager.saveData.bind(window.DataManager);
                  window.DataManager.saveData = async function(data) {
                    const hookStartedAt = performance.now();
                    if (window.reportDebugEvent) {
                      window.reportDebugEvent('web_save_hook_entered', {
                        trackCount: String(data?.sourceOfTruth?.tracks?.length || 0)
                      });
                    }
                    await originalSave(data);
                    if (window.reportDebugEvent) {
                      window.reportDebugEvent('web_save_hook_cache_only_done', {
                        totalMs: Math.round(performance.now() - hookStartedAt),
                        trackCount: String(data?.sourceOfTruth?.tracks?.length || 0)
                      });
                    }
                  };
                  window.__fogSaveHookInstalled = true;
                  return true;
                };

                if (!installSaveHook()) {
                  const retry = setInterval(() => {
                    if (installSaveHook()) clearInterval(retry);
                  }, 500);
                }

                const bindNativeTrackingUi = () => {
                  const manualBtn = document.getElementById('manualDrawBtn');
                  const endBtn = document.getElementById('endActionBtn');
                  const autoBtn = document.getElementById('autoTrackBtn');
                  const endAutoBtn = document.getElementById('endAutoTrackBtn');
                  if (!manualBtn || !endBtn || !autoBtn || !endAutoBtn || window.__fogNativeTrackingUiBound) return false;

                  const formatLastPoint = (timestamp) => {
                    if (!timestamp) return '暂无';
                    try {
                      return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false });
                    } catch (_) {
                      return '暂无';
                    }
                  };

                  const syncNativeArchiveIfAdvanced = async () => {
                    try {
                      const summary = JSON.parse(AndroidBridge.getNativeArchiveSummary());
                      const currentTrackCount = Array.isArray(window.appData?.sourceOfTruth?.tracks)
                        ? window.appData.sourceOfTruth.tracks.length
                        : 0;
                      const currentLatestTimestamp = Math.max(
                        0,
                        ...(window.appData?.sourceOfTruth?.tracks || []).map(track => track?.timestamp || 0)
                      );
                      const previous = window.__fogLastNativeArchiveSummary || { trackCount: 0, latestTimestamp: 0 };
                      const advanced =
                        summary.trackCount > previous.trackCount ||
                        summary.latestTimestamp > previous.latestTimestamp;
                      window.__fogLastNativeArchiveSummary = summary;
                      if ((window.__fogSkipNativeSyncUntil || 0) > Date.now()) return;
                      const newerThanPage =
                        summary.trackCount > currentTrackCount ||
                        summary.latestTimestamp > currentLatestTimestamp;
                      if (!advanced || !newerThanPage || window.__fogEditModeActive) return;
                      const canApplyIncrementally =
                        currentTrackCount > 0 &&
                        summary.trackCount >= currentTrackCount &&
                        (summary.trackCount - currentTrackCount) <= (window.__fogMaxNativeIncrementalTracks || 50) &&
                        typeof window.syncNativeArchiveIncrementally === 'function';
                      if (window.reportDebugEvent) {
                        window.reportDebugEvent('web_native_archive_incremental_sync_scheduled', {
                          trackCount: String(summary.trackCount),
                          latestTimestamp: String(summary.latestTimestamp),
                          currentTrackCount: String(currentTrackCount),
                          currentLatestTimestamp: String(currentLatestTimestamp),
                          incremental: String(canApplyIncrementally),
                          threshold: String(window.__fogMaxNativeIncrementalTracks || 50)
                        });
                      }
                      if (canApplyIncrementally) {
                        await window.syncNativeArchiveIncrementally(summary, currentLatestTimestamp);
                        window.__fogLastNativeArchiveSummary = summary;
                        return;
                      }
                      window.__fogPendingNativeHydration = true;
                      window.__fogHydrateNativeInBackground = true;
                      if (window.hydrateNativeRenderCacheAfterBoot) {
                        window.hydrateNativeRenderCacheAfterBoot();
                      }
                    } catch (e) {
                      console.warn('Failed to sync incremental native archive', e);
                    }
                  };

                  const refreshNativeTrackingStatus = () => {
                    try {
                      const status = JSON.parse(AndroidBridge.getNativeTrackingStatus());
                      const gpsText = document.getElementById('mainGpsText');
                      const gpsDot = document.getElementById('mainGpsDot');
                      const modeText = document.getElementById('trackingModeText');
                      const lastPointText = document.getElementById('trackingLastPointText');
                      const segmentText = document.getElementById('trackingSegmentCountText');
                      const trackingPanel = document.getElementById('trackingStatusPanel');
                      const lastLat = Number(status.lastLat);
                      const lastLng = Number(status.lastLng);
                      const hasFreshCurrentPoint = window.isFreshCurrentLocationTimestamp
                        ? window.isFreshCurrentLocationTimestamp(status.lastPointAt)
                        : false;

                      if (gpsText) gpsText.textContent = status.isTracking ? '自动记录中' : '已暂停记录';
                      if (gpsDot) gpsDot.classList.toggle('lost', !status.isTracking);
                      if (modeText) {
                        modeText.textContent = status.isTracking
                          ? ('正在记录，当前小段 ' + (status.draftPointCount || 0) + ' 点')
                          : (status.shouldTrack ? '等待恢复记录' : '已暂停');
                      }
                      if (lastPointText) lastPointText.textContent = formatLastPoint(status.lastPointAt);
                      if (segmentText) segmentText.textContent = ((status.trackCount || 0) + ' 段');
                      let markerUpdated = false;
                      if (hasFreshCurrentPoint && Number.isFinite(lastLat) && Number.isFinite(lastLng) && window.updateCurrentLocationMarker) {
                        window.updateCurrentLocationMarker(lastLat, lastLng);
                        if (window.focusMapOnCurrentLocation) {
                          window.focusMapOnCurrentLocation(lastLat, lastLng, {
                            reason: 'native_status_last_point',
                            force: false,
                            zoom: 16,
                            timestamp: Number(status.lastPointAt) || Date.now()
                          });
                        }
                        if (window.checkLocalRegion) {
                          window.checkLocalRegion(lastLat, lastLng);
                        }
                        markerUpdated = true;
                      } else if (!window.__fogStartupLocateInFlight && window.ensureStartupLocationMarker) {
                        window.ensureStartupLocationMarker(hasFreshCurrentPoint ? 'native_status_marker_missing' : 'native_status_stale_last_point');
                      }
                      if (trackingPanel) {
                        if (status.isTracking || status.shouldTrack || window.__fogEditModeActive) trackingPanel.classList.remove('hidden');
                        else trackingPanel.classList.add('hidden');
                      }
                      if (window.renderNativeDraftPreview) {
                        window.renderNativeDraftPreview(status.draftPoints || []);
                      }
                      if (window.appendNativeTrackingDebugEvents) {
                        window.appendNativeTrackingDebugEvents(status.debugEvents || []);
                      }
                      if (window.reportDebugEvent) {
                        window.reportDebugEvent('web_native_status_refresh', {
                          isTracking: String(status.isTracking),
                          shouldTrack: String(status.shouldTrack),
                          draftPointCount: String(status.draftPointCount || 0),
                          trackCount: String(status.trackCount || 0),
                          lastLat: String(status.lastLat),
                          lastLng: String(status.lastLng),
                          lastPointFresh: String(hasFreshCurrentPoint),
                          markerUpdated: String(markerUpdated),
                          draftPreviewPoints: String((status.draftPoints || []).length || 0),
                          nativeDebugCount: String((status.debugEvents || []).length || 0)
                        });
                      }
                    } catch (e) {
                      console.warn('Failed to refresh native tracking status', e);
                    }
                  };

                  const setNativeTrackingUi = (active) => {
                    const mainToggle = document.getElementById('mainToggleContainer');
                    const trackingPanel = document.getElementById('trackingStatusPanel');

                    if (active) {
                      window.__fogNativeTrackMode = true;
                      mainToggle?.classList.add('is-active', 'is-tracking');
                      trackingPanel?.classList.remove('hidden');
                      if (window.updateMainBtnUI) window.updateMainBtnUI('recording');
                      refreshNativeTrackingStatus();
                    } else {
                      window.__fogNativeTrackMode = false;
                      mainToggle?.classList.remove('is-tracking');
                      trackingPanel?.classList.remove('hidden');
                      refreshNativeTrackingStatus();
                    }
                  };

                  autoBtn.style.display = 'none';
                  endAutoBtn.style.display = 'none';

                  manualBtn.addEventListener('click', (e) => {
                    if (window.reportDebugEvent) {
                      window.reportDebugEvent('web_native_manual_mark_enter', {
                        nativeTracking: String(window.__fogNativeTrackMode)
                      });
                    }
                    window.__fogResumeAfterManual = !!window.__fogNativeTrackMode;
                    if (!window.__fogNativeTrackMode) return;
                    try {
                      AndroidBridge.stopBackgroundTracking();
                      setNativeTrackingUi(false);
                    } catch (err) {
                      console.warn('Failed to pause native tracking for manual mark', err);
                    }
                  }, true);

                  endBtn.addEventListener('click', (e) => {
                    if (!window.__fogResumeAfterManual) return;
                    if (window.reportDebugEvent) {
                      window.reportDebugEvent('web_native_manual_mark_exit', {});
                    }
                    window.__fogSkipNativeSyncUntil = Date.now() + 12000;
                    setTimeout(() => {
                      try {
                        AndroidBridge.startBackgroundTracking();
                        setNativeTrackingUi(true);
                      } catch (err) {
                        console.warn('Failed to resume native tracking after manual mark', err);
                      } finally {
                        window.__fogResumeAfterManual = false;
                      }
                    }, 0);
                  }, true);

                  try {
                    const recovery = JSON.parse(AndroidBridge.getNativeRecoveryStatus());
                    if (recovery.shouldTrack || recovery.hasRecoverableDraft) {
                      setNativeTrackingUi(true);
                    } else {
                      refreshNativeTrackingStatus();
                    }
                  } catch (e) {
                    console.warn('Failed to read native recovery status', e);
                  }

                  refreshNativeTrackingStatus();
                  try {
                    window.__fogLastNativeArchiveSummary = JSON.parse(AndroidBridge.getNativeArchiveSummary());
                  } catch (e) {}
                  if (!window.__fogNativeTrackingStatusTimer) {
                    window.__fogNativeTrackingStatusTimer = setInterval(() => {
                      refreshNativeTrackingStatus();
                      syncNativeArchiveIfAdvanced();
                    }, 1500);
                  }

                  window.__fogNativeTrackingUiBound = true;
                  return true;
                };

                if (!bindNativeTrackingUi()) {
                  const bindRetry = setInterval(() => {
                    if (bindNativeTrackingUi()) clearInterval(bindRetry);
                  }, 500);
                }

                const bindNativeArchiveUi = () => {
                  const exportBtn = document.getElementById('exportBtn');
                  const importBtn = document.getElementById('importBtn');
                  const mergeBtn = document.getElementById('mergeBtn');
                  const resetBtn = document.getElementById('resetFog');
                  if (!exportBtn || !importBtn || !mergeBtn || !resetBtn || window.__fogNativeArchiveUiBound) return false;

                  exportBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    try {
                      AndroidBridge.pickNativeArchiveExport();
                    } catch (err) {
                      alert('导出失败：' + err);
                    }
                  }, true);

                  importBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    AndroidBridge.pickNativeArchiveForReplace();
                  }, true);

                  mergeBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    AndroidBridge.pickNativeArchiveForMerge();
                  }, true);

                  resetBtn.addEventListener('click', async (e) => {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    if (!confirm('确定清除所有记录吗？此操作无法恢复！')) return;
                    AndroidBridge.clearNativeArchive();
                    const emptyState = {
                      version: '2.0.0',
                      metadata: { totalAreaKm2: 0, createdAt: Date.now() },
                      sourceOfTruth: { tracks: [] },
                      renderCache: {
                        globalFog: turf.polygon(worldCoords),
                        globalExplored: null,
                        dailyExplored: {},
                        regionsCache: {}
                      }
                    };
                    if (window.__fogApplyNormalizedArchiveToPage) {
                      await window.__fogApplyNormalizedArchiveToPage(emptyState);
                    }
                    document.getElementById('subMenu')?.classList.add('hidden');
                  }, true);

                  window.__fogNativeArchiveUiBound = true;
                  return true;
                };

                if (!bindNativeArchiveUi()) {
                  const archiveRetry = setInterval(() => {
                    if (bindNativeArchiveUi()) clearInterval(archiveRetry);
                  }, 500);
                }
              } catch (e) {
                console.warn('Native bootstrap sync failed:', e);
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun startNativeTrackingService() {
        //#region debug-point apk-ui-storage-regression-native-start-service
        reportDebugEvent(
            "native_start_tracking_service_called",
            mapOf("hasFine" to hasForegroundLocationPermission().toString())
        )
        //#endregion
        val intent = Intent(this, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopNativeTrackingService() {
        //#region debug-point apk-ui-storage-regression-native-stop-service
        reportDebugEvent("native_stop_tracking_service_called", emptyMap())
        //#endregion
        val intent = Intent(this, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    private fun launchImportPicker(merge: Boolean) {
        pendingImportMergeMode = merge
        importPickerActive = true
        //#region debug-point apk-ui-storage-regression-native-launch-picker
        reportDebugEvent(
            "native_launch_import_picker",
            mapOf("merge" to merge.toString())
        )
        //#endregion
        importArchiveLauncher.launch(arrayOf("*/*"))
    }

    private fun launchExportPicker() {
        pendingExportFileName = nativeTrackStore.buildExportFileName()
        reportDebugEvent(
            "native_launch_export_picker",
            mapOf("fileName" to pendingExportFileName)
        )
        exportArchiveLauncher.launch(pendingExportFileName)
    }

    private fun handleExportArchiveUri(uri: Uri) {
        runCatching {
            nativeTrackStore.exportArchiveToUri(uri)
        }.onSuccess {
            Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show()
            binding.webView.post {
                binding.webView.evaluateJavascript(
                    "AndroidBridge.showNativeToast('导出成功：${escapeJsString(pendingExportFileName)}');",
                    null
                )
            }
        }.onFailure { err ->
            Toast.makeText(this, "导出失败：${err.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            binding.webView.post {
                binding.webView.evaluateJavascript(
                    "alert('导出失败：${escapeJsString(err.message ?: "未知错误")}');",
                    null
                )
            }
        }
    }

    private fun handleImportedArchiveUri(uri: Uri, merge: Boolean) {
        //#region debug-point apk-ui-storage-regression-native-import-start
        reportDebugEvent(
            "native_import_uri_received",
            mapOf("merge" to merge.toString(), "uri" to uri.toString())
        )
        //#endregion
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }

        runCatching {
            nativeTrackStore.importArchiveUri(uri, merge)
        }.onSuccess {
            //#region debug-point apk-ui-storage-regression-native-import-success
            reportDebugEvent(
                "native_import_success",
                mapOf("merge" to merge.toString())
            )
            //#endregion
            Toast.makeText(
                this,
                if (merge) "合并成功，正在刷新页面数据" else "恢复成功，正在刷新页面数据",
                Toast.LENGTH_SHORT
            ).show()
            binding.webView.post {
                binding.webView.evaluateJavascript(
                    """
                    (async function() {
                      try {
                        const rawArchive = JSON.parse(AndroidBridge.exportNativeArchiveJson());
                        const normalized = window.__fogNormalizeNativeArchiveForWeb
                          ? window.__fogNormalizeNativeArchiveForWeb(rawArchive)
                          : rawArchive;
                        if (window.__fogApplyNormalizedArchiveToPage) {
                          await window.__fogApplyNormalizedArchiveToPage(normalized);
                        } else {
                          await localforage.setItem('fog_of_world_data_v5', normalized);
                        }
                        if (window.reportDebugEvent) {
                          window.reportDebugEvent('web_import_apply_success', {
                            merge: '${if (merge) "true" else "false"}',
                            trackCount: String(normalized?.sourceOfTruth?.tracks?.length || 0)
                          });
                        }
                      } catch (e) {
                        if (window.reportDebugEvent) {
                          window.reportDebugEvent('web_import_apply_failure', {
                            merge: '${if (merge) "true" else "false"}',
                            error: String(e)
                          });
                        }
                      }
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }.onFailure { err ->
            //#region debug-point apk-ui-storage-regression-native-import-failure
            reportDebugEvent(
                "native_import_failure",
                mapOf(
                    "merge" to merge.toString(),
                    "error" to (err.message ?: "unknown")
                )
            )
            //#endregion
            Toast.makeText(this, "导入失败：${err.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            binding.webView.post {
                binding.webView.evaluateJavascript(
                    "alert('导入失败：${escapeJsString(err.message ?: "未知错误")}');",
                    null
                )
            }
        }
    }

    private fun escapeJsString(raw: String): String {
        return raw
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private fun hasForegroundLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!hasForegroundLocationPermission() || hasBackgroundLocationPermission()) return
        backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun isTrustedWebUrl(url: String?): Boolean {
        return url == "file:///android_asset/web/index.html"
    }

    private fun isTrustedWebOrigin(origin: String): Boolean {
        return origin.startsWith("file://")
    }

    //#region debug-point apk-ui-storage-regression-native-reporter
    private fun reportDebugEvent(name: String, payload: Map<String, String>) {
        Log.d("FogVisitor", "$name $payload")
        nativeTrackStore.appendTrackingDebugEvent(name, payload)
    }

}
