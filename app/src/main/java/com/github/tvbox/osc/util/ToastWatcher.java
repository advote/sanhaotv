package com.github.tvbox.osc.util;

import android.content.Context;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 三好TV：全局 Toast 文本监听。
 *
 * 网盘扫码由 spider（加固插件）内部异步完成，成功后由 spider 自己弹
 * “百度网盘登录成功”这类系统 Toast，APP 收不到任何回调，也无法通过
 * actionResult 感知。这里通过代理系统的 INotificationManager.enqueueToast，
 * 拦截所有 Toast 文本，让业务方据此即时更新界面状态（如“未扫码”→“已登录”）。
 *
 * 反射失败（不同系统版本差异）时静默跳过，不影响 Toast 正常显示。
 */
public final class ToastWatcher {

    public interface Listener {
        void onToast(CharSequence text);
    }

    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();
    private static volatile boolean sInstalled;

    /**
     * 三好TV：需要拦截改写的 Toast 关键词。
     * 第三方 spider 接口包（加固 jar）在接口加载时会弹“本接口免费分享”这类
     * 原作者宣示文案，与用户无关。命中关键词的 Toast 会被改写为
     * REPLACEMENT_TEXT 后再显示；改写失败则直接吞掉。
     */
    private static final String[] BLOCK_KEYWORDS = {
            "免费分享", "仅供学习", "仅供测试", "禁止倒卖", "请勿商用", "本接口", "接口免费"
    };

    /** 三好TV：命中宣示文案后展示的替换文案（改为空字符串则完全屏蔽）。 */
    public static final String REPLACEMENT_TEXT = "欢迎使用三好TV";

    public static void setListener(Listener l) {
        if (l != null && !sListeners.contains(l)) sListeners.add(l);
    }

    public static void removeListener(Listener l) {
        sListeners.remove(l);
    }

    public static void install(Context context) {
        if (sInstalled || context == null) return;
        sInstalled = true;
        try {
            Field field = Toast.class.getDeclaredField("sService");
            field.setAccessible(true);
            Object service = field.get(null);
            if (service == null) {
                // 触发一次空 Toast 以初始化 sService
                try {
                    Toast.makeText(context.getApplicationContext(), "", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
                service = field.get(null);
            }
            if (service == null) return;
            final Object target = service;
            Class<?>[] ifaces = target.getClass().getInterfaces();
            if (ifaces == null || ifaces.length == 0) return;
            Object proxy = Proxy.newProxyInstance(
                    target.getClass().getClassLoader(),
                    ifaces,
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method method, Object[] args) throws Throwable {
                            if (method.getName().startsWith("enqueueToast")) {
                                CharSequence text = extractText(args);
                                if (text != null) {
                                    if (shouldBlock(text)) {
                                        // 三好TV：命中宣示文案 —— 先尝试把 Toast 文本原地改写为
                                        // 替换文案（直接改 TN 里的字段，再照常转发给系统）。
                                        // 改写失败则返回 null 吞掉该 Toast，绝不显示原文案。
                                        if (!rewriteToastText(args)) {
                                            return null;
                                        }
                                    } else {
                                        for (Listener l : sListeners) {
                                            try {
                                                l.onToast(text);
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                    }
                                }
                            }
                            try {
                                return method.invoke(target, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        }
                    });
            field.set(null, proxy);
        } catch (Throwable ignored) {
        }
    }

    /** 判断 Toast 文本是否命中屏蔽关键词。 */
    private static boolean shouldBlock(CharSequence text) {
        if (text == null) return false;
        String t = text.toString();
        for (String k : BLOCK_KEYWORDS) {
            if (t.contains(k)) return true;
        }
        return false;
    }

    /**
     * 三好TV：把 Toast 参数对象里的文本原地改写为替换文案。
     * 优先改 TN（TransientNotification）的 mText 字段；若文本在 mNextView 的
     * TextView 里则调用其 setText。返回是否至少改写了一处。
     */
    private static boolean rewriteToastText(Object[] args) {
        if (args == null) return false;
        boolean changed = false;
        for (Object a : args) {
            if (a == null) continue;
            String cn = a.getClass().getName();
            if (cn.contains("TransientNotification") || cn.contains("TN")) {
                try {
                    Field mf = findField(a.getClass(), "mText");
                    if (mf != null) {
                        mf.setAccessible(true);
                        mf.set(a, REPLACEMENT_TEXT);
                        changed = true;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    Field vf = findField(a.getClass(), "mNextView");
                    if (vf != null) {
                        vf.setAccessible(true);
                        Object view = vf.get(a);
                        if (view instanceof android.view.ViewGroup) {
                            ((android.view.ViewGroup) view).removeAllViews();
                            android.widget.TextView tv = new android.widget.TextView(((android.view.View) view).getContext());
                            tv.setText(REPLACEMENT_TEXT);
                            tv.setTextColor(0xFFFFFFFF);
                            ((android.view.ViewGroup) view).addView(tv);
                            changed = true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return changed;
    }

    /** 从 enqueueToast 参数里取出 Toast 文本。 */
    private static CharSequence extractText(Object[] args) {
        if (args == null) return null;
        // 第一优先：ITransientNotification (TN) 对象里的 mText —— 各版本最稳定
        for (Object a : args) {
            if (a == null) continue;
            String cn = a.getClass().getName();
            if (cn.contains("TransientNotification") || cn.contains("TN")) {
                try {
                    Field mf = findField(a.getClass(), "mText");
                    if (mf != null) {
                        mf.setAccessible(true);
                        Object v = mf.get(a);
                        if (v instanceof CharSequence && ((CharSequence) v).length() > 0) {
                            return (CharSequence) v;
                        }
                    }
                } catch (Throwable ignored) {
                }
                // 部分版本文本在 mNextView(TextView) 里
                try {
                    Field vf = findField(a.getClass(), "mNextView");
                    if (vf != null) {
                        vf.setAccessible(true);
                        Object view = vf.get(a);
                        CharSequence t = textFromView(view);
                        if (t != null && t.length() > 0) return t;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // 次选：直接字符串参数（Android 11+ 的 enqueueToast 带 text 参数）
        for (Object a : args) {
            if (a instanceof CharSequence && ((CharSequence) a).length() > 0) {
                return (CharSequence) a;
            }
        }
        return null;
    }

    private static CharSequence textFromView(Object view) {
        if (view == null) return null;
        try {
            Class<?> c = view.getClass();
            while (c != null) {
                try {
                    Field tf = c.getDeclaredField("mText");
                    tf.setAccessible(true);
                    Object v = tf.get(view);
                    if (v instanceof CharSequence) return (CharSequence) v;
                } catch (Throwable ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Field findField(Class<?> c, String name) {
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (Throwable e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
