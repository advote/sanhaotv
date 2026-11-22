package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;

/**
 * 三好TV · 网盘授权 / 配置中心页面。
 * 用 WebView 打开 spider 返回的授权地址，让用户扫码或登录网盘，
 * Cookie 由 WebView 的 CookieManager 持久化，供后续 spider 解析使用。
 */
public class WebAuthActivity extends BaseActivity {

    public static final String EXTRA_URL = "auth_url";
    public static final String EXTRA_HTML = "auth_html";
    public static final String EXTRA_TITLE = "auth_title";

    /**
     * 三好TV：授权页关闭后置位，首页 onResume 据此刷新配置中心当前分类。
     * 扫码成功后 Cookie 虽已写入，但当前页若不重新请求 spider 数据仍显示“未扫码”，
     * 通过这个标记让返回首页时立即刷新，无需用户手动退出再进。
     */
    public static volatile boolean sAuthChanged = false;

    private WebView webView;
    private TextView tvTitle;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_web_auth;
    }

    @Override
    protected void init() {
        webView = findViewById(R.id.webAuthView);
        tvTitle = findViewById(R.id.tvAuthTitle);
        ImageView btnBack = findViewById(R.id.btnAuthBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView.canGoBack()) webView.goBack();
                else finish();
            }
        });

        Bundle extras = getIntent().getExtras();
        String title = extras == null ? "" : extras.getString(EXTRA_TITLE, "");
        if (!TextUtils.isEmpty(title)) tvTitle.setText(title);

        configWebView();

        if (extras != null) {
            String html = extras.getString(EXTRA_HTML, "");
            String url = extras.getString(EXTRA_URL, "");
            if (!TextUtils.isEmpty(html)) {
                webView.loadDataWithBaseURL("https://pan.baidu.com/", html, "text/html", "UTF-8", null);
            } else if (!TextUtils.isEmpty(url)) {
                webView.loadUrl(url);
            } else {
                Toast.makeText(this, "授权地址为空", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void configWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String t) {
                super.onReceivedTitle(view, t);
                if (!TextUtils.isEmpty(t)) tvTitle.setText(t);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    public void finish() {
        // 三好TV：必须在 onResume 回到首页之前置位（onDestroy 晚于首页 onResume）。
        // 只要授权页结束就认为 Cookie 可能变化，由首页恢复时刷新配置中心当前分类。
        if (!isChangingConfigurations()) sAuthChanged = true;
        super.finish();
    }

    @Override
    protected void onDestroy() {
        // 兜底：异常路径未走 finish() 时也能标记。
        if (isFinishing()) sAuthChanged = true;
        try {
            if (webView != null) {
                webView.destroy();
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
