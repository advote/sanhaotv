package com.github.tvbox.osc.util;

import android.content.Context;
import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
 *   <li><b>内容更新检测</b>：若云端 sources.json 里的 video_version / live_version
 *       高于本地记录，则下载最新源内容覆盖本地缓存文件，使 App 无需重装即可用上新源；</li>
 *   <li>用户手动设置的源拥有最高优先级：只要本地地址与云端源不同，就绝不覆盖。</li>
 * </ul>
 *
 * <p>云端配置格式（sources.json）：</p>
 * <pre>
 * {
 *   "video": ".../video_source.json",
 *   "live":  ".../live_source.txt",
 *   "video_version": 2026112001,
 *   "live_version":  2026112001
 * }
 * </pre>
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

    /** 本地已应用的云端内容版本号。 */
    public static final String KEY_VIDEO_VERSION = "sanhao_video_version";
    public static final String KEY_LIVE_VERSION = "sanhao_live_version";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private RemoteSourceHelper() {}

    /**
     * 在 App 启动时调用（异步，不阻塞 UI）。
     * 依次执行：地址失效恢复 → 内容版本更新检测。
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
                try {
                    checkForUpdates();
                } catch (Throwable t) {
                    LOG.e(TAG + " update check failed: " + t.getMessage());
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

    /**
     * 内容更新检测：拉取云端配置，对比 video_version / live_version。
     * 版本升高且当前生效源仍是云端源时，下载最新内容覆盖本地缓存文件。
     */
    private static void checkForUpdates() {
        String configUrl = Hawk.get(KEY_CLOUD_CONFIG, DEFAULT_CONFIG_URL);
        if (TextUtils.isEmpty(configUrl)) configUrl = DEFAULT_CONFIG_URL;

        String json = httpGet(configUrl);
        if (TextUtils.isEmpty(json)) {
            LOG.i(TAG + " update check: fetch config failed");
            return;
        }
        CloudConfig cloud = parseCloudConfig(json);
        if (cloud == null) {
            LOG.i(TAG + " update check: parse config failed");
            return;
        }

        boolean liveUpdated = false;
        boolean videoUpdated = false;

        // ---- 直播源更新 ----
        if (!TextUtils.isEmpty(cloud.live) && cloud.liveVersion > 0) {
            long localVer = Hawk.get(KEY_LIVE_VERSION, 0L);
            String currentLive = Hawk.get(HawkConfig.LIVE_API_URL, "");
            if (cloud.liveVersion > localVer && isSameSource(currentLive, cloud.live)) {
                String content = httpGet(cloud.live);
                if (!TextUtils.isEmpty(content) && looksLikeLiveSource(content)) {
                    if (writeCache(cloud.live, content)) {
                        Hawk.put(KEY_LIVE_VERSION, cloud.liveVersion);
                        liveUpdated = true;
                        LOG.i(TAG + " live source updated to version " + cloud.liveVersion);
                    }
                } else {
                    LOG.e(TAG + " live source download invalid, skip update");
                }
            }
        }

        // ---- 点播源更新 ----
        if (!TextUtils.isEmpty(cloud.video) && cloud.videoVersion > 0) {
            long localVer = Hawk.get(KEY_VIDEO_VERSION, 0L);
            String currentVideo = Hawk.get(HawkConfig.API_URL, "");
            if (cloud.videoVersion > localVer && isSameSource(currentVideo, cloud.video)) {
                String content = httpGet(cloud.video);
                if (!TextUtils.isEmpty(content) && looksLikeVideoSource(content)) {
                    if (writeCache(cloud.video, content)) {
                        Hawk.put(KEY_VIDEO_VERSION, cloud.videoVersion);
                        videoUpdated = true;
                        LOG.i(TAG + " video source updated to version " + cloud.videoVersion);
                    }
                } else {
                    LOG.e(TAG + " video source download invalid, skip update");
                }
            }
        }

        // 直播缓存被更新：清空 ApiConfig 内存列表，使下次进入直播页时重新读缓存
        if (liveUpdated) {
            try {
                ApiConfig.get().clearLiveCache();
                LOG.i(TAG + " live cache cleared, will reload on next enter");
            } catch (Throwable t) {
                LOG.e(TAG + " clear live cache failed: " + t.getMessage());
            }
        }
        if (videoUpdated) {
            try {
                // 点播配置在 HomeActivity 每次进入按 needRefresh 处理；这里仅记录，重启后生效
                LOG.i(TAG + " video cache updated, applies on next config load");
            } catch (Throwable ignored) {}
        }
    }

    /** 判断当前生效源是否就是云端源（去除空白与末尾斜杠后比较），避免覆盖用户手动改的源。 */
    private static boolean isSameSource(String current, String cloud) {
        if (TextUtils.isEmpty(current)) return true; // 未设置时允许云端写入
        return normalize(current).equals(normalize(cloud));
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** 简单校验直播源内容：含 #genre# 或 http 链接或 EXTINF。 */
    private static boolean looksLikeLiveSource(String content) {
        return content.contains("#genre#") || content.contains("#EXTM3U")
                || content.contains("http://") || content.contains("https://");
    }

    /** 简单校验点播源内容：应为 JSON 且含 urls / api 结构。 */
    private static boolean looksLikeVideoSource(String content) {
        String t = content.trim();
        boolean isJson = (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
        return isJson && (t.contains("url") || t.contains("api"));
    }

    /**
     * 把内容写入 ApiConfig 使用的缓存文件：filesDir / MD5(sourceUrl)。
     * 与 ApiConfig.loadConfig/loadLiveConfig 的缓存路径保持一致。
     */
    private static boolean writeCache(String sourceUrl, String content) {
        File dir = App.getInstance().getFilesDir();
        File cache = new File(dir.getAbsolutePath() + "/" + MD5.encode(sourceUrl));
        FileOutputStream fos = null;
        try {
            if (!dir.exists()) dir.mkdirs();
            File tmp = new File(cache.getAbsolutePath() + ".tmp");
            fos = new FileOutputStream(tmp);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
            fos = null;
            if (cache.exists()) cache.delete();
            return tmp.renameTo(cache);
        } catch (Throwable t) {
            LOG.e(TAG + " writeCache failed: " + t.getMessage());
            return false;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 判断一个源地址是否可用：空地址视为失效；http(s) 地址做一次轻量 GET 探测。 */
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

    /** 简单的 HTTP GET，返回字符串；失败返回 null。上限 8MB。 */
    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        InputStream is = null;
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
            is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            int total = 0;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
                total += len;
                if (total > 8 * 1024 * 1024) break; // 上限 8MB
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LOG.e(TAG + " httpGet failed: " + t.getMessage());
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /** 解析云端配置，兼容 {video, live, video_version, live_version} 与 urls 数组。 */
    private static CloudConfig parseCloudConfig(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            CloudConfig cfg = new CloudConfig();
            if (obj.has("video")) cfg.video = obj.optString("video", "");
            if (obj.has("live")) cfg.live = obj.optString("live", "");
            if (obj.has("video_version")) cfg.videoVersion = obj.optLong("video_version", 0L);
            if (obj.has("live_version")) cfg.liveVersion = obj.optLong("live_version", 0L);

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
        long videoVersion;
        long liveVersion;
    }
}
