package com.example.sensegallery;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.lifecycle.ViewModelProvider;

import com.example.sensegallery.gallery.GalleryAdapter;
import com.example.sensegallery.gallery.GalleryViewModel;
import com.example.sensegallery.ui.ClusterDisplayItem;
import com.example.sensegallery.ui.SearchClusterAdapter;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;

import java.util.ArrayList;
import java.util.List;

/**
 * Main activity that hosts the Navigation Graph.
 * Handles runtime permission requests, global actions, and CLIP semantic
 * search.
 */
public class MainActivity extends AppCompatActivity {

    private FloatingActionButton processFacesFab;
    private BottomNavigationView bottomNav;
    private SearchBar searchBar;
    private SearchView searchView;
    private GalleryViewModel galleryViewModel;
    private SearchClusterAdapter searchAdapter;
    private GalleryAdapter semanticAdapter;
    private RecyclerView semanticResultsGrid;
    private TextView searchEmptyState;
    private List<ClusterDisplayItem> allClusters = new ArrayList<>();

    private final ActivityResultLauncher<String> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (!isGranted) {
                    showPermissionDenied();
                } else {
                    startClipIndexing(androidx.work.ExistingWorkPolicy.KEEP, false);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Setup Navigation
        bottomNav = findViewById(R.id.bottom_navigation);
        searchBar = findViewById(R.id.search_bar);
        searchView = findViewById(R.id.search_view);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();
            NavigationUI.setupWithNavController(bottomNav, navController);
            navController.addOnDestinationChangedListener((controller, destination, args) -> {
                updateChromeForDestination(destination.getId());
            });

            if (navController.getCurrentDestination() != null) {
                updateChromeForDestination(navController.getCurrentDestination().getId());
            }
        }

