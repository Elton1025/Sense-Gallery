package com.example.sensegallery.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.sensegallery.R;
import com.example.sensegallery.gallery.GalleryAdapter;
import com.example.sensegallery.gallery.GalleryViewModel;

public class PhotosFragment extends Fragment {

    private GalleryViewModel viewModel;
    private GalleryAdapter adapter;
    private RecyclerView recyclerView;
    private TextView emptyStateText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_photos, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize views
        recyclerView = view.findViewById(R.id.galleryRecyclerView);
        emptyStateText = view.findViewById(R.id.emptyStateText);

        // Setup RecyclerView
        adapter = new GalleryAdapter((position, photo) -> {
            Bundle args = new Bundle();
            args.putInt("initialPosition", position);
            // Default to -1L for global photos (already natively handled by default
            // arguments)
            Navigation.findNavController(view).navigate(R.id.action_global_nav_photo_viewer, args);
        });
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), 3);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(adapter);
        recyclerView.setHasFixedSize(true);

        // Initialize ViewModel
        viewModel = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);

        // Observe photo data
        viewModel.getPhotos().observe(getViewLifecycleOwner(), photos -> {
            if (photos != null && !photos.isEmpty()) {
                adapter.setPhotos(photos);
                recyclerView.setVisibility(View.VISIBLE);
                emptyStateText.setVisibility(View.GONE);
            } else {
                recyclerView.setVisibility(View.GONE);
                emptyStateText.setVisibility(View.VISIBLE);
            }
        });

        // Trigger load
        viewModel.loadPhotos();
    }
}
