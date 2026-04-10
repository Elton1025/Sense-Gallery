package com.example.sensegallery.ui;

import android.app.Activity;
import android.app.RecoverableSecurityException;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.example.sensegallery.R;
import com.example.sensegallery.gallery.GalleryViewModel;
import com.example.sensegallery.gallery.PhotoItem;

import java.io.InputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class PhotoViewerFragment extends Fragment {

    private int initialPosition;
    private long clusterId;
    private String photoUriArg;
    private PhotoPagerAdapter adapter;
    private ViewPager2 viewPager;
    private GalleryViewModel viewModel;
    private TextView counterText;
    private TextView fileNameText;
    private TextView resolutionText;
    private TextView sizeText;
    private View infoPanel;
    private ViewPager2.OnPageChangeCallback pageChangeCallback;
    private boolean hasAppliedInitialPosition = false;
    private Uri pendingDeleteUri;

    private final ActivityResultLauncher<IntentSenderRequest> deleteRequestLauncher = registerForActivityResult(
            new ActivityResultContracts.StartIntentSenderForResult(),
            result -> {
                Uri targetUri = pendingDeleteUri;
                pendingDeleteUri = null;
                if (targetUri == null || !isAdded()) {
                    return;
                }

                if (result.getResultCode() == Activity.RESULT_OK) {
                    onPhotoDeleted(targetUri);
                } else {
                    Toast.makeText(requireContext(), R.string.delete_photo_cancelled, Toast.LENGTH_SHORT).show();
                }
            });

    private final ExecutorService metadataExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger metadataRequestToken = new AtomicInteger(0);

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            initialPosition = getArguments().getInt("initialPosition", 0);
            clusterId = getArguments().getLong("clusterId", -1L);
            photoUriArg = getArguments().getString("photoUri");
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
        counterText = view.findViewById(R.id.photoCounterText);
        fileNameText = view.findViewById(R.id.photoFileNameText);
        resolutionText = view.findViewById(R.id.photoResolutionText);
        sizeText = view.findViewById(R.id.photoSizeText);
        infoPanel = view.findViewById(R.id.photoInfoPanel);

        ImageButton closeButton = view.findViewById(R.id.closeViewerButton);
        ImageButton shareButton = view.findViewById(R.id.sharePhotoButton);
        ImageButton infoButton = view.findViewById(R.id.infoPhotoButton);
        ImageButton deleteButton = view.findViewById(R.id.deletePhotoButton);

        adapter = new PhotoPagerAdapter();
        viewPager.setAdapter(adapter);

        closeButton.setOnClickListener(v -> requireActivity()
                .getOnBackPressedDispatcher()
                .onBackPressed());

        shareButton.setOnClickListener(v -> shareCurrentPhoto());
        infoButton.setOnClickListener(v -> toggleInfoPanel());
        deleteButton.setOnClickListener(v -> deleteCurrentPhoto());

        pageChangeCallback = new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateUiForPosition(position);
            }
        };
        viewPager.registerOnPageChangeCallback(pageChangeCallback);

        viewModel = new ViewModelProvider(this).get(GalleryViewModel.class);

        if (photoUriArg != null && !photoUriArg.trim().isEmpty()) {
            PhotoItem singlePhoto = new PhotoItem(Uri.parse(photoUriArg), 0L);
            adapter.setPhotos(Collections.singletonList(singlePhoto));
            viewPager.setCurrentItem(0, false);
            updateUiForPosition(0);
            hasAppliedInitialPosition = true;
            return;
        }

        if (clusterId != -1L) {
            // Cluster-specific viewing mode
            viewModel.getClusterPhotos().observe(getViewLifecycleOwner(), photos -> {
                if (photos != null && !photos.isEmpty()) {
                    adapter.setPhotos(photos);
                    int safePosition = resolveTargetPosition(photos.size());
                    viewPager.setCurrentItem(safePosition, false);
                    updateUiForPosition(safePosition);
                } else if (photos != null && photos.isEmpty()) {
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            });
            viewModel.loadClusterPhotos(clusterId);
        } else {
            // Global viewing mode (all photos)
            viewModel.getPhotos().observe(getViewLifecycleOwner(), photos -> {
                if (photos != null && !photos.isEmpty()) {
                    adapter.setPhotos(photos);
                    int safePosition = resolveTargetPosition(photos.size());
                    viewPager.setCurrentItem(safePosition, false);
                    updateUiForPosition(safePosition);
                } else if (photos != null && photos.isEmpty()) {
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            });
            viewModel.loadPhotos();
        }
    }

    private int resolveTargetPosition(int itemCount) {
        if (itemCount <= 0) {
            return 0;
        }

        if (!hasAppliedInitialPosition) {
            hasAppliedInitialPosition = true;
            return Math.max(0, Math.min(initialPosition, itemCount - 1));
        }

        return Math.max(0, Math.min(viewPager.getCurrentItem(), itemCount - 1));
    }

    private void toggleInfoPanel() {
        if (infoPanel == null) {
            return;
        }
        infoPanel.setVisibility(infoPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void updateUiForPosition(int position) {
        int total = adapter.getItemCount();
        if (total <= 0) {
            counterText.setText("");
            showMetadataUnavailable();
            return;
        }

        counterText.setText(getString(R.string.photo_position, position + 1, total));

        PhotoItem current = adapter.getPhotoAt(position);
        if (current == null) {
            showMetadataUnavailable();
            return;
        }

        loadMetadata(current.getContentUri());
    }

    private void loadMetadata(Uri photoUri) {
        fileNameText.setText(R.string.photo_info_loading);
        String loading = getString(R.string.photo_info_loading);
        resolutionText.setText(getString(R.string.photo_resolution, loading));
        sizeText.setText(getString(R.string.photo_size, loading));

        Context context = getContext();
        if (context == null) {
            showMetadataUnavailable();
            return;
        }

        Context appContext = context.getApplicationContext();
        ContentResolver resolver = appContext.getContentResolver();
        int requestToken = metadataRequestToken.incrementAndGet();

        metadataExecutor.execute(() -> {
            PhotoMetadata metadata = queryMetadata(appContext, resolver, photoUri);
            mainHandler.post(() -> {
                if (!isAdded() || getView() == null) {
                    return;
                }
                if (metadataRequestToken.get() != requestToken) {
                    return;
                }
                fileNameText.setText(metadata.fileName);
                resolutionText.setText(getString(R.string.photo_resolution, metadata.resolution));
                sizeText.setText(getString(R.string.photo_size, metadata.fileSize));
            });
        });
    }

    private PhotoMetadata queryMetadata(Context context, ContentResolver resolver, Uri photoUri) {
        String fileName = null;
        long fileSize = -1L;
        int width = -1;
        int height = -1;

        String[] projection = {
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT
        };

        try (Cursor cursor = resolver.query(photoUri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
                int widthColumn = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH);
                int heightColumn = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT);

                if (nameColumn != -1) {
                    fileName = cursor.getString(nameColumn);
                }
                if (sizeColumn != -1) {
                    fileSize = cursor.getLong(sizeColumn);
                }
                if (widthColumn != -1) {
                    width = cursor.getInt(widthColumn);
                }
                if (heightColumn != -1) {
                    height = cursor.getInt(heightColumn);
                }
            }
        } catch (Exception ignored) {
        }

        if (width <= 0 || height <= 0) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream inputStream = resolver.openInputStream(photoUri)) {
                if (inputStream != null) {
                    BitmapFactory.decodeStream(inputStream, null, options);
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        width = options.outWidth;
                        height = options.outHeight;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (fileName == null || fileName.trim().isEmpty()) {
            String lastSegment = photoUri.getLastPathSegment();
            if (lastSegment != null && !lastSegment.trim().isEmpty()) {
                fileName = lastSegment;
            } else {
                fileName = context.getString(R.string.photo_info_filename_fallback);
            }
        }

        String unknown = context.getString(R.string.photo_info_unknown);
        String resolutionValue = unknown;
        if (width > 0 && height > 0) {
            resolutionValue = context.getString(R.string.photo_resolution_value, width, height);
        }

        String fileSizeValue = unknown;
        if (fileSize >= 0L) {
            fileSizeValue = Formatter.formatShortFileSize(context, fileSize);
        }

        return new PhotoMetadata(fileName, resolutionValue, fileSizeValue);
    }

    private void showMetadataUnavailable() {
        fileNameText.setText(R.string.photo_info_filename_fallback);
        resolutionText.setText(getString(
                R.string.photo_resolution,
                getString(R.string.photo_info_unknown)));
        sizeText.setText(getString(
                R.string.photo_size,
                getString(R.string.photo_info_unknown)));
    }

    private void shareCurrentPhoto() {
        PhotoItem current = adapter.getPhotoAt(viewPager.getCurrentItem());
        if (current == null) {
            return;
        }

        Uri photoUri = current.getContentUri();

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("image/*");
        shareIntent.putExtra(Intent.EXTRA_STREAM, photoUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shareIntent.setClipData(ClipData.newUri(
                requireContext().getContentResolver(),
                getString(R.string.share_photo_clip_label),
                photoUri));

        Intent chooser = Intent.createChooser(shareIntent, getString(R.string.share_photo));
        try {
            startActivity(chooser);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.share_photo_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteCurrentPhoto() {
        PhotoItem current = adapter.getPhotoAt(viewPager.getCurrentItem());
        if (current == null) {
            return;
        }

        Uri photoUri = current.getContentUri();
        ContentResolver resolver = requireContext().getContentResolver();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                IntentSender intentSender = MediaStore
                        .createDeleteRequest(resolver, Collections.singletonList(photoUri))
                        .getIntentSender();
                launchDeleteRequest(photoUri, intentSender);
            } catch (Exception e) {
                Toast.makeText(requireContext(), R.string.delete_photo_error, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        try {
            int deleted = resolver.delete(photoUri, null, null);
            if (deleted > 0) {
                onPhotoDeleted(photoUri);
            } else {
                Toast.makeText(requireContext(), R.string.delete_photo_error, Toast.LENGTH_SHORT).show();
            }
        } catch (SecurityException securityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && securityException instanceof RecoverableSecurityException) {
                RecoverableSecurityException recoverable = (RecoverableSecurityException) securityException;
                launchDeleteRequest(
                        photoUri,
                        recoverable.getUserAction().getActionIntent().getIntentSender());
            } else {
                Toast.makeText(requireContext(), R.string.delete_photo_error, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.delete_photo_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchDeleteRequest(Uri photoUri, IntentSender intentSender) {
        pendingDeleteUri = photoUri;
        IntentSenderRequest request = new IntentSenderRequest.Builder(intentSender).build();
        deleteRequestLauncher.launch(request);
    }

    private void onPhotoDeleted(Uri photoUri) {
        int removedIndex = adapter.removePhotoByUri(photoUri);
        Toast.makeText(requireContext(), R.string.delete_photo_success, Toast.LENGTH_SHORT).show();

        if (clusterId != -1L) {
            viewModel.loadClusterPhotos(clusterId);
        } else if (photoUriArg == null || photoUriArg.trim().isEmpty()) {
            viewModel.loadPhotos();
        }

        int count = adapter.getItemCount();
        if (count <= 0) {
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return;
        }

        int fallbackIndex = Math.max(0, Math.min(viewPager.getCurrentItem(), count - 1));
        int targetIndex = removedIndex >= 0 ? Math.min(removedIndex, count - 1) : fallbackIndex;
        viewPager.setCurrentItem(targetIndex, false);
        updateUiForPosition(targetIndex);
    }

    @Override
    public void onDestroyView() {
        if (viewPager != null && pageChangeCallback != null) {
            viewPager.unregisterOnPageChangeCallback(pageChangeCallback);
        }
        pendingDeleteUri = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        metadataExecutor.shutdownNow();
        super.onDestroy();
    }

    private static class PhotoMetadata {
        final String fileName;
        final String resolution;
        final String fileSize;

        PhotoMetadata(String fileName, String resolution, String fileSize) {
            this.fileName = fileName;
            this.resolution = resolution;
            this.fileSize = fileSize;
        }
    }
}