        // FAB click: start face processing + CLIP indexing
        processFacesFab = findViewById(R.id.processFacesFab);
        if (processFacesFab != null) {
            processFacesFab.setOnClickListener(v -> startProcessing());
        }

        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();
            if (navController.getCurrentDestination() != null) {
                updateChromeForDestination(navController.getCurrentDestination().getId());
            }
        }

        // Setup Search Functionality
        setupSearch();

        // Check and request permissions
        checkAndRequestPermission();
    }

    private void setupSearch() {
        RecyclerView searchRecyclerView = findViewById(R.id.searchRecyclerView);
        semanticResultsGrid = findViewById(R.id.semanticResultsGrid);
        searchEmptyState = findViewById(R.id.searchEmptyState);

        // Cluster name search adapter
        searchAdapter = new SearchClusterAdapter(cluster -> {
            searchView.hide();
            android.os.Bundle args = new android.os.Bundle();
            args.putLong("clusterId", cluster.clusterId);
            args.putString("clusterName", cluster.label);

            NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.nav_host_fragment);
            if (navHostFragment != null) {
                navHostFragment.getNavController()
                        .navigate(R.id.action_global_nav_cluster_detail, args);
            }
        });

        searchRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        searchRecyclerView.setAdapter(searchAdapter);

        // Semantic photo results adapter (grid of 3 columns)
        semanticAdapter = new GalleryAdapter((position, photo) -> {
            searchView.hide();

            Bundle args = new Bundle();
            args.putInt("initialPosition", 0);
            args.putString("photoUri", photo.getContentUri().toString());

            NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.nav_host_fragment);
            if (navHostFragment != null) {
                navHostFragment.getNavController()
                        .navigate(R.id.action_global_nav_photo_viewer, args);
            }
        });
        semanticResultsGrid.setLayoutManager(new GridLayoutManager(this, 3));
        semanticResultsGrid.setAdapter(semanticAdapter);

        // ViewModel
        galleryViewModel = new ViewModelProvider(this).get(GalleryViewModel.class);

        // Observe cluster data (for name-based filtering)
        galleryViewModel.getClusters().observe(this, clusters -> {
            if (clusters != null) {
                allClusters = clusters;
                filterSearch(searchView.getText().toString());
            }
        });

        // Observe semantic search results
        galleryViewModel.getSemanticSearchResults().observe(this, photos -> {
            if (photos != null && !photos.isEmpty()) {
                semanticAdapter.setPhotos(photos);
                semanticResultsGrid.setVisibility(View.VISIBLE);
                searchEmptyState.setVisibility(View.GONE);
            } else {
                semanticAdapter.setPhotos(new ArrayList<>());
                // Only show "No results" if there's a query typed and no cluster matches either
                String query = searchView.getText() != null ? searchView.getText().toString().trim() : "";
                if (!query.isEmpty() && (searchAdapter == null || searchAdapter.getItemCount() == 0)) {
                    semanticResultsGrid.setVisibility(View.GONE);
                    searchEmptyState.setVisibility(View.VISIBLE);
                } else {
                    semanticResultsGrid.setVisibility(View.GONE);
                    searchEmptyState.setVisibility(View.GONE);
                }
            }
        });

        // Search text listener — cluster filtering is instant, semantic search is
        // debounced
        android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] pendingSearch = { null };

        searchView.getEditText().addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim();

                // Cluster name filtering — instant (local, fast)
                filterSearch(query);

                // Semantic search — debounce 400ms to avoid intermediate junk results
                if (pendingSearch[0] != null) {
                    searchHandler.removeCallbacks(pendingSearch[0]);
                }
                pendingSearch[0] = () -> galleryViewModel.semanticSearch(query);
                searchHandler.postDelayed(pendingSearch[0], 400);
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        // Trigger initial load
        galleryViewModel.loadClusters();
    }

    private void filterSearch(String query) {
        if (query == null)
            return;
        List<ClusterDisplayItem> filteredList = new ArrayList<>();
        String lowerQuery = query.toLowerCase().trim();
        for (ClusterDisplayItem item : allClusters) {
            if (item.label != null && item.label.toLowerCase().contains(lowerQuery)) {
                filteredList.add(item);
            }
        }
        if (searchAdapter != null) {
            searchAdapter.setClusters(filteredList);
        }
    }

    private void startProcessing() {
        // 1. Face processing
        androidx.work.OneTimeWorkRequest faceWork = new androidx.work.OneTimeWorkRequest.Builder(
                com.example.sensegallery.workers.FaceProcessingWorker.class)
                .build();

        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
                "FaceProcessingWork",
                androidx.work.ExistingWorkPolicy.KEEP,
                faceWork);

        // 2. CLIP indexing
        startClipIndexing(androidx.work.ExistingWorkPolicy.REPLACE, false);

        Toast.makeText(this, "Face processing + CLIP indexing started!", Toast.LENGTH_SHORT).show();
    }

    private void startClipIndexing(androidx.work.ExistingWorkPolicy policy, boolean showToast) {
        androidx.work.OneTimeWorkRequest clipWork = new androidx.work.OneTimeWorkRequest.Builder(
                com.example.sensegallery.workers.CLIPIndexingWorker.class)
                .build();

        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
                "CLIPIndexingWork",
                policy,
                clipWork);

        if (showToast) {
            Toast.makeText(this, "Semantic search indexing started", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkAndRequestPermission() {
        String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_IMAGES;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permission);
        } else {
            startClipIndexing(androidx.work.ExistingWorkPolicy.KEEP, false);
        }
    }

    private void showPermissionDenied() {
        Toast.makeText(this, R.string.permission_denied_message, Toast.LENGTH_LONG).show();
    }

    private void updateChromeForDestination(int destinationId) {
        boolean isPhotoViewer = destinationId == R.id.nav_photo_viewer;
        int visibility = isPhotoViewer ? View.GONE : View.VISIBLE;

        if (searchBar != null) {
            searchBar.setVisibility(visibility);
        }
        if (searchView != null) {
            if (isPhotoViewer) {
                searchView.hide();
            }
            searchView.setVisibility(visibility);
        }
        if (bottomNav != null) {
            bottomNav.setVisibility(visibility);
        }
        if (processFacesFab != null) {
            processFacesFab.setVisibility(isPhotoViewer ? View.GONE : View.VISIBLE);
        }
    }
}
