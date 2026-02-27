package com.example.sensegallery.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.sensegallery.R;

import java.util.ArrayList;
import java.util.List;

public class SearchClusterAdapter extends RecyclerView.Adapter<SearchClusterAdapter.SearchViewHolder> {

    private List<ClusterDisplayItem> clusters = new ArrayList<>();
    private final OnClusterClickListener listener;

    public interface OnClusterClickListener {
        void onClusterClick(ClusterDisplayItem cluster);
    }

    public SearchClusterAdapter(OnClusterClickListener listener) {
        this.listener = listener;
    }

    public void setClusters(List<ClusterDisplayItem> clusters) {
        this.clusters = clusters;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SearchViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_search_cluster, parent, false);
        return new SearchViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SearchViewHolder holder, int position) {
        ClusterDisplayItem cluster = clusters.get(position);
        holder.bind(cluster, listener);
    }

    @Override
    public int getItemCount() {
        return clusters != null ? clusters.size() : 0;
    }

    static class SearchViewHolder extends RecyclerView.ViewHolder {
        private final ImageView coverImage;
        private final TextView nameText;

        public SearchViewHolder(@NonNull View itemView) {
            super(itemView);
            coverImage = itemView.findViewById(R.id.searchClusterImage);
            nameText = itemView.findViewById(R.id.searchClusterName);
        }

        public void bind(ClusterDisplayItem cluster, OnClusterClickListener listener) {
            nameText.setText(cluster.label);

            if (cluster.coverPhotoUri != null) {
                Glide.with(itemView.getContext())
                        .load(cluster.coverPhotoUri)
                        .transform(new FaceCropTransformation(
                                cluster.boxLeft, cluster.boxTop,
                                cluster.boxRight, cluster.boxBottom, 0.5f), new CircleCrop())
                        .into(coverImage);
            } else {
                coverImage.setImageDrawable(null);
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onClusterClick(cluster);
                }
            });
        }
    }
}
