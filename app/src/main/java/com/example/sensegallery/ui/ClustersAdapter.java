package com.example.sensegallery.ui;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.example.sensegallery.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying face clusters with profile photos and counts.
 */
public class ClustersAdapter extends RecyclerView.Adapter<ClustersAdapter.ClusterViewHolder> {

    private List<ClusterDisplayItem> clusters = new ArrayList<>();
    private final OnClusterClickListener listener;

    public interface OnClusterClickListener {
        void onClusterClick(ClusterDisplayItem cluster);
    }

    public ClustersAdapter(OnClusterClickListener listener) {
        this.listener = listener;
    }

    public void setClusters(List<ClusterDisplayItem> clusters) {
        this.clusters = clusters;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ClusterViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_cluster, parent, false);
        return new ClusterViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ClusterViewHolder holder, int position) {
        ClusterDisplayItem cluster = clusters.get(position);
        holder.bind(cluster, listener);
    }

    @Override
    public int getItemCount() {
        return clusters.size();
    }

    static class ClusterViewHolder extends RecyclerView.ViewHolder {
        private final ImageView coverImage;
        private final TextView nameText;
        private final TextView countText;

        public ClusterViewHolder(@NonNull View itemView) {
            super(itemView);
            coverImage = itemView.findViewById(R.id.clusterCoverImage);
            nameText = itemView.findViewById(R.id.clusterName);
            countText = itemView.findViewById(R.id.clusterCount);
        }

        public void bind(ClusterDisplayItem cluster, OnClusterClickListener listener) {
            nameText.setText(cluster.label);

            // Show photo count
            if (cluster.photoCount > 0) {
                countText.setVisibility(View.VISIBLE);
                countText.setText(cluster.photoCount + " photo" + (cluster.photoCount != 1 ? "s" : ""));
            } else {
                countText.setVisibility(View.GONE);
            }

            // Load cover photo using Glide
            if (cluster.coverPhotoUri != null) {
                Glide.with(itemView.getContext())
                        .load(Uri.parse(cluster.coverPhotoUri))
                        .transform(new FaceCropTransformation(
                                cluster.boxLeft, cluster.boxTop,
                                cluster.boxRight, cluster.boxBottom, 0.5f), new CircleCrop())
                        .transition(DrawableTransitionOptions.withCrossFade(150))
                        .placeholder(R.drawable.placeholder_photo)
                        .error(R.drawable.ic_people)
                        .into(coverImage);
            } else {
                coverImage.setImageResource(R.drawable.ic_people);
            }

            itemView.setOnClickListener(v -> listener.onClusterClick(cluster));
        }
    }
}
