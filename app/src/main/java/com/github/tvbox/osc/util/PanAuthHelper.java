package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.activity.WebAuthActivity;
import com.github.catvod.crawler.Spider;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 三好TV · 网盘授权助手
 *
 * <p>网盘类 spider（百度 / 夸克 / UC）的配置中心页面由 spider 自身在运行时提供。
 * 本类通过 catvod spider 协议通用的 {@code action(String)} 接口，向配置中心站点
 * 请求授权页面地址或 HTML，然后用 WebView 打开，让用户在页面上扫码 / 登录，
 * Cookie 由 WebView 持久化后即可供 spider 解析网盘资源使用。</p>
 *
 * <p>整个流程在子线程执行，不阻塞 UI；任何一步失败都会回调 {@code onFail}，
 * 由调用方给出明确提示，绝不静默失败。</p>
 */
public final class PanAuthHelper {

    public interface Callback {
        void onOpen();
        void onFail(String reason);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 配置中心站点的候选 key（按优先级）。 */
    private static final String[] PREFERRED_KEYS = {"Wexconfig", "PanConfig", "Config", "MyPan"};
    /** 常见的授权 action 参数，逐个尝试。 */
    private static final String[] ACTIONS = {"config", "auth", "login", "cookie", "setting", ""};

    private PanAuthHelper() {}

    public static void openAuth(Context context, Callback callback) {
        EXECUTOR.execute(() -> {
            String[] out = new String[2]; // [0]=url, [1]=html
            Throwable lastErr = null;
            try {
                SourceBean site = findConfigSite();
                if (site == null) {
                    fail(callback, "未找到网盘配置中心站点");
                    return;
                }
                Spider spider = ApiConfig.get().getCSP(site);
                if (spider == null) {
                    fail(callback, "配置中心 spider 未加载");
                    return;
                }
                for (String action : ACTIONS) {
                    try {
                        String result = spider.action(action);
                        Result parsed = parse(result);
                        if (parsed != null) {
                            out[0] = parsed.url;
                            out[1] = parsed.html;
                            break;
                        }
                    } catch (Throwable t) {
                        lastErr = t;
                    }
                }
            } catch (Throwable t) {
                lastErr = t;
            }

            final String url = out[0];
            final String html = out[1];
            final Throwable err = lastErr;
            MAIN.post(() -> {
                if (TextUtils.isEmpty(url) && TextUtils.isEmpty(html)) {
                    fail(callback, err == null ? "该网盘源未返回授权页面" : "授权请求失败：" + err.getMessage());
                    return;
                }
                try {
                    Intent intent = new Intent(context, WebAuthActivity.class);
                    intent.putExtra(WebAuthActivity.EXTRA_URL, url == null ? "" : url);
                    intent.putExtra(WebAuthActivity.EXTRA_HTML, html == null ? "" : html);
                    intent.putExtra(WebAuthActivity.EXTRA_TITLE, "网盘授权 / 配置中心");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    if (callback != null) callback.onOpen();
                } catch (Throwable t) {
                    fail(callback, "打开授权页失败：" + t.getMessage());
                }
            });
        });
    }

    private static void fail(Callback callback, String reason) {
        MAIN.post(() -> { if (callback != null) callback.onFail(reason); });
    }

    /** 优先按 key 命中，其次按名称包含“配置/网盘”的站点；都找不到时回退到软件内置配置中心。 */
    private static SourceBean findConfigSite() {
        try {
            List<SourceBean> sites = ApiConfig.get().getSourceBeanList();
            if (sites != null) {
                for (String key : PREFERRED_KEYS) {
                    for (SourceBean s : sites) {
                        if (s != null && key.equalsIgnoreCase(s.getKey())) return s;
                    }
                }
                for (SourceBean s : sites) {
                    if (s == null) continue;
                    String name = s.getName() == null ? "" : s.getName();
                    if (name.contains("配置") || name.contains("网盘") || name.contains("授权")) return s;
                }
            }
        } catch (Throwable ignored) {
        }
        // 三好TV：配置中心已从数据源列表移出，作为软件固定项内置，这里作为最终回退。
        return ApiConfig.get().getBuiltInConfigCenter();
    }

    /** 解析 spider.action 返回：http(s) URL、HTML 片段，或含 url/json 字段的 JSON。 */
    private static Result parse(String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        String s = raw.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return new Result(s, null);
        }
        if (s.startsWith("{") || s.startsWith("[")) {
            try {
                JSONObject obj = new JSONObject(s);
                String url = obj.optString("url", "");
                if (TextUtils.isEmpty(url)) url = obj.optString("jump", "");
                if (TextUtils.isEmpty(url)) url = obj.optString("login", "");
                if (!TextUtils.isEmpty(url) && (url.startsWith("http://") || url.startsWith("https://"))) {
                    return new Result(url, null);
                }
            } catch (Throwable ignored) {
            }
        }
        if (s.startsWith("<") || s.contains("<html") || s.contains("<script")) {
            return new Result(null, raw);
        }
        return null;
    }

    private static final class Result {
        final String url;
        final String html;
        Result(String url, String html) { this.url = url; this.html = html; }
    }
}
