/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.preferences

import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient

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
import androidx.compose.ui.draw.clip
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

    val ytdlPreferences = koinInject<YtdlPreferences>()
    val configOwnedOptions = currentMpvConfigOverrideOptions()
    val playbackOptionsEnabled = "ytdl-raw-options" !in configOwnedOptions
    val playlistMode by ytdlPreferences.playlistMode.collectAsState()
    val writeSubs by ytdlPreferences.writeSubs.collectAsState()
    val writeAutoSubs by ytdlPreferences.writeAutoSubs.collectAsState()
    val showDownloadQualityChooser by ytdlPreferences.showDownloadQualityChooser.collectAsState()
    val cookiesFile by ytdlPreferences.cookiesFile.collectAsState()
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
              if (cookiesFile.isNotBlank()) {
                OutlinedButton(
                  onClick = {
                    File(context.filesDir, "ytdlp/cookies.txt").delete()
                    File(context.filesDir, "ytdlp/instagram-cookies.txt").delete()
                    ytdlPreferences.cookiesFile.set("")
                  },
                  modifier = Modifier.fillMaxWidth(),
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
  onUseSession: (String, String) -> Unit,
) {
  val context = LocalContext.current
  var websiteUrl by rememberSaveable { mutableStateOf("https://www.instagram.com/") }
  var savedSites by rememberSaveable { mutableStateOf(emptyList<String>()) }
  var webView by remember { mutableStateOf<WebView?>(null) }
  var loadError by remember { mutableStateOf<String?>(null) }
  var isLoading by remember { mutableStateOf(true) }

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

  fun isInstagramUrl(url: String): Boolean =
    runCatching { java.net.URI(url).host?.lowercase()?.removePrefix("www.") == "instagram.com" }
      .getOrDefault(false)

  fun userAgentFor(url: String): String = if (isInstagramUrl(url)) {
    // Instagram can return a blank authentication page for the stock Android WebView UA.
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
  } else {
    // X/Twitter collapses its desktop login controls when embedded with a desktop UA.
    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
  }

  fun openWebsite() {
    val url = normalizedUrl()
    websiteUrl = url
    val host = runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
    // Google blocks account authentication inside embedded WebViews with the
    // "This browser or app may not be secure" page. Use the user's trusted browser
    // for Google/YouTube sign-in; cookies can then be exported and imported below.
    if (host == "youtube.com" || host.endsWith(".youtube.com") ||
      host == "google.com" || host.endsWith(".google.com")
    ) {
      context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
      return
    }
    webView?.let { view ->
      view.settings.userAgentString = userAgentFor(url)
      view.settings.useWideViewPort = isInstagramUrl(url)
      view.settings.loadWithOverviewMode = isInstagramUrl(url)
      view.loadUrl(url)
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
          Text(
            stringResource(R.string.ytdlp_cookie_login_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
          )
          TextButton(
            onClick = {
              val url = normalizedUrl()
              val cookies = CookieManager.getInstance().getCookie(url)
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
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
          TextField(
            value = websiteUrl,
            onValueChange = { websiteUrl = it },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.ytdlp_cookie_login_url)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
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
                    view.settings.useWideViewPort = isInstagramUrl(websiteUrl)
                    view.settings.loadWithOverviewMode = isInstagramUrl(websiteUrl)
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
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .imePadding()
            .clip(RoundedCornerShape(18.dp)),
        ) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
              WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                clipToPadding = false
              settings.javaScriptEnabled = true
              settings.domStorageEnabled = true
              settings.databaseEnabled = true
              settings.setSupportMultipleWindows(false)
              settings.javaScriptCanOpenWindowsAutomatically = true
              val initialUrl = normalizedUrl()
              settings.useWideViewPort = isInstagramUrl(initialUrl)
              settings.loadWithOverviewMode = isInstagramUrl(initialUrl)
              settings.userAgentString = userAgentFor(initialUrl)
              settings.loadsImagesAutomatically = true
              settings.allowContentAccess = true
              settings.allowFileAccess = false
              settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
              webChromeClient = object : WebChromeClient() {
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
                  isLoading = true
                  loadError = null
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                  isLoading = false
                  view?.scrollTo(0, 0)
                }
                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                  if (request?.isForMainFrame != false) {
                    isLoading = false
                    loadError = error?.description?.toString() ?: "Unable to load login page"
                  }
                }
              }
              webView = this
              loadUrl(normalizedUrl())
              }
            },
            update = { view -> webView = view },
          )
          if (isLoading) {
            Surface(
              modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
              shape = RoundedCornerShape(18.dp),
              tonalElevation = 4.dp,
              color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            ) {
              Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                CircularProgressIndicator()
                Text(
                  text = stringResource(R.string.ytdlp_cookie_login_loading),
                  style = MaterialTheme.typography.labelLarge,
                )
              }
            }
          }
        }
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
  val newRows = cookieHeader.split(';').mapNotNull { item ->
    val separator = item.indexOf('=')
    if (separator <= 0) return@mapNotNull null
    val name = item.substring(0, separator).trim()
    val value = item.substring(separator + 1).trim()
    if (name.isBlank()) null else "$domain\tTRUE\t/\t${secure.toString().uppercase()}\t0\t$name\t$value"
  }
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
  return destination
}
