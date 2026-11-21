package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.adapter.ApiHistoryDialogAdapter;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 描述
 *
 * @author pj567
 * @since 2020/12/27
 */
public class ApiDialog extends BaseDialog {
    private ImageView ivQRCode;
    private TextView tvAddress;
    private EditText inputApi;
    private EditText inputApiLive;
    private View inputConfirm;

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_API_URL_CHANGE) {
            inputApi.setText((String) event.obj);
            inputApiLive.setText((String) event.obj);
        } else if (event.type == RefreshEvent.TYPE_LIVE_API_URL_CHANGE) {
            inputApiLive.setText((String) event.obj);
        }
    }

    public ApiDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_api);
        setCanceledOnTouchOutside(false);
        ivQRCode = findViewById(R.id.ivQRCode);
        tvAddress = findViewById(R.id.tvAddress);
        inputApi = findViewById(R.id.input);
        inputApiLive = findViewById(R.id.inputLive);
        inputConfirm = findViewById(R.id.inputConfirm);
        //内置网络接口在此处添加
        inputApi.setText(Hawk.get(HawkConfig.API_URL, ""));
        inputApiLive.setText(Hawk.get(HawkConfig.LIVE_API_URL, Hawk.get(HawkConfig.API_URL)));
        findViewById(R.id.inputSubmit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onLocalConfig(false);
            }
        });
        findViewById(R.id.inputSubmitLive).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onLocalConfig(true);
            }
        });
        inputConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndDismiss();
            }
        });
        // 三好TV：点播源历史（最近6条）
        findViewById(R.id.vodHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<String> history = HistoryHelper.ensureApiHistory(HawkConfig.API_HISTORY, HistoryHelper.DEFAULT_VIDEO_API);
                if (history.isEmpty()) {
                    Toast.makeText(getContext(), "点播历史为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                String current = Hawk.get(HawkConfig.API_URL, "");
                int idx = 0;
                if (history.contains(current)) idx = history.indexOf(current);
                ApiHistoryDialog dialog = new ApiHistoryDialog(getContext());
                dialog.setTip("点播历史配置");
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String value) {
                        dialog.dismiss();
                        // 立即应用该历史地址并触发重新加载
                        applyHistoryAddress(value, false);
                    }

                    @Override
                    public void del(String value, ArrayList<String> data) {
                        // 保险：删除后仍确保初始地址置顶
                        data.remove(HistoryHelper.DEFAULT_VIDEO_API);
                        data.add(0, HistoryHelper.DEFAULT_VIDEO_API);
                        Hawk.put(HawkConfig.API_HISTORY, data);
                    }
                }, history, idx);
                dialog.show();
            }
        });
        findViewById(R.id.apiHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<String> history = HistoryHelper.ensureApiHistory(HawkConfig.LIVE_API_HISTORY, HistoryHelper.DEFAULT_LIVE_API);
                if (history.isEmpty()){
                    Toast.makeText(getContext(), "直播历史为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                String current = Hawk.get(HawkConfig.LIVE_API_URL, "");
                int idx = 0;
                if (history.contains(current))
                    idx = history.indexOf(current);
                ApiHistoryDialog dialog = new ApiHistoryDialog(getContext());
                dialog.setTip("直播历史配置");
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String value) {
                        dialog.dismiss();
                        // 立即应用该历史地址并触发重新加载
                        applyHistoryAddress(value, true);
                    }

                    @Override
                    public void del(String value, ArrayList<String> data) {
                        // 保险：删除后仍确保初始地址置顶
                        data.remove(HistoryHelper.DEFAULT_LIVE_API);
                        data.add(0, HistoryHelper.DEFAULT_LIVE_API);
                        Hawk.put(HawkConfig.LIVE_API_HISTORY, data);
                    }
                }, history, idx);
                dialog.show();
            }
        });
        findViewById(R.id.storagePermission).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (XXPermissions.isGranted(getContext(), Permission.Group.STORAGE)) {
                    Toast.makeText(getContext(), "已获得存储权限", Toast.LENGTH_SHORT).show();
                } else {
                    XXPermissions.with(getContext())
                            .permission(Permission.Group.STORAGE)
                            .request(new OnPermissionCallback() {
                                @Override
                                public void onGranted(List<String> permissions, boolean all) {
                                    if (all) {
                                        Toast.makeText(getContext(), "已获得存储权限", Toast.LENGTH_SHORT).show();
                                    }
                                }

                                @Override
                                public void onDenied(List<String> permissions, boolean never) {
                                    if (never) {
                                        Toast.makeText(getContext(), "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                                        XXPermissions.startPermissionActivity((Activity) getContext(), permissions);
                                    } else {
                                        Toast.makeText(getContext(), "获取存储权限失败", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                }
            }
        });
        inputApi.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_NEXT || isSoftKeyboardEnter(actionId, event)) {
                    inputApiLive.requestFocus();
                    inputApiLive.setSelection(inputApiLive.getText() == null ? 0 : inputApiLive.getText().length());
                    return true;
                }
                return false;
            }
        });
        inputApiLive.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE || isSoftKeyboardEnter(actionId, event)) {
                    focusConfirm();
                    return true;
                }
                return false;
            }
        });
        refreshQRCode();
    }

    private boolean isSoftKeyboardEnter(int actionId, KeyEvent event) {
        return actionId == EditorInfo.IME_NULL
                && event != null
                && event.getAction() == KeyEvent.ACTION_UP
                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
    }

    private void focusConfirm() {
        InputMethodManager imm = (InputMethodManager) inputApiLive.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(inputApiLive.getWindowToken(), 0);
        inputApiLive.clearFocus();
        inputConfirm.requestFocus();
    }

    private void refreshQRCode() {
        try {
            String address = ControlManager.get().getAddress(false);
            if (address == null) address = "";
            tvAddress.setText(String.format("手机/电脑扫描上方二维码或者直接浏览器访问地址\n%s", address));
            int size = AutoSizeUtils.mm2px(getContext(), 300);
            if (size <= 0) size = 300;
            Bitmap bmp = QRCodeGen.generateBitmap(address + "api.html", size, size);
            if (bmp != null && ivQRCode != null) {
                ivQRCode.setImageBitmap(bmp);
            }
        } catch (Throwable t) {
            // 二维码生成失败不应导致配置弹窗/应用崩溃
            if (tvAddress != null) {
                tvAddress.setText("扫码配置暂不可用，请直接在下方输入框填写接口地址");
            }
        }
    }

    private void saveAndDismiss() {
        String newApi = inputApi.getText().toString().trim();
        String newLiveApi = inputApiLive.getText().toString().trim();
        if (!newApi.isEmpty()) {
            HistoryHelper.setApiHistory(newApi);
            if (!newApi.equals(Hawk.get(HawkConfig.API_URL, newApi))) {
                inputApiLive.setText(newApi);
                newLiveApi = newApi;
            }
        }
        if (!newLiveApi.isEmpty()) {
            HistoryHelper.setLiveApiHistory(newLiveApi);
        }
        Hawk.put(HawkConfig.LIVE_API_URL, newLiveApi);
        if (listener != null) listener.onchange(newApi);
        dismiss();
    }

    /**
     * 三好TV：点击历史记录后立即把该地址应用为当前生效源。
     * 之前仅 setText + Hawk.put 并关闭子弹窗，未触发 onchange/重启，导致点了不生效。
     *
     * @param value 历史中的地址
     * @param live  true=直播地址，false=点播地址
     */
    private void applyHistoryAddress(String value, boolean live) {
        if (value == null || value.trim().isEmpty()) return;
        value = value.trim();
        if (live) {
            // 直播：写入 LIVE_API_URL + 历史，然后借 onchange 触发重启加载（用当前点播地址作为参数，避免误改点播源）
            inputApiLive.setText(value);
            Hawk.put(HawkConfig.LIVE_API_URL, value);
            HistoryHelper.setLiveApiHistory(value);
            String currentVideo = Hawk.get(HawkConfig.API_URL, "");
            if (listener != null && currentVideo != null && !currentVideo.isEmpty()) {
                listener.onchange(currentVideo);
            }
        } else {
            // 点播：设置输入框 + 历史，onchange 会写入 API_URL 并重启加载
            inputApi.setText(value);
            HistoryHelper.setApiHistory(value);
            if (listener != null) listener.onchange(value);
        }
        dismiss();
    }

    public void setOnListener(OnListener listener) {
        this.listener = listener;
    }

    public void setLocalApi(String api, boolean live) {
        if (live) {
            inputApiLive.setText(api);
        } else {
            inputApi.setText(api);
        }
    }

    OnListener listener = null;

    public interface OnListener {
        void onchange(String api);

        void onLocalConfig(boolean live);
    }
}
