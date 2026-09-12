package io.github.MoWei.Frozen.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import io.github.MoWei.Frozen.R;
import io.github.MoWei.Frozen.model.AppFreezeInfo;

public class AppFreezeAdapter extends RecyclerView.Adapter<AppFreezeAdapter.ViewHolder> {
    private final Context context;
    private final List<AppFreezeInfo> fullList = new ArrayList<>();
    private final List<AppFreezeInfo> displayList = new ArrayList<>();
    private String currentKeyword = "";
    private OnAppClickListener listener;

    public interface OnAppClickListener {
        void onAppClick(AppFreezeInfo info);
    }

    public AppFreezeAdapter(Context context) {
        this.context = context;
    }

    public void setOnAppClickListener(OnAppClickListener listener) {
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateData(List<AppFreezeInfo> newList) {
        fullList.clear();
        if (newList != null) {
            fullList.addAll(newList);
        }
        applyFilter();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void filter(String keyword) {
        currentKeyword = keyword != null ? keyword.trim() : "";
        applyFilter();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void applyFilter() {
        displayList.clear();
        for (AppFreezeInfo info : fullList) {
            if (info.matches(currentKeyword)) {
                displayList.add(info);
            }
        }
        notifyDataSetChanged();
    }

    public int getFrozenAppCount() {
        int count = 0;
        for (AppFreezeInfo info : fullList) {
            if (info.isFrozen()) count++;
        }
        return count;
    }

    public int getRunningAppCount() {
        int count = 0;
        for (AppFreezeInfo info : fullList) {
            if (info.isRunning()) count++;
        }
        return count;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_freeze_detail, parent, false);
        return new ViewHolder(v);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppFreezeInfo info = displayList.get(position);
        if (info.icon != null) {
            holder.appIcon.setImageDrawable(info.icon);
        } else {
            holder.appIcon.setImageResource(R.drawable.icon);
        }

        holder.appName.setText(info.label);

        if (info.isRunning()) {
            holder.procStat.setText(context.getString(R.string.stat_procs_frozen, info.procCount, info.frozenCount));
            holder.memStat.setText(context.getString(R.string.stat_mem, info.rssMb, info.swapMb));
        } else {
            holder.procStat.setText(R.string.freeze_state_idle);
            holder.memStat.setText("RSS 0MB  SWAP 0MB");
        }

        if (info.isFrozen()) {
            holder.freezeBadge.setText(R.string.freeze_state_v2);
            holder.freezeBadge.setTextColor(Color.parseColor("#00C853"));
            holder.freezeBadge.setBackgroundResource(R.drawable.bg_badge_frozen);
            holder.freezeBadge.setVisibility(View.VISIBLE);
        } else if (info.isRunning()) {
            holder.freezeBadge.setText(R.string.freeze_state_running);
            holder.freezeBadge.setTextColor(Color.parseColor("#2196F3"));
            holder.freezeBadge.setBackgroundResource(R.drawable.bg_badge_running);
            holder.freezeBadge.setVisibility(View.VISIBLE);
        } else {
            holder.freezeBadge.setText(R.string.freeze_state_idle);
            holder.freezeBadge.setTextColor(Color.parseColor("#888888"));
            holder.freezeBadge.setBackgroundResource(R.drawable.bg_badge_idle);
            holder.freezeBadge.setVisibility(View.VISIBLE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAppClick(info);
            }
        });
    }

    @Override
    public int getItemCount() {
        return displayList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView appIcon;
        TextView appName;
        TextView procStat;
        TextView memStat;
        TextView freezeBadge;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.app_icon);
            appName = itemView.findViewById(R.id.app_name);
            procStat = itemView.findViewById(R.id.proc_stat);
            memStat = itemView.findViewById(R.id.mem_stat);
            freezeBadge = itemView.findViewById(R.id.freeze_badge);
        }
    }
}
