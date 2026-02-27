package com.example.sensegallery.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.example.sensegallery.R;
import com.example.sensegallery.gallery.PhotoItem;

import java.util.ArrayList;
import java.util.List;

public class PhotoPagerAdapter extends RecyclerView.Adapter<PhotoPagerAdapter.PagerViewHolder> {

    private List<PhotoItem> photos = new ArrayList<>();

    public void setPhotos(List<PhotoItem> photos) {
        this.photos = photos;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PagerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_fullscreen_photo, parent, false);
        return new PagerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PagerViewHolder holder, int position) {
        PhotoItem photo = photos.get(position);

        Glide.with(holder.imageView.getContext())
                .load(photo.getContentUri())
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .into(holder.imageView);
    }

    @Override
    public int getItemCount() {
        return photos.size();
    }

    static class PagerViewHolder extends RecyclerView.ViewHolder {
        final ImageView imageView;

        PagerViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.pagerImageView);
        }
    }
}
