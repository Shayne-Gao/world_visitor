package io.shayne.fogvisitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import io.shayne.fogvisitor.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var didInjectBridgeSync = false
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                hasForegroundLocationPermission()

            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoOrigin = null
            pendingGeoCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRuntimePermissions()
        setupWebView()
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needRequest) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setGeolocationEnabled(true)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!didInjectBridgeSync) {
                    didInjectBridgeSync = true
                    injectNativeArchiveSync(webView)
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (origin == null || callback == null) return

                if (hasForegroundLocationPermission()) {
                    callback.invoke(origin, true, false)
                    return
                }

                pendingGeoOrigin = origin
                pendingGeoCallback = callback
                permissionLauncher.launch(
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
                onStopTracking = { stopNativeTrackingService() }
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

              const syncNativeArchiveToLocal = async () => {
                const nativeArchive = JSON.parse(AndroidBridge.exportNativeArchiveJson());
                const nativeSummary = JSON.parse(AndroidBridge.getNativeArchiveSummary());
                const current = await localforage.getItem('fog_of_world_data_v5');
                const currentTrackCount = current?.sourceOfTruth?.tracks?.length || 0;
                const currentLatestTimestamp = (current?.sourceOfTruth?.tracks || []).reduce((max, t) => Math.max(max, t.timestamp || 0), 0);

                const shouldReplaceLocal =
                  !current ||
                  nativeSummary.trackCount > currentTrackCount ||
                  nativeSummary.latestTimestamp > currentLatestTimestamp;

                if (shouldReplaceLocal) {
                  await localforage.setItem('fog_of_world_data_v5', nativeArchive);
                  return true;
                }
                return false;
              };

              try {
                const replaced = await syncNativeArchiveToLocal();
                if (replaced) {
                  sessionStorage.setItem('fog_native_bootstrap_done', '1');
                  if (!sessionStorage.getItem('fog_native_bootstrap_reloaded')) {
                    sessionStorage.setItem('fog_native_bootstrap_reloaded', '1');
                    location.reload();
                    return;
                  }
                }

                const installSaveHook = () => {
                  if (!window.DataManager || !window.DataManager.saveData || window.__fogSaveHookInstalled) return false;
                  const originalSave = window.DataManager.saveData.bind(window.DataManager);
                  window.DataManager.saveData = async function(data) {
                    await originalSave(data);
                    try {
                      AndroidBridge.importNativeArchiveJson(JSON.stringify(data), false);
                    } catch (e) {
                      console.warn('Native archive sync failed:', e);
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
                  const autoBtn = document.getElementById('autoTrackBtn');
                  const endBtn = document.getElementById('endActionBtn');
                  if (!autoBtn || !endBtn || window.__fogNativeTrackingUiBound) return false;

                  const setNativeTrackingUi = (active) => {
                    const mainToggle = document.getElementById('mainToggleContainer');
                    const trackingPanel = document.getElementById('trackingStatusPanel');
                    const gpsText = document.getElementById('gpsStatusText');
                    const gpsDot = document.getElementById('gpsStatusDot');

                    if (active) {
                      window.__fogNativeTrackMode = true;
                      mainToggle?.classList.add('is-active', 'is-tracking');
                      trackingPanel?.classList.remove('hidden');
                      if (gpsText) gpsText.textContent = 'APK 后台记录中';
                      if (gpsDot) gpsDot.className = 'fa-solid fa-circle text-green-400';
                      if (window.updateMainBtnUI) window.updateMainBtnUI('tracking');
                    } else {
                      window.__fogNativeTrackMode = false;
                      mainToggle?.classList.remove('is-active', 'is-tracking');
                      trackingPanel?.classList.add('hidden');
                      if (window.updateMainBtnUI) window.updateMainBtnUI();
                    }
                  };

                  autoBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    try {
                      AndroidBridge.startBackgroundTracking();
                      setNativeTrackingUi(true);
                    } catch (err) {
                      console.warn('Failed to start native tracking', err);
                    }
                  }, true);

                  endBtn.addEventListener('click', async (e) => {
                    if (!window.__fogNativeTrackMode) return;
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    try {
                      AndroidBridge.stopBackgroundTracking();
                      setNativeTrackingUi(false);
                      setTimeout(async () => {
                        try {
                          await syncNativeArchiveToLocal();
                          location.reload();
                        } catch (reloadErr) {
                          console.warn('Failed to reload native archive after stop', reloadErr);
                        }
                      }, 1200);
                    } catch (err) {
                      console.warn('Failed to stop native tracking', err);
                    }
                  }, true);

                  try {
                    const recovery = JSON.parse(AndroidBridge.getNativeRecoveryStatus());
                    if (recovery.shouldTrack || recovery.hasRecoverableDraft) {
                      setNativeTrackingUi(true);
                    }
                  } catch (e) {
                    console.warn('Failed to read native recovery status', e);
                  }

                  window.__fogNativeTrackingUiBound = true;
                  return true;
                };

                if (!bindNativeTrackingUi()) {
                  const bindRetry = setInterval(() => {
                    if (bindNativeTrackingUi()) clearInterval(bindRetry);
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
        val intent = Intent(this, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopNativeTrackingService() {
        val intent = Intent(this, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    private fun hasForegroundLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
