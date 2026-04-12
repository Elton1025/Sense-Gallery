package com.example.sensegallery.ui;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.example.sensegallery.R;
import com.example.sensegallery.data.AppDatabase;
import com.example.sensegallery.data.PhotoLocationItem;

import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;

public class MapFragment extends Fragment {

    private static final long MAP_TILE_CACHE_MAX_BYTES = 64L * 1024L * 1024L;
    private static final long MAP_TILE_CACHE_TRIM_BYTES = 48L * 1024L * 1024L;

    private MapView mapView;
    private TextView emptyState;
    private ExecutorService mapExecutor;
    private Context appContext;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mapView = view.findViewById(R.id.placesMapView);
        emptyState = view.findViewById(R.id.mapEmptyState);
        mapExecutor = Executors.newSingleThreadExecutor();
        appContext = requireContext().getApplicationContext();

        IConfigurationProvider config = Configuration.getInstance();
        File osmdroidBase = new File(appContext.getCacheDir(), "osmdroid");
        File osmdroidTileCache = new File(osmdroidBase, "tiles");
        if (!osmdroidBase.exists()) {
            osmdroidBase.mkdirs();
        }
        if (!osmdroidTileCache.exists()) {
            osmdroidTileCache.mkdirs();
        }

        config.setUserAgentValue(appContext.getPackageName());
        config.setOsmdroidBasePath(osmdroidBase);
        config.setOsmdroidTileCache(osmdroidTileCache);
        config.setTileFileSystemCacheMaxBytes(MAP_TILE_CACHE_MAX_BYTES);
        config.setTileFileSystemCacheTrimBytes(MAP_TILE_CACHE_TRIM_BYTES);

        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);

        IMapController controller = mapView.getController();
        controller.setZoom(2.5);
        controller.setCenter(new GeoPoint(20.5937, 78.9629));

        loadPhotoLocations();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }
        loadPhotoLocations();
    }

    @Override
    public void onPause() {
        if (mapView != null) {
            mapView.onPause();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (mapExecutor != null) {
            mapExecutor.shutdownNow();
            mapExecutor = null;
        }
        appContext = null;
        mapView = null;
        emptyState = null;
        super.onDestroyView();
    }

    private void loadPhotoLocations() {
        if (mapExecutor == null || mapView == null || appContext == null) {
            return;
        }

        Context context = appContext;

        mapExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(context);
            List<PhotoLocationItem> locations = db.photoDao().getPhotosWithLocation();
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> renderMap(locations));
        });
    }

    private void renderMap(@NonNull List<PhotoLocationItem> locations) {
        if (mapView == null || emptyState == null) {
            return;
        }

        mapView.getOverlays().clear();

        if (locations.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            mapView.invalidate();
            return;
        }

        emptyState.setVisibility(View.GONE);

        List<GeoPoint> geoPoints = new ArrayList<>();
        DateFormat dateFormat = DateFormat.getDateInstance();

        for (PhotoLocationItem item : locations) {
            if (item.latitude == null || item.longitude == null) {
                continue;
            }

            GeoPoint point = new GeoPoint(item.latitude, item.longitude);
            geoPoints.add(point);

            Marker marker = new Marker(mapView);
            marker.setPosition(point);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle("Photo");
            marker.setSubDescription(dateFormat.format(new Date(item.dateTaken)));
            marker.setOnMarkerClickListener((clickedMarker, map) -> {
                if (item.mediaStoreUri != null) {
                    openPhotoViewer(item.mediaStoreUri);
                }
                return true;
            });

            mapView.getOverlays().add(marker);
        }

        if (geoPoints.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            mapView.invalidate();
            return;
        }

        if (geoPoints.size() == 1) {
            mapView.getController().setCenter(geoPoints.get(0));
            mapView.getController().setZoom(15.0);
        } else {
            BoundingBox box = BoundingBox.fromGeoPoints(geoPoints);
            mapView.zoomToBoundingBox(box, true);
        }

        mapView.invalidate();
    }

    private void openPhotoViewer(@NonNull String photoUri) {
        Bundle args = new Bundle();
        args.putInt("initialPosition", 0);
        args.putString("photoUri", photoUri);
        NavHostFragment.findNavController(this)
                .navigate(R.id.action_global_nav_photo_viewer, args);
    }
}
