/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.preferences

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.util.Log
import android.database.sqlite.SQLiteDatabase

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.YtdlPreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.ytdlp.YtdlPlaylistMode
import app.infinity.mpvz.ui.player.ytdlp.YtdlpInstallationStatus
import app.infinity.mpvz.ui.player.ytdlp.YtdlpManager
import app.infinity.mpvz.ui.player.ytdlp.YtdlpReleaseChannel
import app.infinity.mpvz.ui.preferences.components.SwitchPreference
import app.infinity.mpvz.ui.theme.spacing
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.currentMpvConfigOverrideOptions
import app.infinity.mpvz.ui.utils.popSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.koin.compose.koinInject

private const val COOKIE_WEBVIEW_TAG = "CookieWebView"

@Serializable
object YtdlpSettingsScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val settingsHighlight =
      rememberSettingsSearchHighlight(YtdlpSettingsScreen, scrollState, MaterialTheme.colorScheme.primary)
    var isRunning by remember { mutableStateOf(false) }
    var showCookieLogin by remember { mutableStateOf(false) }
    var showCustomUserAgentSheet by remember { mutableStateOf(false) }

    val ytdlPreferences = koinInject<YtdlPreferences>()
    val configOwnedOptions = currentMpvConfigOverrideOptions()
    val playbackOptionsEnabled = "ytdl-raw-options" !in configOwnedOptions
    val playlistMode by ytdlPreferences.playlistMode.collectAsState()
    val writeSubs by ytdlPreferences.writeSubs.collectAsState()
    val writeAutoSubs by ytdlPreferences.writeAutoSubs.collectAsState()
    val showDownloadQualityChooser by ytdlPreferences.showDownloadQualityChooser.collectAsState()
    val cookiesFile by ytdlPreferences.cookiesFile.collectAsState()
    val customUserAgent by ytdlPreferences.customUserAgent.collectAsState()
    val installationInfo by YtdlpManager.installationInfo.collectAsState()
    val cookieFilePicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument(),
    ) { uri ->
      uri ?: return@rememberLauncherForActivityResult
      scope.launch {
        val copied = withContext(Dispatchers.IO) {
          runCatching {
            val destination = File(context.filesDir, "ytdlp/instagram-cookies.txt")
            destination.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
              destination.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to open selected cookie file")
            destination
          }.getOrNull()
        }
        copied?.takeIf { it.isFile && it.length() > 0L }?.let {
          ytdlPreferences.cookiesFile.set(it.absolutePath)
        }
      }
    }

    LaunchedEffect(Unit) {
      YtdlpManager.refreshInstallationInfo(context)
    }

    fun runOperation(operation: suspend () -> Unit) {
      scope.launch {
        isRunning = true
        try {
          operation()
        } finally {
          isRunning = false
        }
      }
    }

    val isInstalled = installationInfo?.isInstalled == true
    val stableActionLabel =
      when {
        !isInstalled -> stringResource(R.string.ui_install_stable)
        installationInfo?.channel == YtdlpReleaseChannel.STABLE -> stringResource(R.string.ui_update_stable)
        else -> stringResource(R.string.ui_switch_to_stable)
      }
    val nightlyActionLabel =
      if (installationInfo?.channel == YtdlpReleaseChannel.NIGHTLY) {
        stringResource(R.string.ui_update_nightly)
      } else {
        stringResource(R.string.ui_switch_to_nightly)
      }

    if (showCookieLogin) {
      WebsiteCookieLoginDialog(
        onDismiss = { showCookieLogin = false },
        customUserAgent = customUserAgent,
        onUseSession = { websiteUrl, cookieHeader ->
          scope.launch {
            val destination = withContext(Dispatchers.IO) {
              runCatching { writeWebsiteCookiesFile(context, websiteUrl, cookieHeader) }.getOrNull()
            }
            if (destination != null) {
              // Keep the browser open so another website can be authenticated in the same session.
              ytdlPreferences.cookiesFile.set(destination.absolutePath)
            }
          }
        },
      )
    }

    if (showCustomUserAgentSheet) {
      var draftUserAgent by remember(showCustomUserAgentSheet) { mutableStateOf(customUserAgent) }
      ModalBottomSheet(
        onDismissRequest = { showCustomUserAgentSheet = false },
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
            text = stringResource(R.string.ytdlp_custom_user_agent_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = stringResource(R.string.ytdlp_custom_user_agent_summary),
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium,
          )
          OutlinedTextField(
            value = draftUserAgent,
            onValueChange = { draftUserAgent = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ytdlp_custom_user_agent_title)) },
            singleLine = true,
          )
          Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedButton(
              onClick = {
                draftUserAgent = ""
                ytdlPreferences.customUserAgent.set("")
              },
              modifier = Modifier.weight(1f),
            ) {
              Text(stringResource(R.string.ytdlp_custom_user_agent_reset))
            }
            Button(
              onClick = {
                ytdlPreferences.customUserAgent.set(draftUserAgent.trim())
                showCustomUserAgentSheet = false
              },
              modifier = Modifier.weight(1f),
            ) {
              Text(stringResource(R.string.ytdlp_custom_user_agent_save))
            }
          }
        }
      }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.ui_yt_dlp_streaming),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backStack.popSafely() }) {
              Icon(Icons.RoundedFilled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        Column(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight)
              .verticalScroll(scrollState)
              .padding(bottom = 32.dp),
          verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
          YtdlpInstallationStatus(
            info = installationInfo,
            isRunning = isRunning,
            modifier = Modifier.padding(horizontal = 16.dp),
          )

          PreferenceSectionHeader(
            title = stringResource(R.string.ui_release_channel),
            modifier = Modifier.settingsSearchTarget(R.string.ui_yt_dlp_manager),
          )

          PreferenceCard {
            Column(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Button(
                onClick = {
                  runOperation {
                    if (installationInfo?.channel == YtdlpReleaseChannel.STABLE) {
                      YtdlpManager.runUpdate(context) {}
                    } else {
                      YtdlpManager.runInstall(context) {}
                    }
                  }
                },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
              ) {
                Icon(Icons.RoundedFilled.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stableActionLabel)
              }

              OutlinedButton(
                onClick = {
                  runOperation { YtdlpManager.runUpdateToNightly(context) {} }
                },
                enabled = !isRunning && isInstalled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              ) {
                Icon(Icons.RoundedFilled.Update, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(nightlyActionLabel)
              }
            }
          }

          PreferenceSectionHeader(title = stringResource(R.string.ytdlp_subtitles_language))

          PreferenceCard {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.ui_download_media_subtitles),
                value = writeSubs,
                enabled = playbackOptionsEnabled,
                onValueChange = { ytdlPreferences.writeSubs.set(it) },
                title = { Text(stringResource(R.string.ui_download_media_subtitles)) },
                summary = {
                  Text(stringResource(R.string.ui_automatically_extract_and_load_physical_subtitle_tracks_from_sup))
                },
              )
              PreferenceDivider()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.ui_include_auto_generated_subtitles),
                value = writeAutoSubs,
                enabled = playbackOptionsEnabled,
                onValueChange = { ytdlPreferences.writeAutoSubs.set(it) },
                title = { Text(stringResource(R.string.ui_include_auto_generated_subtitles)) },
                summary = {
                  Text(stringResource(R.string.ui_fetch_auto_caption_tracks_e_g_youtube_speech_to_text_when_regula))
                },
              )
            }
          }

          PreferenceSectionHeader(
            title = stringResource(R.string.ytdlp_playlist_behavior),
            modifier = Modifier.settingsSearchTarget(R.string.ytdlp_playlist_behavior),
          )

          PreferenceCard {
            FlowRow(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              YtdlPlaylistMode.entries.forEach { mode ->
                FilterChip(
                  selected = playlistMode == mode,
                  enabled = playbackOptionsEnabled,
                  onClick = { ytdlPreferences.playlistMode.set(mode) },
                  label = { Text(mode.title) },
                  leadingIcon =
                    if (playlistMode == mode) {
                      { Icon(Icons.RoundedFilled.Check, null, modifier = Modifier.size(16.dp)) }
                    } else {
                      null
                    },
                )
              }
            }
          }

          PreferenceSectionHeader(
            title = stringResource(R.string.ytdlp_advanced_networking),
            modifier = Modifier.settingsSearchTarget(R.string.ytdlp_download_quality_chooser_title),
          )

          PreferenceCard {
            Column(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = stringResource(R.string.ytdlp_instagram_cookies_title),
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text = stringResource(R.string.ytdlp_instagram_cookies_summary),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text = cookiesFile.ifBlank { stringResource(R.string.ytdlp_instagram_cookies_not_configured) },
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
              )
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCookieLogin = true }) {
                  Text(stringResource(R.string.ytdlp_cookies_login))
                }
                OutlinedButton(onClick = { cookieFilePicker.launch(arrayOf("text/plain", "application/json", "*/*")) }) {
                  Text(stringResource(R.string.ytdlp_cookies_choose))
                }
              }
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showCustomUserAgentSheet = true }) {
                  Text(stringResource(R.string.ytdlp_custom_user_agent_title))
                }
                OutlinedButton(
                  onClick = {
                    File(context.filesDir, "ytdlp/cookies.txt").delete()
                    File(context.filesDir, "ytdlp/instagram-cookies.txt").delete()
                    ytdlPreferences.cookiesFile.set("")
                  },
                  enabled = cookiesFile.isNotBlank(),
                ) {
                  Text(stringResource(R.string.ytdlp_cookies_clear))
                }
              }
            }
          }

          PreferenceCard {
            SwitchPreference(
              modifier = Modifier.settingsSearchTarget(R.string.ytdlp_download_quality_chooser_title),
              value = showDownloadQualityChooser,
              onValueChange = { ytdlPreferences.showDownloadQualityChooser.set(it) },
              title = { Text(stringResource(R.string.ytdlp_download_quality_chooser_title)) },
              summary = { Text(stringResource(R.string.ytdlp_download_quality_chooser_summary)) },
            )
          }
        }
      }
    }
  }

}


