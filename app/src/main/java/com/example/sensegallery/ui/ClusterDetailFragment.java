package com.example.sensegallery.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.sensegallery.R;
import com.example.sensegallery.data.AppDatabase;
import com.example.sensegallery.data.FaceEntity;
import com.example.sensegallery.data.PhotoEntity;
import com.example.sensegallery.gallery.GalleryAdapter;
import com.example.sensegallery.gallery.GalleryViewModel;

import java.util.List;

public class ClusterDetailFragment extends Fragment {

    private static final String TAG = "ClusterDetail";
    private GalleryViewModel viewModel;
    private GalleryAdapter adapter;
    private RecyclerView recyclerView;
    private TextView emptyText;
    private long clusterId;
    private String clusterName;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            clusterId = getArguments().getLong("clusterId");
            clusterName = getArguments().getString("clusterName");
        }
        Log.d(TAG, "onCreate — clusterId=" + clusterId + " clusterName=" + clusterName);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_cluster_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.clusterDetailRecyclerView);
        emptyText = view.findViewById(R.id.emptyClusterDetailText);

        TextView nameText = view.findViewById(R.id.clusterDetailNameText);
        ImageButton editButton = view.findViewById(R.id.editClusterNameButton);
        if (clusterName != null) {
            nameText.setText(clusterName);
        }

        adapter = new GalleryAdapter(position -> {
            Bundle args = new Bundle();
            args.putInt("initialPosition", position);
            args.putLong("clusterId", clusterId);
            Navigation.findNavController(view).navigate(R.id.action_global_nav_photo_viewer, args);
        });
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 3));
        recyclerView.setAdapter(adapter);

        editButton.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
            builder.setTitle("Edit Name");

            final EditText input = new EditText(requireContext());
            input.setText(clusterName);

            // Add margin to EditText
            android.widget.FrameLayout container = new android.widget.FrameLayout(requireContext());
            android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int margin = (int) (20 * getResources().getDisplayMetrics().density);
            params.setMargins(margin, 0, margin, 0);
            input.setLayoutParams(params);
            container.addView(input);

            builder.setView(container);

            builder.setPositiveButton("Save", (dialog, which) -> {
                String newName = input.getText().toString().trim();
                if (!newName.isEmpty()) {
                    clusterName = newName;
                    nameText.setText(newName);
                    if (viewModel != null) {
                        viewModel.renameCluster(clusterId, newName);
                    }
                }
            });
            builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
            builder.show();
        });

        viewModel = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);

        viewModel.getClusterPhotos().observe(getViewLifecycleOwner(), photos -> {
            Log.d(TAG, "LiveData update: " + (photos != null ? photos.size() : "null") + " photos");
            if (photos != null && !photos.isEmpty()) {
                adapter.setPhotos(photos);
                recyclerView.setVisibility(View.VISIBLE);
                emptyText.setVisibility(View.GONE);
            } else {
                recyclerView.setVisibility(View.GONE);
                emptyText.setVisibility(View.VISIBLE);
            }
        });

        // Direct DB debug on background thread
        new Thread(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(requireContext());
                List<FaceEntity> faces = db.faceDao().getFacesInCluster(clusterId);
                Log.d(TAG, "=== DB DEBUG for cluster " + clusterId + " ===");
                Log.d(TAG, "Faces in cluster: " + faces.size());
                for (FaceEntity f : faces) {
                    Log.d(TAG, "  Face: id=" + f.id + " photoId=" + f.photoId
                            + " clusterId=" + f.clusterId);
                }

                List<PhotoEntity> joinPhotos = db.faceDao().getPhotosForCluster(clusterId);
                Log.d(TAG, "JOIN query result: " + joinPhotos.size() + " photos");
                for (PhotoEntity p : joinPhotos) {
                    Log.d(TAG, "  Photo: id=" + p.id + " uri=" + p.mediaStoreUri);
                }

                int totalPhotos = db.photoDao().getPhotoCount();
                int totalFaces = db.faceDao().getFaceCount();
                Log.d(TAG, "Total photos in DB: " + totalPhotos);
                Log.d(TAG, "Total faces in DB: " + totalFaces);
                Log.d(TAG, "=== END DB DEBUG ===");
            } catch (Exception e) {
                Log.e(TAG, "DB debug failed", e);
            }
        }).start();

        // Load cluster photos (with debug logging in ViewModel)
        if (clusterId != 0) {
            Log.d(TAG, "Calling loadClusterPhotos(" + clusterId + ")");
            viewModel.loadClusterPhotos(clusterId);
        } else {
            Log.e(TAG, "clusterId is 0! Not loading.");
        }
    }
}
