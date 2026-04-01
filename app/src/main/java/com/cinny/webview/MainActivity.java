package com.cinny.webview;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.content.ContentValues;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.OutputStream;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TARGET_URL = "https://app.cinny.in/direct/";
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int PERMISSION_REQUEST_CODE = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String cameraPhotoPath;
    private String pendingBlobFileName = "download";
    private String pendingBlobMimeType = "application/octet-stream";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestAppPermissions();

        webView = findViewById(R.id.webView);
        setupWebView();
        webView.loadUrl(TARGET_URL);
    }

    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();

        // Core settings
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);

        // Media settings
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        // Cache settings
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Display settings
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);

        // Mixed content
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // User agent - append custom identifier
        String userAgent = webSettings.getUserAgentString();
        webSettings.setUserAgentString(userAgent + " CinnyAndroid/1.0");

        // Enable cookies
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new BlobDownloadInterface(), "AndroidBlobDownloader");

        // WebViewClient - handle navigation
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Keep cinny URLs in WebView
                if (url.contains("app.cinny.in") || url.contains("matrix.org")) {
                    return false;
                }
                // Open external links in browser
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    // Ignore if no browser available
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectMarkdownTableScript(view);
            }
        });

        // WebChromeClient - handle file uploads, permissions
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                // Create camera intent
                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                    File photoFile = null;
                    try {
                        photoFile = createImageFile();
                    } catch (IOException ex) {
                        // Error occurred while creating the file
                    }
                    if (photoFile != null) {
                        cameraPhotoPath = "file:" + photoFile.getAbsolutePath();
                        Uri photoUri = FileProvider.getUriForFile(
                                MainActivity.this,
                                getApplicationContext().getPackageName() + ".fileprovider",
                                photoFile);
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                    } else {
                        takePictureIntent = null;
                    }
                } else {
                    takePictureIntent = null;
                }

                // Create video capture intent
                Intent takeVideoIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
                if (takeVideoIntent.resolveActivity(getPackageManager()) == null) {
                    takeVideoIntent = null;
                }

                // Create file chooser intent
                Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);

                // Determine accepted types
                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                if (acceptTypes != null && acceptTypes.length > 0 && acceptTypes[0] != null
                        && !acceptTypes[0].isEmpty()) {
                    contentSelectionIntent.setType(acceptTypes[0]);
                    if (acceptTypes.length > 1) {
                        contentSelectionIntent.putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes);
                    }
                } else {
                    contentSelectionIntent.setType("*/*");
                }
                contentSelectionIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);

                // Build chooser
                Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "選擇檔案");

                // Add camera and video intents
                List<Intent> extraIntents = new ArrayList<>();
                if (takePictureIntent != null) {
                    extraIntents.add(takePictureIntent);
                }
                if (takeVideoIntent != null) {
                    extraIntents.add(takeVideoIntent);
                }
                if (!extraIntents.isEmpty()) {
                    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS,
                            extraIntents.toArray(new Intent[0]));
                }

                try {
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "無法開啟檔案選擇器", Toast.LENGTH_SHORT).show();
                    return false;
                }

                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        // Download listener
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                // For Android 10 (API 29) and below, we need WRITE_EXTERNAL_STORAGE
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
                        Toast.makeText(MainActivity.this, "請授予儲存權限以開始下載", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                try {
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    if (fileName == null || fileName.trim().isEmpty()) {
                        fileName = "download_" + System.currentTimeMillis();
                    }
                    if (mimeType == null || mimeType.trim().isEmpty()) {
                        mimeType = "application/octet-stream";
                    }

                    if (url.startsWith("blob:")) {
                        pendingBlobFileName = fileName;
                        pendingBlobMimeType = mimeType;
                        downloadBlob(url, fileName, mimeType);
                        Toast.makeText(MainActivity.this, "正在處理下載...", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setTitle(fileName);
                    request.setDescription("下載中...");
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, fileName);
                    request.setMimeType(mimeType);

                    // Add cookies
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) {
                        request.addRequestHeader("Cookie", cookies);
                    }
                    request.addRequestHeader("User-Agent", userAgent);

                    DownloadManager downloadManager =
                            (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (downloadManager != null) {
                        long downloadId = downloadManager.enqueue(request);
                        Toast.makeText(MainActivity.this, "開始下載: " + fileName,
                                Toast.LENGTH_SHORT).show();
                        Log.d("CinnyWebView", "Download started with ID: " + downloadId + " URL: " + url);
                    }
                } catch (Exception e) {
                    Log.e("CinnyWebView", "Download Error: " + e.getMessage(), e);
                    Toast.makeText(MainActivity.this, "下載失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    // Fallback to browser download if applicable
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                    } catch (ActivityNotFoundException ae) {
                        Toast.makeText(MainActivity.this, "找不到可用的瀏覽器開啟連結", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    private void injectMarkdownTableScript(WebView view) {
        String js =
            "(function () {" +
            "  'use strict';" +
            "  var STYLE_ID = 'echo-cinny-markdown-table-style';" +
            "  var PROCESSED_ATTR = 'data-echo-md-table-processed';" +
            "  var TABLE_CLASS = 'echo-md-table';" +
            "  function injectStyles() {" +
            "    if (document.getElementById(STYLE_ID)) return;" +
            "    var style = document.createElement('style');" +
            "    style.id = STYLE_ID;" +
            "    style.textContent = " +
            "      '.' + TABLE_CLASS + '-wrap {'" +
            "      + ' display: block; width: 100%; overflow-x: auto;'" +
            "      + ' margin: 0; border: none;'" +
            "      + ' border-radius: 0; background: transparent;'" +
            "      + ' box-shadow: none; }'" +
            "      + 'table.' + TABLE_CLASS + ' { width: 100%; margin: 0;'" +
            "      + ' table-layout: fixed; border-collapse: collapse; border-spacing: 0;'" +
            "      + ' font-size: 0.95em; line-height: 1.5; overflow: hidden; }'" +
            "      + 'table.' + TABLE_CLASS + ' th, table.' + TABLE_CLASS + ' td {'" +
            "      + ' border-right: 1px solid rgba(127,127,127,0.18);'" +
            "      + ' border-bottom: 1px solid rgba(127,127,127,0.18);'" +
            "      + ' padding: 0.65em 0.9em; vertical-align: middle;'" +
            "      + ' text-align: center !important; white-space: normal; word-break: break-word; }'" +
            "      + 'table.' + TABLE_CLASS + ' th:last-child, table.' + TABLE_CLASS + ' td:last-child { border-right: none; }'" +
            "      + 'table.' + TABLE_CLASS + ' tbody tr:last-child td { border-bottom: none; }'" +
            "      + 'table.' + TABLE_CLASS + ' th { font-weight: 700; text-align: center;'" +
            "      + ' background: rgba(127,127,127,0.12); }'" +
            "      + 'table.' + TABLE_CLASS + ' tbody tr:nth-child(even) td { background: rgba(127,127,127,0.045); }'" +
            "      + 'table.' + TABLE_CLASS + ' tbody tr:hover td { background: rgba(127,127,127,0.08); }';" +
            "    document.head.appendChild(style);" +
            "  }" +
            "  function escapeHtml(text) {" +
            "    return text.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')" +
            "               .replace(/\"/g,'&quot;').replace(/'/g,'&#39;');" +
            "  }" +
            "  function splitMarkdownRow(line) {" +
            "    var trimmed = line.trim();" +
            "    if (!trimmed.includes('|')) return null;" +
            "    if (trimmed.startsWith('|')) trimmed = trimmed.slice(1);" +
            "    if (trimmed.endsWith('|')) trimmed = trimmed.slice(0,-1);" +
            "    return trimmed.split('|').map(function(c){ return c.trim(); });" +
            "  }" +
            "  function isDividerCell(cell) { return /^:?-{3,}:?$/.test(cell); }" +
            "  function parseMarkdownTable(lines) {" +
            "    if (lines.length < 2) return null;" +
            "    var header = splitMarkdownRow(lines[0]);" +
            "    var divider = splitMarkdownRow(lines[1]);" +
            "    if (!header || !divider) return null;" +
            "    if (header.length < 2 || divider.length !== header.length) return null;" +
            "    if (!divider.every(isDividerCell)) return null;" +
            "    var rows = [];" +
            "    for (var i = 2; i < lines.length; i++) {" +
            "      var row = splitMarkdownRow(lines[i]);" +
            "      if (!row || row.length !== header.length) return null;" +
            "      rows.push(row);" +
            "    }" +
            "    return { header: header, rows: rows };" +
            "  }" +
            "  function buildTableElement(parsed) {" +
            "    var outer = document.createElement('div');" +
            "    outer.style.cssText = 'display:block;width:100%;margin:0;padding:0;clear:both;';" +
            "    var wrap = document.createElement('div');" +
            "    wrap.className = TABLE_CLASS + '-wrap';" +
            "    var table = document.createElement('table');" +
            "    table.className = TABLE_CLASS;" +
            "    var thead = document.createElement('thead');" +
            "    var headerRow = document.createElement('tr');" +
            "    parsed.header.forEach(function(cell) {" +
            "      var th = document.createElement('th');" +
            "      th.innerHTML = escapeHtml(cell);" +
            "      th.style.textAlign = 'center';" +
            "      headerRow.appendChild(th);" +
            "    });" +
            "    thead.appendChild(headerRow);" +
            "    table.appendChild(thead);" +
            "    var tbody = document.createElement('tbody');" +
            "    parsed.rows.forEach(function(row) {" +
            "      var tr = document.createElement('tr');" +
            "      row.forEach(function(cell) {" +
            "        var td = document.createElement('td');" +
            "        td.innerHTML = escapeHtml(cell);" +
            "        td.style.textAlign = 'center';" +
            "        tr.appendChild(td);" +
            "      });" +
            "      tbody.appendChild(tr);" +
            "    });" +
            "    table.appendChild(tbody);" +
            "    wrap.appendChild(table);" +
            "    outer.appendChild(wrap);" +
            "    return outer;" +
            "  }" +
            "  function processElement(el) {" +
            "    if (!el || el.getAttribute(PROCESSED_ATTR) === '1') return;" +
            "    if (el.querySelector('table.' + TABLE_CLASS)) {" +
            "      el.setAttribute(PROCESSED_ATTR, '1'); return;" +
            "    }" +
            "    var text = el.innerText || el.textContent || '';" +
            "    if (!text.includes('|') || !text.includes('\\n') || text.includes('```')) {" +
            "      el.setAttribute(PROCESSED_ATTR, '1'); return;" +
            "    }" +
            "    var lines = text.split(/\\r?\\n/).map(function(l){ return l.trim(); }).filter(Boolean);" +
            "    var parsed = parseMarkdownTable(lines);" +
            "    if (!parsed) { el.setAttribute(PROCESSED_ATTR, '1'); return; }" +
            "    el.innerHTML = '';" +
            "    el.style.display = 'flex';" +
            "    el.style.justifyContent = 'center';" +
            "    el.style.width = '100%';" +
            "    el.style.overflowX = 'auto';" +
            "    el.appendChild(buildTableElement(parsed));" +
            "    el.setAttribute(PROCESSED_ATTR, '1');" +
            "  }" +
            "  function scan() {" +
            "    document.querySelectorAll('pre, code, p, div, span, article').forEach(processElement);" +
            "  }" +
            "  function boot() {" +
            "    injectStyles();" +
            "    scan();" +
            "    if (window.__echoMdTableObserver) return;" +
            "    var observer = new MutationObserver(function() { scan(); });" +
            "    observer.observe(document.body, { childList: true, subtree: true });" +
            "    window.__echoMdTableObserver = observer;" +
            "  }" +
            "  if (document.readyState === 'loading') {" +
            "    document.addEventListener('DOMContentLoaded', boot, { once: true });" +
            "  } else {" +
            "    boot();" +
            "  }" +
            "})();";
        view.evaluateJavascript(js, null);
    }

    private void downloadBlob(String blobUrl, String fileName, String mimeType) {
        String safeFileName = fileName.replace("'", "\\'");
        String script = "(function() {" +
                "var xhr = new XMLHttpRequest();" +
                "xhr.open('GET', '" + blobUrl + "', true);" +
                "xhr.responseType = 'blob';" +
                "xhr.onload = function() {" +
                "  if (xhr.status === 200 || xhr.status === 0) {" +
                "    var reader = new FileReader();" +
                "    reader.onloadend = function() {" +
                "      var base64data = reader.result.split(',')[1];" +
                "      AndroidBlobDownloader.saveBase64File(base64data, '" + safeFileName + "', '" + mimeType + "');" +
                "    };" +
                "    reader.readAsDataURL(xhr.response);" +
                "  } else {" +
                "    AndroidBlobDownloader.onDownloadFailed('Blob download failed with status: ' + xhr.status);" +
                "  }" +
                "};" +
                "xhr.onerror = function() { AndroidBlobDownloader.onDownloadFailed('Blob download network error'); };" +
                "xhr.send();" +
                "})();";
        webView.evaluateJavascript(script, null);
    }

    private void saveBase64ToDownloads(String base64Data, String fileName, String mimeType) {
        try {
            byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
            OutputStream outputStream;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri item = getContentResolver().insert(collection, values);
                if (item == null) {
                    throw new IOException("無法建立下載檔案");
                }

                outputStream = getContentResolver().openOutputStream(item);
                if (outputStream == null) {
                    throw new IOException("無法開啟輸出串流");
                }
                outputStream.write(data);
                outputStream.flush();
                outputStream.close();

                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(item, values, null, null);
            } else {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                    throw new IOException("無法建立 Downloads 資料夾");
                }
                File outFile = new File(downloadsDir, fileName);
                outputStream = new FileOutputStream(outFile);
                outputStream.write(data);
                outputStream.flush();
                outputStream.close();
            }

            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "下載完成: " + fileName, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.e("CinnyWebView", "Blob save error: " + e.getMessage(), e);
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Blob 下載失敗: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private class BlobDownloadInterface {
        @JavascriptInterface
        public void saveBase64File(String base64Data, String fileName, String mimeType) {
            saveBase64ToDownloads(base64Data, fileName, mimeType);
        }

        @JavascriptInterface
        public void onDownloadFailed(String error) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "下載失敗: " + error, Toast.LENGTH_LONG).show());
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return;

            Uri[] results = null;

            if (resultCode == Activity.RESULT_OK) {
                if (data == null || (data.getData() == null && data.getClipData() == null)) {
                    // Camera photo result
                    if (cameraPhotoPath != null) {
                        results = new Uri[]{Uri.parse(cameraPhotoPath)};
                    }
                } else if (data.getClipData() != null) {
                    // Multiple file selection
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    // Single file selection
                    results = new Uri[]{data.getData()};
                }
            }

            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            cameraPhotoPath = null;
        }
    }

    private void requestAppPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // Battery optimization request
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent();
                intent.setAction(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    // Fallback to general battery settings
                    Intent settingsIntent = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(settingsIntent);
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Permissions handled - app will function with or without them
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Removed webView.onResume() to prevent background freezing
    }

    @Override
    protected void onPause() {
        // Removed webView.onPause() to keep WebView alive in background
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
