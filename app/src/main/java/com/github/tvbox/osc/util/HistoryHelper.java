package com.github.tvbox.osc.util;

import com.orhanobut.hawk.Hawk;
import java.util.ArrayList;

public class HistoryHelper {
    private static final Integer[] hisNumArray = {30,50,100};
    private static final String API_LINE_SPLIT = "\t";

    /** 三好TV 内置的初始（默认）源地址——始终固定在配置历史第一条，且不可删除。 */
    public static final String DEFAULT_VIDEO_API = "https://raw.githubusercontent.com/advote/sanhaotv/main/video_source.json";
    public static final String DEFAULT_LIVE_API = "https://raw.githubusercontent.com/advote/sanhaotv/main/live_source.txt";
    private static final int API_HISTORY_MAX = 6;

    public static String getHistoryNumName(int index){
        Integer value = getHisNum(index);
        return value + "条";
    }

    public static int getHisNum(int index){
        Integer value = null;
        if(index>=0 && index < hisNumArray.length){
            value = hisNumArray[index];
        }else{
            value = hisNumArray[0];
        }
        return value;
    }

    public static void setSearchHistory(String title){
        // 读取历史记录
        ArrayList<String> history = Hawk.get(HawkConfig.SEARCH_HISTORY, new ArrayList<String>());
        history.remove(title);
        history.add(0, title);
        // 保证最多只保留 30 条，超过的就删除最后一条
        if (history.size() > 30) {
            history.remove(history.size() - 1);
        }
        Hawk.put(HawkConfig.SEARCH_HISTORY, history);
    }

    public static void clearSearchHistory(){
        Hawk.put(HawkConfig.SEARCH_HISTORY, new ArrayList<String>());
    }

    /**
     * 把初始地址固定到历史列表第一条：先移除已存在的初始地址，再插到索引 0。
     * 这样无论新增/删除/裁剪，初始地址永远位于第一条且不会被挤掉。
     */
    private static void pinDefaultFirst(ArrayList<String> history, String defaultApi) {
        if (defaultApi == null || defaultApi.isEmpty()) return;
        history.remove(defaultApi);
        history.add(0, defaultApi);
    }

    /** 判断某条历史是否为不可删除的初始地址。 */
    public static boolean isDefaultApi(String value) {
        return DEFAULT_VIDEO_API.equals(value) || DEFAULT_LIVE_API.equals(value);
    }

    /** 读取当前点播地址；空值自动回退到 GitHub 默认地址。 */
    public static String getVideoApi() {
        String value = Hawk.get(HawkConfig.API_URL, "");
        return value == null || value.trim().isEmpty() ? DEFAULT_VIDEO_API : value.trim();
    }

    /** 读取当前直播地址；空值自动回退到 GitHub 默认地址。 */
    public static String getLiveApi() {
        String value = Hawk.get(HawkConfig.LIVE_API_URL, "");
        return value == null || value.trim().isEmpty() ? DEFAULT_LIVE_API : value.trim();
    }

    /** 首次启动或地址被清空时，立即写入 GitHub 默认源，避免界面显示为空。 */
    public static void ensureDefaultSources() {
        ensureDefaultSource(HawkConfig.API_URL, DEFAULT_VIDEO_API);
        ensureDefaultSource(HawkConfig.LIVE_API_URL, DEFAULT_LIVE_API);
    }

    private static void ensureDefaultSource(String key, String defaultApi) {
        String value = Hawk.get(key, "");
        if (value == null || value.trim().isEmpty()) {
            Hawk.put(key, defaultApi);
        }
    }

    /** 兼容老用户：读取历史时若缺初始地址则补上并置顶。 */
    public static ArrayList<String> ensureApiHistory(String key, String defaultApi) {
        ArrayList<String> history = Hawk.get(key, new ArrayList<String>());
        pinDefaultFirst(history, defaultApi);
        if (history.size() > API_HISTORY_MAX) {
            history.subList(API_HISTORY_MAX, history.size()).clear();
        }
        Hawk.put(key, history);
        return history;
    }

    public static void setLiveApiHistory(String value){
        ArrayList<String> history = ensureApiHistory(HawkConfig.LIVE_API_HISTORY, DEFAULT_LIVE_API);
        if (value != null && !value.isEmpty() && !history.contains(value)) {
            history.add(1, value); // 插在初始地址之后
        }
        if (history.size() > API_HISTORY_MAX) {
            history.subList(API_HISTORY_MAX, history.size()).clear();
        }
        Hawk.put(HawkConfig.LIVE_API_HISTORY, history);
    }

    public static void setApiHistory(String value){
        ArrayList<String> history = ensureApiHistory(HawkConfig.API_HISTORY, DEFAULT_VIDEO_API);
        if (value != null && !value.isEmpty() && !history.contains(value)) {
            history.add(1, value); // 插在初始地址之后
        }
        if (history.size() > API_HISTORY_MAX) {
            history.subList(API_HISTORY_MAX, history.size()).clear();
        }
        Hawk.put(HawkConfig.API_HISTORY, history);
    }

    public static String buildApiLine(String name, String url) {
        String lineName = name == null ? "" : name.trim();
        String lineUrl = url == null ? "" : url.trim();
        if (lineName.isEmpty()) {
            lineName = lineUrl;
        }
        return lineName + API_LINE_SPLIT + lineUrl;
    }

    public static String getApiLineName(String value) {
        if (value == null) return "";
        int splitIndex = value.indexOf(API_LINE_SPLIT);
        String name = splitIndex >= 0 ? value.substring(0, splitIndex) : value;
        return name.trim();
    }

    public static String getApiLineUrl(String value) {
        if (value == null) return "";
        int splitIndex = value.indexOf(API_LINE_SPLIT);
        String url = splitIndex >= 0 ? value.substring(splitIndex + API_LINE_SPLIT.length()) : value;
        return url.trim();
    }

    public static boolean isApiLineUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String trimUrl = url.trim();
        ArrayList<String> apiLines = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        for (String apiLine : apiLines) {
            if (trimUrl.equals(getApiLineUrl(apiLine))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isApiLineSource(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String source = Hawk.get(HawkConfig.API_LINE_SOURCE, "");
        return url.trim().equals(source);
    }

    public static boolean isApiLineHistory(String url) {
        return isApiLineSource(url) || isApiLineUrl(url);
    }

    public static void clearApiLineList() {
        Hawk.put(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        Hawk.put(HawkConfig.API_LINE_SOURCE, "");
    }
}
