package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

/**
 * 三好TV · 关于对话框
 * 版本信息从 BuildConfig 动态读取，随每次编译自动更新，无需手动维护。
 */
public class AboutDialog extends BaseDialog {

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_about);
        TextView tvVersionCode = findViewById(R.id.tvVersionCode);
        TextView tvVersionName = findViewById(R.id.tvVersionName);
        if (tvVersionCode != null) {
            tvVersionCode.setText("版本号=" + BuildConfig.VERSION_CODE);
        }
        if (tvVersionName != null) {
            tvVersionName.setText("版本名称=" + BuildConfig.VERSION_NAME);
        }
    }
}
