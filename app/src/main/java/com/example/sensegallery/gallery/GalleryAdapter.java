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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RecyclerView adapter for the photo gallery grid.
 * Uses Glide for efficient image loading and memory management.
 */
public class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.PhotoViewHolder> {

    public interface OnPhotoClickListener {
        void onPhotoClick(int position, PhotoItem photo);
    }

    public interface OnPhotoLongClickListener {
        boolean onPhotoLongClick(int position, PhotoItem photo);
    }

    private List<PhotoItem> photos = new ArrayList<>();
    private final OnPhotoClickListener listener;
    private OnPhotoLongClickListener longClickListener;
    private boolean selectionMode = false;
    private final Set<String> selectedPhotoUris = new HashSet<>();

    public GalleryAdapter(OnPhotoClickListener listener) {
        this.listener = listener;
    }

    public void setPhotos(List<PhotoItem> photos) {
        this.photos = photos;
        notifyDataSetChanged();
    }

    public void setOnPhotoLongClickListener(OnPhotoLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setSelectionState(boolean selectionMode, Set<String> selectedUris) {
        this.selectionMode = selectionMode;
        selectedPhotoUris.clear();
        if (selectedUris != null) {
            selectedPhotoUris.addAll(selectedUris);
        }
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

        boolean isSelected = selectedPhotoUris.contains(photo.getContentUri().toString());
        holder.selectionOverlay.setVisibility(selectionMode && isSelected ? View.VISIBLE : View.GONE);
        holder.checkmarkView.setVisibility(selectionMode && isSelected ? View.VISIBLE : View.GONE);
        holder.itemView.setAlpha(selectionMode && !isSelected ? 0.75f : 1f);

        holder.itemView.setOnClickListener(v -> {
            int currentPosition = holder.getAdapterPosition();
            if (listener != null && currentPosition != RecyclerView.NO_POSITION) {
                listener.onPhotoClick(currentPosition, photos.get(currentPosition));
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            int currentPosition = holder.getAdapterPosition();
            if (longClickListener != null && currentPosition != RecyclerView.NO_POSITION) {
                return longClickListener.onPhotoLongClick(currentPosition, photos.get(currentPosition));
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return photos.size();
    }

    static class PhotoViewHolder extends RecyclerView.ViewHolder {
        final ImageView imageView;
        final View selectionOverlay;
        final ImageView checkmarkView;

        PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.photoImageView);
            selectionOverlay = itemView.findViewById(R.id.photoSelectionOverlay);
            checkmarkView = itemView.findViewById(R.id.photoSelectionCheckmark);
        }
    }
}
