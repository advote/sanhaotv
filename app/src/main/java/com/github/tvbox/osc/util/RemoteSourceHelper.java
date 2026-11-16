package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 三好TV · 云端源自动同步助手
 *
 * <p>职责：</p>
 * <ul>
 *   <li>应用启动时校验本地缓存的「视频源 / 直播源」地址是否仍然可用；</li>
 *   <li>若本地地址为空或网络请求失败（失效），自动从 GitHub 托管的
 *       sources.json 拉取最新源地址并写回本地；</li>
 *   <li>用户手动设置的源拥有最高优先级：只要本地地址可访问，就绝不被云端覆盖。</li>
 * </ul>
 *
 * <p>云端配置格式（sources.json）：</p>
 * <pre>
 * {
 *   "video": "https://raw.githubusercontent.com/用户名/sanhaotv/main/video_source.json",
 *   "live":  "https://raw.githubusercontent.com/用户名/sanhaotv/main/live_source.txt"
 * }
 * </pre>
 * 也支持 urls 数组的多线路形式，取第一条可用线路。
 */
public final class RemoteSourceHelper {

    private static final String TAG = "RemoteSourceHelper";

    /** GitHub 上托管的源配置文件地址（仓库内 sources.json 的 raw 链接）。 */
    public static final String DEFAULT_CONFIG_URL =
            "https://raw.githubusercontent.com/advote/sanhaotv/main/sources.json";

    /** 配置文件地址本身也支持用户在设置里改，键名存于 Hawk。 */
    public static final String KEY_CLOUD_CONFIG = "sanhao_cloud_config_url";

    /** 记录云端源是否已被应用过一次，避免每次启动重复探测网络。 */
    public static final String KEY_LAST_CLOUD_SYNC = "sanhao_last_cloud_sync";

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 6000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private RemoteSourceHelper() {}

    /**
     * 在 App 启动时调用（异步，不阻塞 UI）。
     */
    public static void syncOnStartup() {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    doSync();
                } catch (Throwable t) {
                    LOG.e(TAG + " sync failed: " + t.getMessage());
                }
            }
        });
    }

    /** 同步主线程逻辑；返回是否成功应用了云端配置。 */
    private static boolean doSync() {
        String video = Hawk.get(HawkConfig.API_URL, "");
        String live = Hawk.get(HawkConfig.LIVE_API_URL, "");

        boolean videoOk = isUrlReachable(video);
        boolean liveOk = isUrlReachable(live);

        // 两者都可用：用户手动设置的源优先，不做任何改动
        if (videoOk && liveOk) {
            LOG.i(TAG + " local sources reachable, keep local config");
            return false;
        }

        String configUrl = Hawk.get(KEY_CLOUD_CONFIG, DEFAULT_CONFIG_URL);
        if (TextUtils.isEmpty(configUrl)) configUrl = DEFAULT_CONFIG_URL;

        String json = httpGet(configUrl);
        if (TextUtils.isEmpty(json)) {
            LOG.i(TAG + " fetch cloud config failed, keep local config");
            return false;
        }

        CloudConfig cloud = parseCloudConfig(json);
        if (cloud == null) {
            LOG.i(TAG + " parse cloud config failed");
            return false;
        }

        boolean changed = false;
        if (!videoOk && !TextUtils.isEmpty(cloud.video)) {
            if (!cloud.video.equals(video)) {
                Hawk.put(HawkConfig.API_URL, cloud.video);
                HistoryHelper.setApiHistory(cloud.video);
                changed = true;
                LOG.i(TAG + " video source restored from cloud: " + cloud.video);
            }
        }
        if (!liveOk && !TextUtils.isEmpty(cloud.live)) {
            if (!cloud.live.equals(live)) {
                Hawk.put(HawkConfig.LIVE_API_URL, cloud.live);
                HistoryHelper.setLiveApiHistory(cloud.live);
                changed = true;
                LOG.i(TAG + " live source restored from cloud: " + cloud.live);
            }
        }
        if (changed) {
            Hawk.put(KEY_LAST_CLOUD_SYNC, System.currentTimeMillis());
        }
        return changed;
    }

    /** 判断一个源地址是否可用：空地址视为失效；http(s) 地址做一次轻量 HEAD/GET 探测。 */
    private static boolean isUrlReachable(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            // 本地文件 / 特殊协议，交由 App 自身处理，这里视为有效以免误覆盖
            return true;
        }
        HttpURLConnection conn = null;
        try {
            URL target = new URL(trimmed);
            conn = (HttpURLConnection) target.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; SanHaoTV/1.0)");
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Throwable t) {
            LOG.i(TAG + " unreachable: " + trimmed + " (" + t.getMessage() + ")");
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 简单的 HTTP GET，返回字符串；失败返回 null。 */
    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; SanHaoTV/1.0)");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return null;
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 1024 * 1024) break; // 上限 1MB，防止异常大文件
            }
            return sb.toString();
        } catch (Throwable t) {
            LOG.e(TAG + " httpGet failed: " + t.getMessage());
            return null;
        } finally {
            try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /** 解析云端配置，兼容 {video, live} 与 {urls:[...]} 两种结构。 */
    private static CloudConfig parseCloudConfig(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            CloudConfig cfg = new CloudConfig();
            if (obj.has("video")) cfg.video = obj.optString("video", "");
            if (obj.has("live")) cfg.live = obj.optString("live", "");

            if (TextUtils.isEmpty(cfg.video) && obj.has("urls")) {
                cfg.video = firstUrl(obj.optJSONArray("urls"));
            }
            if (TextUtils.isEmpty(cfg.live) && obj.has("live_urls")) {
                cfg.live = firstUrl(obj.optJSONArray("live_urls"));
            }
            if (TextUtils.isEmpty(cfg.video) && TextUtils.isEmpty(cfg.live)) return null;
            return cfg;
        } catch (Throwable t) {
            LOG.e(TAG + " parse json failed: " + t.getMessage());
            return null;
        }
    }

    /** urls 数组元素可以是字符串，也可以是含 url/api 字段的对象。 */
    private static String firstUrl(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            if (o instanceof String) {
                String s = ((String) o).trim();
                if (!s.isEmpty()) return s;
            } else if (o instanceof JSONObject) {
                JSONObject jo = (JSONObject) o;
                String u = jo.optString("url", "");
                if (TextUtils.isEmpty(u)) u = jo.optString("api", "");
                if (!TextUtils.isEmpty(u)) return u.trim();
            }
        }
        return "";
    }

    private static final class CloudConfig {
        String video;
        String live;
    }
}
