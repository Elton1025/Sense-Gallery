package com.example.sensegallery.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.example.sensegallery.R;
import com.example.sensegallery.gallery.GalleryViewModel;

public class PhotoViewerFragment extends Fragment {

    private int initialPosition;
    private long clusterId;
    private PhotoPagerAdapter adapter;
    private ViewPager2 viewPager;
    private GalleryViewModel viewModel;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            initialPosition = getArguments().getInt("initialPosition", 0);
            clusterId = getArguments().getLong("clusterId", -1L);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_photo_viewer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewPager = view.findViewById(R.id.photoViewPager);
        adapter = new PhotoPagerAdapter();
        viewPager.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(GalleryViewModel.class);

        if (clusterId != -1L) {
            // Cluster-specific viewing mode
            viewModel.getClusterPhotos().observe(getViewLifecycleOwner(), photos -> {
                if (photos != null && !photos.isEmpty()) {
                    adapter.setPhotos(photos);
                    viewPager.setCurrentItem(initialPosition, false);
                }
            });
            viewModel.loadClusterPhotos(clusterId);
        } else {
            // Global viewing mode (all photos)
            viewModel.getPhotos().observe(getViewLifecycleOwner(), photos -> {
                if (photos != null && !photos.isEmpty()) {
                    adapter.setPhotos(photos);
                    viewPager.setCurrentItem(initialPosition, false);
                }
            });
            viewModel.loadPhotos();
        }
    }
}
