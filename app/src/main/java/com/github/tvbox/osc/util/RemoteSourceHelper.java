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

    /** 远端文件轻量指纹：ETag + Last-Modified + Content-Length，用于判断是否需要重新下载。 */
    public static final String KEY_VIDEO_REMOTE_META = "sanhao_video_remote_meta";
    public static final String KEY_LIVE_REMOTE_META = "sanhao_live_remote_meta";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private RemoteSourceHelper() {}

    /**
     * 在 App 启动时调用（异步，不阻塞 UI）。
     *
     * <p>2026-11-21 优化：不再每次启动都 GET 直播/点播源全文。
     * 启动流程只做两件轻量操作：</p>
     * <ol>
     *   <li>读取很小的 sources.json，必要时恢复空的源地址；</li>
     *   <li>对 live_source.txt / video_source.json 发 HEAD 请求，仅取 ETag / Last-Modified /
     *       Content-Length 与本地记录比对；只有确认远端文件变化时才删除旧缓存，
     *       让 TVBox 原生流程重新下载一次。</li>
     * </ol>
     */
    /** 三好TV：启动时 GitHub 更新检测的总时限（毫秒）。超过即放弃检测，使用本地源文件。 */
    public static final long STARTUP_SYNC_TIMEOUT_MS = 15000;

    /** 三好TV：与 EXECUTOR 分离的限时工作池，避免单线程池内自等待死锁。 */
    private static final ExecutorService SYNC_WORKER = Executors.newSingleThreadExecutor();

    /** 三好TV：云端更新检测的最小间隔（6 小时）。间隔内锁屏/解锁恢复不重新下载源文件。 */
    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    /**
     * 三好TV：上次云端更新检测的时刻（单调时钟 SystemClock.elapsedRealtime）。
     * 绝不使用 System.currentTimeMillis()：设备系统时间一旦被改小/倒退，
     * 时间差判断就会失效，导致每次锁屏冷启动都重复检测并重新下载源文件。
     */
    public static final String KEY_LAST_CHECK_TIME = "sanhao_last_check_time";
    /** 三好TV：是否已完成过首次检测（持久化；未设置时视为首次启动）。 */
    public static final String KEY_HAS_CHECKED_ONCE = "sanhao_has_checked_once";
    /** 三好TV：本进程是否已做过更新检测（进程内去重）。 */
    private static volatile boolean sCheckedThisProcess = false;

    public static void syncOnStartup() {
        // 三好TV：严格按用户要求控制检测下载：
        //   规则 1 —— 只在第一次启动时检测并下载；
        //   规则 2 —— 之后的检测必须与上一次间隔 >= 6 小时，间隔内一律用本地文件。
        // 时间一律用单调时钟 elapsedRealtime（设备重启会归零，归零后视为新周期，
        // 重新计算首次），绝不受用户可修改的系统时间影响。
        if (sCheckedThisProcess) {
            LOG.i(TAG + " update check skipped: already checked in this process");
            return;
        }
        sCheckedThisProcess = true;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean checkedOnce = Hawk.get(KEY_HAS_CHECKED_ONCE, false);
        long lastCheck = Hawk.get(KEY_LAST_CHECK_TIME, 0L);

        // 已检测过、且本次距上次不足 6 小时 → 跳过。
        // 注意 elapsedRealtime 在设备重启后会归零，此时 now < lastCheck，
        // 视为全新的开机周期，允许再检测一次（新周期的“首次”）。
        if (checkedOnce && now >= lastCheck && (now - lastCheck) < CHECK_INTERVAL_MS) {
            LOG.i(TAG + " update check skipped: only-once / within 6h interval");
            return;
        }
        // 通过关卡：立即落标记再检测，确保即使检测失败/超时，6 小时内也不会重复触发。
        Hawk.put(KEY_HAS_CHECKED_ONCE, true);
        Hawk.put(KEY_LAST_CHECK_TIME, now);
        LOG.i(TAG + " update check allowed: first launch or 6h elapsed");
        // 三好TV：整体硬限时 15 秒。网络差（DNS 失败 / TLS 抖动）时 HTTP 超时
        // 会层层叠加，把启动等待页拖到几十秒；超时后放弃检测，直播与点播
        // 直接读取本地缓存或 APK 内置文件，绝不阻塞启动。
        final Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.concurrent.Future<?> task = SYNC_WORKER.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            checkForUpdates();
                        } catch (Throwable t) {
                            LOG.e(TAG + " update check failed: " + t.getMessage());
                        }
                    }
                });
                try {
                    task.get(STARTUP_SYNC_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    task.cancel(true);
                    LOG.i(TAG + " update check aborted after " + STARTUP_SYNC_TIMEOUT_MS + "ms, use local sources");
                } catch (Throwable t) {
                    LOG.e(TAG + " update check interrupted: " + t.getMessage());
                }
            }
        }, "sanhao-sync-watchdog");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * 更新检测：小配置 + HEAD 元数据比对，不下载源文件内容。
     *
     * <p>重要：早期版本每次启动都会对直播/点播地址做 GET 可达性探测，虽然不写入缓存，
     * 但仍会把完整文件拉下来，浪费流量。现在改为 HEAD，只读取响应头。
     * 若 HEAD 不可用（少数自定义源不支持），再退回 version 字段判断。
     * 用户手动设置的非云端源不会覆盖，也不会触发自动更新。</p>
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

        restoreEmptySource(HawkConfig.API_URL, cloud.video, KEY_VIDEO_REMOTE_META);
        restoreEmptySource(HawkConfig.LIVE_API_URL, cloud.live, KEY_LIVE_REMOTE_META);

        // ---- 直播源：HEAD 指纹变化才清理缓存，交由原生流程重拉 ----
        if (!TextUtils.isEmpty(cloud.live)) {
            String currentLive = Hawk.get(HawkConfig.LIVE_API_URL, "");
            if (isSameSource(currentLive, cloud.live)) {
                RemoteMeta meta = httpHead(cacheBust(cloud.live));
                boolean changed = isRemoteChanged(cloud.live, KEY_LIVE_REMOTE_META, cloud.liveVersion, KEY_LIVE_VERSION, meta);
                if (changed && deleteCache(cloud.live)) {
                    saveRemoteMeta(KEY_LIVE_REMOTE_META, meta, cloud.liveVersion, KEY_LIVE_VERSION);
                    LOG.i(TAG + " live cache removed, will refetch on next enter");
                    // 清空内存直播列表，使下次进入直播页重新加载
                    try {
                        ApiConfig.get().clearLiveCache();
                    } catch (Throwable t) {
                        LOG.e(TAG + " clear live cache failed: " + t.getMessage());
                    }
                } else if (!changed) {
                    LOG.i(TAG + " live source unchanged, skip download");
                }
            }
        }

        // ---- 点播源：HEAD 指纹变化才清理缓存与多源切换状态 ----
        if (!TextUtils.isEmpty(cloud.video)) {
            String currentVideo = Hawk.get(HawkConfig.API_URL, "");
            if (isSameSource(currentVideo, cloud.video)) {
                RemoteMeta meta = httpHead(cacheBust(cloud.video));
                boolean changed = isRemoteChanged(cloud.video, KEY_VIDEO_REMOTE_META, cloud.videoVersion, KEY_VIDEO_VERSION, meta);
                if (changed && deleteCache(cloud.video)) {
                    // 清除多源集合的线路切换状态，避免因旧的子源索引导致加载异常
                    try {
                        HistoryHelper.clearApiLineList();
                    } catch (Throwable ignored) {}
                    saveRemoteMeta(KEY_VIDEO_REMOTE_META, meta, cloud.videoVersion, KEY_VIDEO_VERSION);
                    LOG.i(TAG + " video cache removed, will refetch on next enter");
                } else if (!changed) {
                    LOG.i(TAG + " video source unchanged, skip download");
                }
            }
        }
    }

    /** 本地地址为空时从云端配置恢复，用户手动设置的地址不覆盖。 */
    private static void restoreEmptySource(String key, String cloudUrl, String metaKey) {
        String current = Hawk.get(key, "");
        if (TextUtils.isEmpty(current) && !TextUtils.isEmpty(cloudUrl)) {
            Hawk.put(key, cloudUrl);
            // 新地址视为首次使用，清掉旧指纹，让后续检查重新记录
            Hawk.put(metaKey, "");
            if (HawkConfig.API_URL.equals(key)) HistoryHelper.setApiHistory(cloudUrl);
            else HistoryHelper.setLiveApiHistory(cloudUrl);
            LOG.i(TAG + " source restored from cloud: " + cloudUrl);
        }
    }

    /** 只用 HEAD 响应头判断远端文件是否变化；HEAD 不支持时用版本字段兜底。 */
    private static boolean isRemoteChanged(String url, String metaKey, long cloudVersion, String versionKey, RemoteMeta meta) {
        String oldMeta = Hawk.get(metaKey, "");
        long localVersion = Hawk.get(versionKey, 0L);
        String newMeta = meta == null ? "" : meta.signature();
        if (!TextUtils.isEmpty(newMeta)) {
            // 首次运行没有旧指纹：若本地已有缓存且版本未升高，则先记录指纹，不重复下载
            if (TextUtils.isEmpty(oldMeta)) {
                if (localVersion <= 0 || cloudVersion > localVersion) return true;
                Hawk.put(metaKey, newMeta);
                if (cloudVersion > localVersion) Hawk.put(versionKey, cloudVersion);
                LOG.i(TAG + " remote meta initialized, skip download: " + url);
                return false;
            }
            return !newMeta.equals(oldMeta);
        }
        // HEAD 失败时退回版本字段；版本号未变则绝不下载
        return cloudVersion > 0 && cloudVersion > localVersion;
    }

    private static void saveRemoteMeta(String metaKey, RemoteMeta meta, long cloudVersion, String versionKey) {
        if (meta != null && !TextUtils.isEmpty(meta.signature())) Hawk.put(metaKey, meta.signature());
        if (cloudVersion > 0) Hawk.put(versionKey, cloudVersion);
    }

    /** HEAD 请求：只取响应头，不读取 body，避免下载源文件内容。 */
    private static RemoteMeta httpHead(String urlStr) {
        java.net.HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("HEAD");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; SanHaoTV/1.0)");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return null;
            String etag = conn.getHeaderField("ETag");
            String lastModified = conn.getHeaderField("Last-Modified");
            long length = conn.getHeaderFieldLong("Content-Length", -1L);
            if (TextUtils.isEmpty(etag) && TextUtils.isEmpty(lastModified) && length <= 0) return null;
            return new RemoteMeta(etag, lastModified, length);
        } catch (Throwable t) {
            LOG.i(TAG + " httpHead failed: " + t.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 给 raw 链接加时间戳，绕过 CDN 缓存，保证拿到最新 ETag。 */
    private static String cacheBust(String url) {
        if (TextUtils.isEmpty(url)) return url;
        return url + (url.contains("?") ? "&" : "?") + "_ts=" + System.currentTimeMillis();
    }

    private static final class RemoteMeta {
        final String etag;
        final String lastModified;
        final long length;

        RemoteMeta(String etag, String lastModified, long length) {
            this.etag = etag == null ? "" : etag.trim();
            this.lastModified = lastModified == null ? "" : lastModified.trim();
            this.length = length;
        }

        String signature() {
            return etag + "|" + lastModified + "|" + length;
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
     * 删除 ApiConfig 使用的缓存文件：filesDir / MD5(sourceUrl)。
     * 删除后 App 走原生 loadConfig/loadLiveConfig 流程会自动重新从网络拉取最新内容，
     * 完全复用 TVBox 原生解析逻辑，避免手工写缓存带来的格式/并发问题。
     */
    private static boolean deleteCache(String sourceUrl) {
        try {
            File dir = App.getInstance().getFilesDir();
            File cache = new File(dir.getAbsolutePath() + "/" + MD5.encode(sourceUrl));
            boolean deleted = false;
            if (cache.exists()) deleted = cache.delete();
            // 同时清理可能残留的临时文件
            File tmp = new File(cache.getAbsolutePath() + ".tmp");
            if (tmp.exists()) tmp.delete();
            return deleted || !cache.exists();
        } catch (Throwable t) {
            LOG.e(TAG + " deleteCache failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * 把内容写入 ApiConfig 使用的缓存文件：filesDir / MD5(sourceUrl)。
     * 与 ApiConfig.loadConfig/loadLiveConfig 的缓存路径保持一致。
     * 现已不再使用，保留仅为兼容。
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
