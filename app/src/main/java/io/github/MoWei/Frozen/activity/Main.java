package io.github.MoWei.Frozen.activity;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import io.github.MoWei.Frozen.AppInfoCache;
import io.github.MoWei.Frozen.BuildConfig;
import io.github.MoWei.Frozen.R;
import io.github.MoWei.Frozen.StaticData;
import io.github.MoWei.Frozen.Utils;
import io.github.MoWei.Frozen.databinding.ActivityMainBinding;

public class Main extends AppCompatActivity {

    @SuppressLint("UseCompatLoadingForDrawables")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkPrivacy(this);

        StaticData.am = (ActivityManager) this.getSystemService(ACTIVITY_SERVICE);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.navigation_home, R.id.navigation_config, R.id.navigation_logcat)
                .build();
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_activity_main);
        assert navHostFragment != null;
        NavController navController = navHostFragment.getNavController();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        NavigationUI.setupWithNavController(binding.navView, navController);

        new Thread(() -> AppInfoCache.refreshCache(this)).start();
    }

    @Override
    public void onResume() {
        super.onResume();
        findViewById(R.id.container).setBackground(StaticData.getBackgroundDrawable(this));
    }

    public static void checkPrivacy(Context context) {
        SharedPreferences sf = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        final String key = BuildConfig.VERSION_NAME + "isAccept";
        var isAccept = sf.getBoolean(key, false);
        if (isAccept) return;

        android.app.Dialog dialog = new android.app.Dialog(context);
        dialog.setContentView(R.layout.dialog_liquid_glass_text);
        Utils.setupCenteredDialogWindow(dialog, context);
        dialog.setCancelable(false);

        android.widget.TextView tvTitle = dialog.findViewById(R.id.dialog_title);
        android.widget.TextView tvMsg = dialog.findViewById(R.id.dialog_message);
        android.widget.Button btnConfirm = dialog.findViewById(R.id.dialog_btn_confirm);
        android.widget.Button btnCancel = dialog.findViewById(R.id.dialog_btn_cancel);

        if (tvTitle != null) tvTitle.setText(R.string.privacy_title);
        if (tvMsg != null) tvMsg.setText(R.string.privacy_content);

        if (btnCancel != null) {
            btnCancel.setVisibility(android.view.View.VISIBLE);
            btnCancel.setText(R.string.reject);
            btnCancel.setOnClickListener(v -> System.exit(0));
        }

        if (btnConfirm != null) {
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) btnConfirm.getLayoutParams();
            lp.width = 0;
            lp.weight = 1;
            btnConfirm.setLayoutParams(lp);
            btnConfirm.setText(R.string.accept);
            btnConfirm.setOnClickListener(v -> {
                var edit = sf.edit();
                edit.putBoolean(key, true);
                edit.apply();
                dialog.dismiss();
            });
        }

        dialog.show();
    }

}