@Composable
private fun WebsiteCookieLoginDialog(
  onDismiss: () -> Unit,
  customUserAgent: String,
  onUseSession: (String, String) -> Unit,
) {
  val context = LocalContext.current
  var websiteUrl by rememberSaveable { mutableStateOf("https://www.instagram.com/") }
  var savedSites by rememberSaveable { mutableStateOf(emptyList<String>()) }
  var webView by remember { mutableStateOf<WebView?>(null) }
  var loadError by remember { mutableStateOf<String?>(null) }
  val desktopWebViewUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
  val mobileWebViewUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36"

  fun userAgentFor(url: String): String {
    if (customUserAgent.isNotBlank()) return customUserAgent.trim()
    val host = runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
    return if (host == "youtube.com" || host.endsWith(".youtube.com") || host == "google.com" || host.endsWith(".google.com")) {
      mobileWebViewUserAgent
    } else {
      desktopWebViewUserAgent
    }
  }

  fun normalizedUrl(): String {
    val value = websiteUrl.trim()
    val normalized = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
    return if (normalized.contains("instagram.com", ignoreCase = true) &&
      normalized.trimEnd('/').equals("https://www.instagram.com", ignoreCase = true)
    ) {
      "https://www.instagram.com/accounts/login/"
    } else {
      normalized
    }
  }

  fun openWebsite() {
    val url = normalizedUrl()
    websiteUrl = url
    webView?.let { view ->
      view.settings.userAgentString = userAgentFor(url)
      view.loadUrl(url)
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize().safeDrawingPadding(),
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(stringResource(R.string.ytdlp_cookie_login_title), style = MaterialTheme.typography.titleMedium)
          TextButton(
            onClick = {
              val url = normalizedUrl()
              val cookieManager = CookieManager.getInstance()
              cookieManager.flush()
              val cookies = cookieManager.getCookie(url)
              Log.i(
                COOKIE_WEBVIEW_TAG,
                "export requested url=$url hasCookies=${!cookies.isNullOrBlank()} " +
                  "cookieNames=${cookies.orEmpty().split(';').mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }}",
              )
              if (!cookies.isNullOrBlank()) {
                val host = java.net.URI(url).host?.removePrefix("www.") ?: url
                savedSites = (savedSites + host).distinct()
                onUseSession(url, cookies)
              }
            },
          ) {
            Text(stringResource(R.string.ytdlp_cookie_login_use))
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
          TextField(
            value = websiteUrl,
            onValueChange = { websiteUrl = it },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.ytdlp_cookie_login_url)) },
            singleLine = true,
          )
          Button(onClick = ::openWebsite) {
            Text(stringResource(R.string.ytdlp_cookie_login_open))
          }
        }
        if (savedSites.isNotEmpty()) {
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            savedSites.forEach { host ->
              FilterChip(
                selected = websiteUrl.contains(host, ignoreCase = true),
                onClick = {
                  websiteUrl = "https://$host/"
                  webView?.let { view ->
                    view.settings.userAgentString = userAgentFor(websiteUrl)
                    view.loadUrl(websiteUrl)
                  }
                },
                label = { Text(host) },
              )
            }
          }
        }
        loadError?.let { error ->
          Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
          )
        }
        AndroidView(
          modifier = Modifier.fillMaxWidth().weight(1f).imePadding(),
          factory = {
            WebView(context).apply {
              isFocusable = true
              isFocusableInTouchMode = true
              setOnTouchListener { view, _ ->
                if (!view.hasFocus()) view.requestFocus()
                false
              }
              settings.javaScriptEnabled = true
              settings.domStorageEnabled = true
              settings.databaseEnabled = true
              settings.useWideViewPort = true
              settings.loadWithOverviewMode = true
              settings.setSupportMultipleWindows(false)
              settings.javaScriptCanOpenWindowsAutomatically = true
              // Instagram often serves a blank login response to the Android WebView UA.
              // A current desktop Chrome UA keeps the login page usable while cookies remain in this WebView.
              settings.userAgentString = userAgentFor(normalizedUrl())
              settings.loadsImagesAutomatically = true
              settings.allowContentAccess = true
              settings.allowFileAccess = false
              settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
              webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                  Log.d(
                    COOKIE_WEBVIEW_TAG,
                    "console level=${message?.messageLevel()} source=${message?.sourceId()} " +
                      "line=${message?.lineNumber()} message=${message?.message()}",
                  )
                  return true
                }
                override fun onCreateWindow(
                  view: WebView?,
                  isDialog: Boolean,
                  isUserGesture: Boolean,
                  resultMsg: android.os.Message?,
                ): Boolean {
                  val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                  transport.webView = view
                  resultMsg.sendToTarget()
                  return true
                }
              }
              CookieManager.getInstance().setAcceptCookie(true)
              CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
              webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = false
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                  Log.i(
                    COOKIE_WEBVIEW_TAG,
                    "page_started url=$url ua=${view?.settings?.userAgentString} " +
                      "size=${view?.width}x${view?.height}",
                  )
                  loadError = null
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                  view?.post {
                    view.requestFocus()
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.flush()
                    val cookieNames = cookieManager.getCookie(url.orEmpty()).orEmpty()
                      .split(';')
                      .mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }
                    Log.d(
                      COOKIE_WEBVIEW_TAG,
                      "page_finished url=$url focus=${view.hasFocus()} " +
                        "cookieNames=$cookieNames",
                    )
                  }
                }
                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                  Log.e(
                    COOKIE_WEBVIEW_TAG,
                    "page_error url=${request?.url} mainFrame=${request?.isForMainFrame} " +
                      "code=${error?.errorCode} description=${error?.description}",
                  )
                  if (request?.isForMainFrame != false) {
                    loadError = error?.description?.toString() ?: "Unable to load login page"
                  }
                }
                override fun onReceivedHttpError(
                  view: WebView?,
                  request: android.webkit.WebResourceRequest?,
                  errorResponse: android.webkit.WebResourceResponse?,
                ) {
                  Log.w(
                    COOKIE_WEBVIEW_TAG,
                    "http_error url=${request?.url} mainFrame=${request?.isForMainFrame} " +
                      "status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase}",
                  )
                }
              }
              webView = this
              Log.i(
                COOKIE_WEBVIEW_TAG,
                "created ua=${settings.userAgentString} js=${settings.javaScriptEnabled} " +
                  "domStorage=${settings.domStorageEnabled} thirdPartyCookies=true",
              )
              loadUrl(normalizedUrl())
            }
          },
          update = { view -> webView = view },
        )
      }
    }
  }
}

