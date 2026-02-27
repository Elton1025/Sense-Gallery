package com.example.sensegallery.gallery;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.example.sensegallery.R;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for the photo gallery grid.
 * Uses Glide for efficient image loading and memory management.
 */
public class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.PhotoViewHolder> {

    public interface OnPhotoClickListener {
        void onPhotoClick(int position);
    }

    private List<PhotoItem> photos = new ArrayList<>();
    private final OnPhotoClickListener listener;

    public GalleryAdapter(OnPhotoClickListener listener) {
        this.listener = listener;
    }

    public void setPhotos(List<PhotoItem> photos) {
        this.photos = photos;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_photo, parent, false);
        return new PhotoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        PhotoItem photo = photos.get(position);

        Glide.with(holder.imageView.getContext())
                .load(photo.getContentUri())
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .placeholder(R.drawable.placeholder_photo)
                .into(holder.imageView);

        holder.itemView.setOnClickListener(v -> {
            int currentPosition = holder.getAdapterPosition();
            if (listener != null && currentPosition != RecyclerView.NO_POSITION) {
                listener.onPhotoClick(currentPosition);
            }
        });
    }

    @Override
    public int getItemCount() {
        return photos.size();
    }

    static class PhotoViewHolder extends RecyclerView.ViewHolder {
        final ImageView imageView;

        PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.photoImageView);
        }
    }
}
