package com.example.sensegallery.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.sensegallery.R;
import com.example.sensegallery.data.AppDatabase;
import com.example.sensegallery.data.ClusterDao;
import com.example.sensegallery.data.ClusterEntity;
import com.example.sensegallery.data.FaceDao;
import com.example.sensegallery.data.PhotoDao;
import com.example.sensegallery.data.PhotoEntity;
import com.example.sensegallery.gallery.GalleryAdapter;
import com.example.sensegallery.gallery.GalleryViewModel;
import com.example.sensegallery.gallery.PhotoItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClusterDetailFragment extends Fragment {

    private GalleryViewModel viewModel;
    private GalleryAdapter adapter;
    private RecyclerView recyclerView;
    private TextView emptyText;
    private TextView nameText;
    private TextView selectionCountText;
    private View selectionBar;
    private ImageButton selectButton;

    private long clusterId;
    private String clusterName;
    private boolean selectionMode = false;

    private final List<PhotoItem> currentPhotos = new ArrayList<>();
    private final Set<String> selectedPhotoUris = new LinkedHashSet<>();

    private final ExecutorService curationExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            clusterId = getArguments().getLong("clusterId", 0L);
            clusterName = getArguments().getString("clusterName");
        }
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
        nameText = view.findViewById(R.id.clusterDetailNameText);
        selectionBar = view.findViewById(R.id.clusterSelectionBar);
        selectionCountText = view.findViewById(R.id.clusterSelectionCountText);

        ImageButton editButton = view.findViewById(R.id.editClusterNameButton);
        ImageButton mergeButton = view.findViewById(R.id.mergeClusterButton);
        selectButton = view.findViewById(R.id.selectClusterPhotosButton);

        Button moveSelectedButton = view.findViewById(R.id.moveSelectedButton);
        Button splitSelectedButton = view.findViewById(R.id.splitSelectedButton);
        Button removeSelectedButton = view.findViewById(R.id.removeSelectedButton);
        Button cancelSelectionButton = view.findViewById(R.id.cancelSelectionButton);

        if (clusterName != null) {
            nameText.setText(clusterName);
        }

        adapter = new GalleryAdapter((position, photo) -> {
            if (selectionMode) {
                togglePhotoSelection(photo);
                return;
            }
            openPhotoViewer(view, position);
        });
        adapter.setOnPhotoLongClickListener((position, photo) -> {
            if (!selectionMode) {
                setSelectionMode(true);
            }
            togglePhotoSelection(photo);
            return true;
        });
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 3));
        recyclerView.setAdapter(adapter);

        editButton.setOnClickListener(v -> showRenameDialog());
        mergeButton.setOnClickListener(v -> showMergeDialog());
        selectButton.setOnClickListener(v -> setSelectionMode(!selectionMode));

        moveSelectedButton.setOnClickListener(v -> {
            if (!ensureSelection()) {
                return;
            }
            showMoveSelectedDialog();
        });

        splitSelectedButton.setOnClickListener(v -> {
            if (!ensureSelection()) {
                return;
            }
            splitSelectionToNewPerson();
        });

        removeSelectedButton.setOnClickListener(v -> {
            if (!ensureSelection()) {
                return;
            }
            removeSelectionFromCluster();
        });

        cancelSelectionButton.setOnClickListener(v -> setSelectionMode(false));

        viewModel = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);

        viewModel.getClusterPhotos().observe(getViewLifecycleOwner(), photos -> {
            currentPhotos.clear();
            if (photos != null) {
                currentPhotos.addAll(photos);
            }

            adapter.setPhotos(currentPhotos);
            syncSelectionWithCurrentPhotos();
            adapter.setSelectionState(selectionMode, selectedPhotoUris);
            updateSelectionUi();

            boolean hasPhotos = !currentPhotos.isEmpty();
            recyclerView.setVisibility(hasPhotos ? View.VISIBLE : View.GONE);
            emptyText.setVisibility(hasPhotos ? View.GONE : View.VISIBLE);
        });

        if (clusterId == 0L) {
            Toast.makeText(requireContext(), R.string.cluster_curation_error_missing_cluster, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        viewModel.loadClusterPhotos(clusterId);
    }

    private void openPhotoViewer(@NonNull View view, int position) {
        Bundle args = new Bundle();
        args.putInt("initialPosition", position);
        args.putLong("clusterId", clusterId);
        Navigation.findNavController(view).navigate(R.id.action_global_nav_photo_viewer, args);
    }

    private void showRenameDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(R.string.cluster_curation_rename_title);

        final EditText input = new EditText(requireContext());
        input.setText(clusterName);

        android.widget.FrameLayout container = new android.widget.FrameLayout(requireContext());
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = (int) (20 * getResources().getDisplayMetrics().density);
        params.setMargins(margin, 0, margin, 0);
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton(R.string.cluster_curation_save, (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                clusterName = newName;
                nameText.setText(newName);
                if (viewModel != null) {
                    viewModel.renameCluster(clusterId, newName);
                }
            }
        });
        builder.setNegativeButton(R.string.cluster_curation_cancel, (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showMergeDialog() {
        loadOtherClusters(otherClusters -> {
            if (otherClusters.isEmpty()) {
                Toast.makeText(requireContext(), R.string.cluster_curation_no_other_clusters, Toast.LENGTH_SHORT)
                        .show();
                return;
            }

            CharSequence[] labels = new CharSequence[otherClusters.size()];
            for (int i = 0; i < otherClusters.size(); i++) {
                labels[i] = otherClusters.get(i).label;
            }

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.cluster_curation_merge_title)
                    .setItems(labels, (dialog, which) -> {
                        NamedCluster target = otherClusters.get(which);
                        mergeCurrentClusterInto(target.id, target.label);
                    })
                    .show();
        });
    }

    private void showMoveSelectedDialog() {
        loadOtherClusters(otherClusters -> {
            if (otherClusters.isEmpty()) {
                Toast.makeText(requireContext(), R.string.cluster_curation_no_other_clusters, Toast.LENGTH_SHORT)
                        .show();
                return;
            }

            CharSequence[] labels = new CharSequence[otherClusters.size()];
            for (int i = 0; i < otherClusters.size(); i++) {
                labels[i] = otherClusters.get(i).label;
            }

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.cluster_curation_move_title)
                    .setItems(labels, (dialog, which) -> {
                        NamedCluster target = otherClusters.get(which);
                        moveSelectionToCluster(target.id, target.label);
                    })
                    .show();
        });
    }

    private void mergeCurrentClusterInto(long targetClusterId, @NonNull String targetLabel) {
        curationExecutor.execute(() -> {
            boolean success = true;
            try {
                AppDatabase db = AppDatabase.getInstance(requireContext());
                FaceDao faceDao = db.faceDao();

                db.runInTransaction(() -> {
                    faceDao.moveAllFacesToCluster(clusterId, targetClusterId);
                    refreshOrDeleteCluster(db, targetClusterId);
                    refreshOrDeleteCluster(db, clusterId);
                });
            } catch (Exception e) {
                success = false;
            }

            boolean finalSuccess = success;
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }

                if (!finalSuccess) {
                    Toast.makeText(requireContext(), R.string.cluster_curation_failed, Toast.LENGTH_SHORT).show();
                    return;
                }

                Toast.makeText(requireContext(), R.string.cluster_curation_merge_success, Toast.LENGTH_SHORT).show();
                viewModel.loadClusters();
                navigateToCluster(targetClusterId, targetLabel);
            });
        });
    }

    private void moveSelectionToCluster(long targetClusterId, @NonNull String targetLabel) {
        Set<String> selectionSnapshot = new LinkedHashSet<>(selectedPhotoUris);
        curationExecutor.execute(() -> {
            boolean success = true;
            boolean sourceDeleted = false;
            try {
                AppDatabase db = AppDatabase.getInstance(requireContext());
                PhotoDao photoDao = db.photoDao();
                FaceDao faceDao = db.faceDao();

                List<Long> photoIds = resolvePhotoIds(photoDao, selectionSnapshot);
                if (photoIds.isEmpty()) {
                    success = false;
                } else {
                    final boolean[] sourceDeletedRef = new boolean[] { false };
                    db.runInTransaction(() -> {
                        faceDao.moveClusterFacesInPhotos(clusterId, photoIds, targetClusterId);
                        sourceDeletedRef[0] = refreshOrDeleteCluster(db, clusterId);
                        refreshOrDeleteCluster(db, targetClusterId);
                    });
                    sourceDeleted = sourceDeletedRef[0];
                }
            } catch (Exception e) {
                success = false;
            }

            boolean finalSuccess = success;
            boolean finalSourceDeleted = sourceDeleted;
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }

                if (!finalSuccess) {
                    Toast.makeText(requireContext(), R.string.cluster_curation_failed, Toast.LENGTH_SHORT).show();
                    return;
                }

                Toast.makeText(requireContext(), R.string.cluster_curation_move_success, Toast.LENGTH_SHORT).show();
                selectedPhotoUris.clear();
                viewModel.loadClusters();

                if (finalSourceDeleted) {
                    setSelectionMode(false);
                    navigateToCluster(targetClusterId, targetLabel);
                } else {
                    setSelectionMode(false);
                    viewModel.loadClusterPhotos(clusterId);
                }
            });
        });
    }

    private void splitSelectionToNewPerson() {
        Set<String> selectionSnapshot = new LinkedHashSet<>(selectedPhotoUris);
        curationExecutor.execute(() -> {
            boolean success = true;
            boolean sourceDeleted = false;
            long newClusterId = -1L;
            String newClusterLabel = null;

            try {
                AppDatabase db = AppDatabase.getInstance(requireContext());
                PhotoDao photoDao = db.photoDao();
                FaceDao faceDao = db.faceDao();
                ClusterDao clusterDao = db.clusterDao();

                List<Long> photoIds = resolvePhotoIds(photoDao, selectionSnapshot);
                if (photoIds.isEmpty()) {
                    success = false;
                } else {
                    final boolean[] sourceDeletedRef = new boolean[] { false };
                    final long[] newClusterIdRef = new long[] { -1L };
                    final String[] newClusterLabelRef = new String[] { null };

                    db.runInTransaction(() -> {
                        int nextNumber = clusterDao.getClusterCount() + 1;
                        newClusterLabelRef[0] = "Person " + nextNumber;
                        newClusterIdRef[0] = clusterDao.insertCluster(
                                new ClusterEntity(newClusterLabelRef[0], null));

                        faceDao.moveClusterFacesInPhotos(clusterId, photoIds, newClusterIdRef[0]);

                        sourceDeletedRef[0] = refreshOrDeleteCluster(db, clusterId);
                        refreshOrDeleteCluster(db, newClusterIdRef[0]);
                    });

                    sourceDeleted = sourceDeletedRef[0];
                    newClusterId = newClusterIdRef[0];
                    newClusterLabel = newClusterLabelRef[0];
                }
            } catch (Exception e) {
                success = false;
            }

            boolean finalSuccess = success;
            boolean finalSourceDeleted = sourceDeleted;
            long finalNewClusterId = newClusterId;
            String finalNewClusterLabel = newClusterLabel;

            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }

                if (!finalSuccess || finalNewClusterId <= 0L || finalNewClusterLabel == null) {
                    Toast.makeText(requireContext(), R.string.cluster_curation_failed, Toast.LENGTH_SHORT).show();
                    return;
                }

                Toast.makeText(requireContext(), R.string.cluster_curation_split_success, Toast.LENGTH_SHORT).show();
                selectedPhotoUris.clear();
                setSelectionMode(false);
                viewModel.loadClusters();

                if (finalSourceDeleted) {
                    navigateToCluster(finalNewClusterId, finalNewClusterLabel);
                } else {
                    viewModel.loadClusterPhotos(clusterId);
                }
            });
        });
    }

    private void removeSelectionFromCluster() {
        Set<String> selectionSnapshot = new LinkedHashSet<>(selectedPhotoUris);
        curationExecutor.execute(() -> {
            boolean success = true;
            boolean sourceDeleted = false;

            try {
                AppDatabase db = AppDatabase.getInstance(requireContext());
                PhotoDao photoDao = db.photoDao();
                FaceDao faceDao = db.faceDao();

                List<Long> photoIds = resolvePhotoIds(photoDao, selectionSnapshot);
                if (photoIds.isEmpty()) {
                    success = false;
                } else {
                    final boolean[] sourceDeletedRef = new boolean[] { false };
                    db.runInTransaction(() -> {
                        faceDao.moveClusterFacesInPhotos(clusterId, photoIds, null);
                        sourceDeletedRef[0] = refreshOrDeleteCluster(db, clusterId);
                    });
                    sourceDeleted = sourceDeletedRef[0];
                }
            } catch (Exception e) {
                success = false;
            }

            boolean finalSuccess = success;
            boolean finalSourceDeleted = sourceDeleted;
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }

                if (!finalSuccess) {
                    Toast.makeText(requireContext(), R.string.cluster_curation_failed, Toast.LENGTH_SHORT).show();
                    return;
                }

                Toast.makeText(requireContext(), R.string.cluster_curation_remove_success, Toast.LENGTH_SHORT).show();
                selectedPhotoUris.clear();
                setSelectionMode(false);
                viewModel.loadClusters();

                if (finalSourceDeleted) {
                    if (getView() != null) {
                        NavController navController = Navigation.findNavController(requireView());
                        navController.navigateUp();
                    }
                } else {
                    viewModel.loadClusterPhotos(clusterId);
                }
            });
        });
    }

    private void loadOtherClusters(@NonNull ClusterListCallback callback) {
        curationExecutor.execute(() -> {
            List<NamedCluster> otherClusters = new ArrayList<>();
            try {
                AppDatabase db = AppDatabase.getInstance(requireContext());
                List<ClusterEntity> all = db.clusterDao().getAllClusters();
                for (ClusterEntity entity : all) {
                    if (entity.id == clusterId) {
                        continue;
                    }

                    String label = entity.label;
                    if (label == null || label.trim().isEmpty()) {
                        label = "Person " + entity.id;
                    }
                    otherClusters.add(new NamedCluster(entity.id, label));
                }
            } catch (Exception ignored) {
            }

            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                callback.onLoaded(otherClusters);
            });
        });
    }

    private boolean ensureSelection() {
        if (!selectedPhotoUris.isEmpty()) {
            return true;
        }
        Toast.makeText(requireContext(), R.string.cluster_curation_none_selected, Toast.LENGTH_SHORT).show();
        return false;
    }

    private void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        if (!enabled) {
            selectedPhotoUris.clear();
        }

        adapter.setSelectionState(selectionMode, selectedPhotoUris);
        updateSelectionUi();
    }

    private void togglePhotoSelection(@NonNull PhotoItem photo) {
        String uri = photo.getContentUri().toString();
        if (selectedPhotoUris.contains(uri)) {
            selectedPhotoUris.remove(uri);
        } else {
            selectedPhotoUris.add(uri);
        }

        adapter.setSelectionState(selectionMode, selectedPhotoUris);
        updateSelectionUi();
    }

    private void syncSelectionWithCurrentPhotos() {
        if (selectedPhotoUris.isEmpty()) {
            return;
        }

        Set<String> availableUris = new HashSet<>();
        for (PhotoItem item : currentPhotos) {
            availableUris.add(item.getContentUri().toString());
        }
        selectedPhotoUris.retainAll(availableUris);
    }

    private void updateSelectionUi() {
        selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        selectionCountText.setText(getString(
                R.string.cluster_curation_select_count,
                selectedPhotoUris.size()));

        if (selectButton != null) {
            selectButton.setImageResource(selectionMode
                    ? android.R.drawable.ic_menu_close_clear_cancel
                    : android.R.drawable.ic_menu_agenda);
            selectButton.setContentDescription(getString(selectionMode
                    ? R.string.cluster_curation_done
                    : R.string.cluster_curation_select));
        }
    }

    private List<Long> resolvePhotoIds(@NonNull PhotoDao photoDao, @NonNull Set<String> selectedUris) {
        List<Long> photoIds = new ArrayList<>();
        for (String uri : selectedUris) {
            PhotoEntity photo = photoDao.getPhotoByUri(uri);
            if (photo != null) {
                photoIds.add(photo.id);
            }
        }
        return photoIds;
    }

    private boolean refreshOrDeleteCluster(@NonNull AppDatabase db, long id) {
        ClusterDao clusterDao = db.clusterDao();
        FaceDao faceDao = db.faceDao();

        ClusterEntity cluster = clusterDao.getClusterById(id);
        if (cluster == null) {
            return true;
        }

        int faceCount = clusterDao.getFaceCountInCluster(id);
        if (faceCount <= 0) {
            clusterDao.deleteCluster(id);
            return true;
        }

        boolean validCover = false;
        if (cluster.coverPhotoId != null) {
            validCover = faceDao.getFaceForCover(cluster.coverPhotoId, id) != null;
        }

        if (!validCover) {
            List<PhotoEntity> photos = faceDao.getPhotosForCluster(id);
            if (!photos.isEmpty()) {
                clusterDao.updateCoverPhoto(id, photos.get(0).id);
            } else {
                clusterDao.updateCoverPhoto(id, null);
            }
        }

        return false;
    }

    private void navigateToCluster(long targetClusterId, @NonNull String targetLabel) {
        if (getView() == null) {
            return;
        }

        Bundle args = new Bundle();
        args.putLong("clusterId", targetClusterId);
        args.putString("clusterName", targetLabel);

        NavOptions navOptions = new NavOptions.Builder()
                .setPopUpTo(R.id.nav_cluster_detail, true)
                .build();
        Navigation.findNavController(requireView())
                .navigate(R.id.action_global_nav_cluster_detail, args, navOptions);
    }

    @Override
    public void onDestroy() {
        curationExecutor.shutdownNow();
        super.onDestroy();
    }

    private interface ClusterListCallback {
        void onLoaded(List<NamedCluster> clusters);
    }

    private static class NamedCluster {
        final long id;
        final String label;

        NamedCluster(long id, String label) {
            this.id = id;
            this.label = label;
        }
    }
}
