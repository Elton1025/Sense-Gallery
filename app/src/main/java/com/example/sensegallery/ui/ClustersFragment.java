package com.example.sensegallery.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.sensegallery.R;
import com.example.sensegallery.gallery.GalleryViewModel;

public class ClustersFragment extends Fragment {

    private GalleryViewModel viewModel;
    private ClustersAdapter adapter;
    private RecyclerView recyclerView;
    private TextView emptyText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_clusters, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.clustersRecyclerView);
        emptyText = view.findViewById(R.id.emptyClustersText);

        adapter = new ClustersAdapter(cluster -> {
            Bundle args = new Bundle();
            args.putLong("clusterId", cluster.clusterId);
            args.putString("clusterName", cluster.label);

            androidx.navigation.Navigation.findNavController(view)
                    .navigate(R.id.action_nav_clusters_to_nav_cluster_detail, args);
        });

        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 2));
        recyclerView.setAdapter(adapter);

        viewModel = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);

        viewModel.getClusters().observe(getViewLifecycleOwner(), clusters -> {
            if (clusters != null && !clusters.isEmpty()) {
                adapter.setClusters(clusters);
                recyclerView.setVisibility(View.VISIBLE);
                emptyText.setVisibility(View.GONE);
            } else {
                recyclerView.setVisibility(View.GONE);
                emptyText.setVisibility(View.VISIBLE);
            }
        });

        viewModel.loadClusters();
    }
}
