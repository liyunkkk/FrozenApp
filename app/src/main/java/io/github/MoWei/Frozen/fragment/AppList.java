package io.github.MoWei.Frozen.fragment;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.List;

import io.github.MoWei.Frozen.FreezeDetailScanner;
import io.github.MoWei.Frozen.R;
import io.github.MoWei.Frozen.Utils;
import io.github.MoWei.Frozen.activity.AppConfigActivity;
import io.github.MoWei.Frozen.adapter.AppFreezeAdapter;
import io.github.MoWei.Frozen.databinding.FragmentAppListBinding;
import io.github.MoWei.Frozen.model.AppFreezeInfo;

public class AppList extends Fragment {
    private static volatile List<AppFreezeInfo> sCachedList = null;
    private FragmentAppListBinding binding;
    private AppFreezeAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isLoading = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAppListBinding.inflate(inflater, container, false);
        binding.rvAppList.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new AppFreezeAdapter(requireContext());
        binding.rvAppList.setAdapter(adapter);

        binding.swipeRefresh.setColorSchemeResources(R.color.md_primary);
        binding.swipeRefresh.setOnRefreshListener(() -> loadData(true));

        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String kw = s != null ? s.toString().trim() : "";
                adapter.filter(kw);
                binding.btnClearSearch.setVisibility(kw.isEmpty() ? View.GONE : View.VISIBLE);
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        binding.btnClearSearch.setOnClickListener(v -> {
            binding.searchInput.setText("");
        });

        adapter.setOnAppClickListener(this::showAppDetailDialog);

        // 瞬间秒开：优先渲染已有完整数据；若首次冷启动则先用快速已安装列表填充首屏
        if (sCachedList != null && !sCachedList.isEmpty()) {
            adapter.updateData(sCachedList);
            updateSummary();
            loadData(false);
        } else {
            List<AppFreezeInfo> fastList = FreezeDetailScanner.getFastInstalledList();
            if (!fastList.isEmpty()) {
                adapter.updateData(fastList);
                updateSummary();
            }
            loadData(true);
        }
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadData(false);
    }

    private void loadData(boolean showIndicator) {
        if (isLoading) return;
        isLoading = true;

        if (showIndicator && binding != null) {
            binding.swipeRefresh.setRefreshing(true);
        }

        new Thread(() -> {
            Context ctx = getContext();
            if (ctx == null) {
                isLoading = false;
                return;
            }

            List<AppFreezeInfo> list;
            try {
                list = FreezeDetailScanner.scan(ctx.getApplicationContext(), showIndicator);
            } catch (Throwable error) {
                Log.e("FrozenAppList", "Failed to scan app freeze details", error);
                list = java.util.Collections.emptyList();
            }
            final List<AppFreezeInfo> result = list;

            mainHandler.post(() -> {
                if (binding == null || !isAdded()) {
                    isLoading = false;
                    return;
                }
                isLoading = false;
                binding.swipeRefresh.setRefreshing(false);
                sCachedList = result;
                adapter.updateData(result);
                updateSummary();
            });
        }, "FrozenAppListScanner").start();
    }

    private void updateSummary() {
        if (binding == null || adapter == null || !isAdded()) return;
        int frozen = adapter.getFrozenAppCount();
        int running = adapter.getRunningAppCount();
        binding.tvFreezeSummary.setText(getString(R.string.stat_summary_format, frozen, running));
    }

    @SuppressLint("SetTextI18n")
    private void showAppDetailDialog(AppFreezeInfo info) {
        if (!isAdded()) return;

        Dialog dialog = new Dialog(requireContext());
        dialog.setContentView(R.layout.dialog_liquid_glass_text);
        Utils.setupCenteredDialogWindow(dialog, requireContext());

        TextView tvTitle = dialog.findViewById(R.id.dialog_title);
        TextView tvMessage = dialog.findViewById(R.id.dialog_message);
        Button btnCancel = dialog.findViewById(R.id.dialog_btn_cancel);
        Button btnConfirm = dialog.findViewById(R.id.dialog_btn_confirm);

        if (tvTitle != null) {
            tvTitle.setText(info.label);
        }

        if (tvMessage != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(info.packageName).append(" (UID ").append(info.uid).append(")\n\n");
            if (info.isRunning()) {
                sb.append("进程总数: ").append(info.procCount).append("\n");
                sb.append("已冻结进程: ").append(info.frozenCount).append("\n");
                sb.append("物理内存 (RSS): ").append(info.rssMb).append("\n");
                sb.append("交换内存 (SWAP): ").append(info.swapMb).append("\n");
                sb.append("当前状态: ").append(info.isFrozen() ? "❄️ cgroup v2 冻结中" : "📱 前台/后台运行中");
            } else {
                sb.append("当前状态: 未运行 (0 进程)");
            }
            tvMessage.setText(sb.toString());
        }

        if (btnCancel != null) {
            btnCancel.setVisibility(View.VISIBLE);
            btnCancel.setText(R.string.action_app_details);
            btnCancel.setOnClickListener(v -> {
                dialog.dismiss();
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + info.packageName));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "无法打开系统设置", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnConfirm != null) {
            btnConfirm.setText(R.string.action_app_config);
            btnConfirm.setOnClickListener(v -> {
                dialog.dismiss();
                Intent intent = new Intent(requireContext(), AppConfigActivity.class);
                intent.putExtra("search_keyword", info.packageName);
                startActivity(intent);
            });
        }

        dialog.show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