private fun writeWebsiteCookiesFile(
  context: android.content.Context,
  websiteUrl: String,
  cookieHeader: String,
): File {
  val host = java.net.URI(websiteUrl).host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    ?: error("Website URL has no host")
  val domain = ".${host}"
  val secure = websiteUrl.startsWith("https://")
  val destination = File(context.filesDir, "ytdlp/cookies.txt")
  destination.parentFile?.mkdirs()
  CookieManager.getInstance().flush()
  val databaseRows = runCatching {
    val database = context.dataDir.resolve("app_webview/Default/Cookies")
    if (!database.isFile) return@runCatching emptyList<String>()
    SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      db.query(
        "cookies",
        arrayOf("host_key", "path", "name", "value", "expires_utc", "is_secure"),
        null,
        null,
        null,
        null,
        null,
      ).use { cursor ->
        buildList {
          val hostIndex = cursor.getColumnIndexOrThrow("host_key")
          val pathIndex = cursor.getColumnIndexOrThrow("path")
          val nameIndex = cursor.getColumnIndexOrThrow("name")
          val valueIndex = cursor.getColumnIndexOrThrow("value")
          val expiryIndex = cursor.getColumnIndexOrThrow("expires_utc")
          val secureIndex = cursor.getColumnIndexOrThrow("is_secure")
          while (cursor.moveToNext()) {
            val rowHost = cursor.getString(hostIndex).let { if (it.startsWith('.')) it else ".${it}" }
            val rowPath = cursor.getString(pathIndex).ifBlank { "/" }
            val rowName = cursor.getString(nameIndex)
            val rowValue = cursor.getString(valueIndex)
            if (rowName.isNotBlank() && rowValue != null) {
              val expiry = (cursor.getLong(expiryIndex) / 1_000_000L - 11_644_473_600L).coerceAtLeast(0L)
              add("$rowHost\tTRUE\t$rowPath\t${(cursor.getLong(secureIndex) == 1L).toString().uppercase()}\t$expiry\t$rowName\t$rowValue")
            }
          }
        }
      }
    }
  }.getOrElse { error ->
    Log.w(COOKIE_WEBVIEW_TAG, "database_export_failed type=${error.javaClass.simpleName}")
    emptyList()
  }
  val fallbackRows = cookieHeader.split(';').mapNotNull { item ->
    val separator = item.indexOf('=')
    if (separator <= 0) return@mapNotNull null
    val name = item.substring(0, separator).trim()
    val value = item.substring(separator + 1).trim()
    if (name.isBlank()) null else "$domain\tTRUE\t/\t${secure.toString().uppercase()}\t0\t$name\t$value"
  }
  val newRows = if (databaseRows.isNotEmpty()) databaseRows else fallbackRows
  require(newRows.isNotEmpty()) { "Website did not provide cookies" }
  val merged = linkedMapOf<String, String>()
  if (destination.isFile) {
    destination.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { row ->
      val fields = row.split('\t')
      if (fields.size >= 7) merged["${fields[0]}\t${fields[2]}\t${fields[5]}"] = row
    }
  }
  newRows.forEach { row ->
    val fields = row.split('\t')
    merged["${fields[0]}\t${fields[2]}\t${fields[5]}"] = row
  }
  destination.writeText("# Netscape HTTP Cookie File\n" + merged.values.joinToString("\n") + "\n")
  Log.i(COOKIE_WEBVIEW_TAG, "cookie_file_written path=${destination.name} rows=${merged.size} source=${if (databaseRows.isNotEmpty()) "database" else "url_header"}")
  return destination
}
