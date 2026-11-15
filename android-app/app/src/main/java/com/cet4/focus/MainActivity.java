package com.cet4.focus;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.MimeTypeMap;
import android.webkit.JavascriptInterface;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;

public class MainActivity extends Activity {
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String LATEST_RELEASE_URL = "https://github.com/qyl123665408/cet4-focus/releases/latest";
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        WebViewClient assetClient = new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return interceptAsset(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && ASSET_HOST.equals(uri.getHost())) return false;
                if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (RuntimeException ignored) { }
                }
                return true;
            }
        };
        webView.setWebViewClient(assetClient);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    return interceptAsset(request.getUrl());
                }
            });
        }
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new StudyBridge(), "StudyBridge");
        setContentView(webView);
        webView.loadUrl("https://" + ASSET_HOST + "/assets/index.html");
    }

    private final class StudyBridge {
        @JavascriptInterface
        public String getInstalledVersion() {
            try {
                PackageInfo info;
                if (Build.VERSION.SDK_INT >= 33) {
                    info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0));
                } else {
                    info = getPackageManager().getPackageInfo(getPackageName(), 0);
                }
                return info.versionName == null ? "" : info.versionName;
            } catch (PackageManager.NameNotFoundException error) {
                return "";
            }
        }

        @JavascriptInterface
        public void openLatestRelease() {
            runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_RELEASE_URL))));
        }
    }

    private WebResourceResponse missingAsset() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private WebResourceResponse interceptAsset(Uri uri) {
        if (!ASSET_HOST.equals(uri.getHost())) return null;
        String path = uri.getPath();
        if (!"https".equals(uri.getScheme()) || path == null || !path.startsWith("/assets/")
                || path.contains("..") || path.indexOf(92) >= 0) return missingAsset();
        String asset = path.substring("/assets/".length());
        String type = asset.endsWith(".js") ? "application/javascript"
                : asset.endsWith(".json") ? "application/json"
                : MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(asset));
        if (type == null) type = "application/octet-stream";
        try {
            return new WebResourceResponse(type, "UTF-8", 200, "OK",
                    Collections.singletonMap("Cache-Control", "no-store"), getAssets().open(asset));
        } catch (IOException error) {
            return missingAsset();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.evaluateJavascript("window.dispatchEvent(new Event('studyappresume'))", null);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
        }
        super.onDestroy();
    }
}
