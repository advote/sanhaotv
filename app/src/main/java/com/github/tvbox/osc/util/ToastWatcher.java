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
                                    for (Listener l : sListeners) {
                                        try {
                                            l.onToast(text);
                                        } catch (Throwable ignored) {
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
