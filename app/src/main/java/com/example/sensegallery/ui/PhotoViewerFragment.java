package com.example.sensegallery.ui;

import android.app.Activity;
import android.app.RecoverableSecurityException;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.exifinterface.media.ExifInterface;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.example.sensegallery.R;
import com.example.sensegallery.data.AppDatabase;
import com.example.sensegallery.gallery.GalleryViewModel;
import com.example.sensegallery.gallery.PhotoItem;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class PhotoViewerFragment extends Fragment {

    private static final int MIN_JPEG_QUALITY = 18;
    private static final int MAX_JPEG_QUALITY = 95;

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
    private final ExecutorService imageOperationExecutor = Executors.newSingleThreadExecutor();
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
        ImageButton cropButton = view.findViewById(R.id.cropPhotoButton);
        ImageButton compressButton = view.findViewById(R.id.compressPhotoButton);
        ImageButton infoButton = view.findViewById(R.id.infoPhotoButton);
        ImageButton deleteButton = view.findViewById(R.id.deletePhotoButton);

        adapter = new PhotoPagerAdapter();
        viewPager.setAdapter(adapter);

        closeButton.setOnClickListener(v -> requireActivity()
                .getOnBackPressedDispatcher()
                .onBackPressed());

        shareButton.setOnClickListener(v -> shareCurrentPhoto());
        cropButton.setOnClickListener(v -> showCropDialog());
        compressButton.setOnClickListener(v -> showCompressionDialog());
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

        return new PhotoMetadata(fileName, resolutionValue, fileSizeValue, fileSize);
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

    private void showCompressionDialog() {
        if (getCurrentPhotoUri() == null) {
            return;
        }

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_compress_image, null, false);
        SeekBar levelSeekBar = dialogView.findViewById(R.id.compressionLevelSeekBar);
        TextView valueText = dialogView.findViewById(R.id.compressionLevelValueText);
        TextView hintText = dialogView.findViewById(R.id.compressionHintText);

        updateCompressionPreviewTexts(levelSeekBar.getProgress(), valueText, hintText);
        levelSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateCompressionPreviewTexts(progress, valueText, hintText);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.compress_dialog_title)
                .setView(dialogView)
                .setPositiveButton(R.string.compress_action_save,
                        (dialog, which) -> compressCurrentPhoto(levelSeekBar.getProgress()))
                .setNegativeButton(R.string.cluster_curation_cancel, null)
                .show();
    }

    private void updateCompressionPreviewTexts(int level, @NonNull TextView valueText, @NonNull TextView hintText) {
        int quality = compressionLevelToQuality(level);
        int maxSide = compressionLevelToMaxSide(level);
        valueText.setText(getString(R.string.compress_level_value, level));
        hintText.setText(getString(R.string.compress_hint_dynamic, quality, maxSide));
    }

    private void compressCurrentPhoto(int compressionLevel) {
        Uri sourceUri = getCurrentPhotoUri();
        if (sourceUri == null) {
            return;
        }

        Toast.makeText(requireContext(), R.string.compress_processing, Toast.LENGTH_SHORT).show();
        imageOperationExecutor.execute(() -> {
            try {
                ContentResolver resolver = requireContext().getContentResolver();
                PhotoMetadata metadata = queryMetadata(requireContext(), resolver, sourceUri);
                Bitmap sourceBitmap = decodeBitmapWithOrientation(
                        resolver,
                        sourceUri,
                        compressionLevelToMaxSide(compressionLevel));

                if (sourceBitmap == null) {
                    throw new IllegalStateException("Bitmap decode failed");
                }

                byte[] compressedJpeg = compressAdaptiveJpeg(
                        sourceBitmap,
                        compressionLevel,
                        metadata.fileSizeBytes);
                sourceBitmap.recycle();

                Uri savedUri = saveJpegCopy(
                        resolver,
                        metadata.fileName,
                        "compressed",
                        compressedJpeg);

                mainHandler.post(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    Toast.makeText(requireContext(), R.string.compress_success, Toast.LENGTH_SHORT).show();
                    refreshAfterNewImageSaved(savedUri);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    Toast.makeText(requireContext(), R.string.compress_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showCropDialog() {
        if (getCurrentPhotoUri() == null) {
            return;
        }

        CharSequence[] options = new CharSequence[] {
                getString(R.string.crop_option_square),
                getString(R.string.crop_option_portrait),
                getString(R.string.crop_option_story),
                getString(R.string.crop_option_landscape)
        };

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.crop_dialog_title)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            cropCurrentPhoto(1f, 1f);
                            break;
                        case 1:
                            cropCurrentPhoto(4f, 5f);
                            break;
                        case 2:
                            cropCurrentPhoto(9f, 16f);
                            break;
                        case 3:
                            cropCurrentPhoto(16f, 9f);
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton(R.string.cluster_curation_cancel, null)
                .show();
    }

    private void cropCurrentPhoto(float aspectWidth, float aspectHeight) {
        Uri sourceUri = getCurrentPhotoUri();
        if (sourceUri == null) {
            return;
        }

        Toast.makeText(requireContext(), R.string.crop_processing, Toast.LENGTH_SHORT).show();
        imageOperationExecutor.execute(() -> {
            try {
                ContentResolver resolver = requireContext().getContentResolver();
                PhotoMetadata metadata = queryMetadata(requireContext(), resolver, sourceUri);
                Bitmap sourceBitmap = decodeBitmapWithOrientation(resolver, sourceUri, 4096);
                if (sourceBitmap == null) {
                    throw new IllegalStateException("Bitmap decode failed");
                }

                Bitmap cropped = cropCenter(sourceBitmap, aspectWidth / aspectHeight);
                if (cropped != sourceBitmap) {
                    sourceBitmap.recycle();
                }

                byte[] croppedJpeg = encodeJpeg(cropped, 92);
                cropped.recycle();

                Uri savedUri = saveJpegCopy(
                        resolver,
                        metadata.fileName,
                        "crop",
                        croppedJpeg);

                mainHandler.post(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    Toast.makeText(requireContext(), R.string.crop_success, Toast.LENGTH_SHORT).show();
                    refreshAfterNewImageSaved(savedUri);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    Toast.makeText(requireContext(), R.string.crop_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Nullable
    private Uri getCurrentPhotoUri() {
        PhotoItem current = adapter.getPhotoAt(viewPager.getCurrentItem());
        return current != null ? current.getContentUri() : null;
    }

    private int compressionLevelToQuality(int level) {
        int clamped = Math.max(0, Math.min(100, level));
        return Math.max(MIN_JPEG_QUALITY,
                Math.min(MAX_JPEG_QUALITY, Math.round(MAX_JPEG_QUALITY - (clamped * 0.72f))));
    }

    private int compressionLevelToMaxSide(int level) {
        int clamped = Math.max(0, Math.min(100, level));
        return Math.max(1280, Math.round(4096 - (clamped * 28.16f)));
    }

    @Nullable
    private Bitmap decodeBitmapWithOrientation(@NonNull ContentResolver resolver,
            @NonNull Uri photoUri,
            int maxSide) throws Exception {
        Bitmap decoded = decodeScaledBitmap(resolver, photoUri, maxSide);
        if (decoded == null) {
            return null;
        }

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream inputStream = resolver.openInputStream(photoUri)) {
            if (inputStream != null) {
                ExifInterface exif = new ExifInterface(inputStream);
                orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {
        }

        Bitmap adjusted = applyExifOrientation(decoded, orientation);
        if (adjusted != decoded) {
            decoded.recycle();
        }
        return adjusted;
    }

    @Nullable
    private Bitmap decodeScaledBitmap(@NonNull ContentResolver resolver, @NonNull Uri photoUri, int maxSide)
            throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = resolver.openInputStream(photoUri)) {
            if (stream == null) {
                return null;
            }
            BitmapFactory.decodeStream(stream, null, bounds);
        }

        int srcWidth = bounds.outWidth;
        int srcHeight = bounds.outHeight;
        if (srcWidth <= 0 || srcHeight <= 0) {
            return null;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateInSampleSize(srcWidth, srcHeight, maxSide);

        Bitmap decoded;
        try (InputStream stream = resolver.openInputStream(photoUri)) {
            if (stream == null) {
                return null;
            }
            decoded = BitmapFactory.decodeStream(stream, null, options);
        }

        if (decoded == null) {
            return null;
        }

        int longest = Math.max(decoded.getWidth(), decoded.getHeight());
        if (longest > maxSide) {
            float scale = maxSide / (float) longest;
            int targetWidth = Math.max(1, Math.round(decoded.getWidth() * scale));
            int targetHeight = Math.max(1, Math.round(decoded.getHeight() * scale));
            Bitmap resized = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true);
            if (resized != decoded) {
                decoded.recycle();
            }
            return resized;
        }

        return decoded;
    }

    private int calculateInSampleSize(int srcWidth, int srcHeight, int maxSide) {
        int inSample = 1;
        while ((srcWidth / inSample) > maxSide || (srcHeight / inSample) > maxSide) {
            inSample *= 2;
        }
        return Math.max(1, inSample);
    }

    @NonNull
    private Bitmap applyExifOrientation(@NonNull Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        boolean needsTransform = true;

        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90);
                break;
            default:
                needsTransform = false;
                break;
        }

        if (!needsTransform) {
            return bitmap;
        }

        try {
            return Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    matrix,
                    true);
        } catch (OutOfMemoryError oom) {
            return bitmap;
        }
    }

    private byte[] compressAdaptiveJpeg(@NonNull Bitmap sourceBitmap, int compressionLevel, long originalBytes) {
        long baselineBytes = originalBytes > 0L
                ? originalBytes
                : (long) sourceBitmap.getWidth() * sourceBitmap.getHeight() * 3L;
        float reductionFactor = 0.20f + (0.70f * (compressionLevel / 100f));
        long targetBytes = (long) (baselineBytes * (1f - reductionFactor));
        targetBytes = Math.max(120L * 1024L, targetBytes);

        Bitmap working = sourceBitmap;
        byte[] output = encodeJpegForTarget(working, targetBytes);

        for (int attempt = 0; attempt < 3; attempt++) {
            if (output.length <= targetBytes) {
                break;
            }

            int longest = Math.max(working.getWidth(), working.getHeight());
            if (longest <= 1024) {
                break;
            }

            int nextWidth = Math.max(640, Math.round(working.getWidth() * 0.85f));
            int nextHeight = Math.max(640, Math.round(working.getHeight() * 0.85f));
            Bitmap downscaled = Bitmap.createScaledBitmap(working, nextWidth, nextHeight, true);

            if (working != sourceBitmap) {
                working.recycle();
            }
            working = downscaled;
            output = encodeJpegForTarget(working, targetBytes);
        }

        if (working != sourceBitmap) {
            working.recycle();
        }
        return output;
    }

    @NonNull
    private byte[] encodeJpegForTarget(@NonNull Bitmap bitmap, long targetBytes) {
        byte[] bestUnderTarget = null;
        int low = MIN_JPEG_QUALITY;
        int high = MAX_JPEG_QUALITY;

        while (low <= high) {
            int quality = (low + high) / 2;
            byte[] bytes = encodeJpeg(bitmap, quality);

            if (bytes.length <= targetBytes) {
                bestUnderTarget = bytes;
                low = quality + 1;
            } else {
                high = quality - 1;
            }
        }

        if (bestUnderTarget != null) {
            return bestUnderTarget;
        }
        return encodeJpeg(bitmap, compressionLevelToQuality(100));
    }

    @NonNull
    private byte[] encodeJpeg(@NonNull Bitmap bitmap, int quality) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output);
        return output.toByteArray();
    }

    @NonNull
    private Bitmap cropCenter(@NonNull Bitmap bitmap, float targetAspectRatio) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return bitmap;
        }

        float srcRatio = width / (float) height;
        int cropWidth = width;
        int cropHeight = height;

        if (srcRatio > targetAspectRatio) {
            cropWidth = Math.round(height * targetAspectRatio);
        } else {
            cropHeight = Math.round(width / targetAspectRatio);
        }

        cropWidth = Math.max(1, Math.min(width, cropWidth));
        cropHeight = Math.max(1, Math.min(height, cropHeight));

        int left = Math.max(0, (width - cropWidth) / 2);
        int top = Math.max(0, (height - cropHeight) / 2);

        if (cropWidth == width && cropHeight == height) {
            return bitmap;
        }
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight);
    }

    @NonNull
    private Uri saveJpegCopy(@NonNull ContentResolver resolver,
            @NonNull String sourceFileName,
            @NonNull String suffix,
            @NonNull byte[] jpegBytes) throws Exception {
        String baseName = sourceFileName;
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }

        String displayName = baseName + "_" + suffix + "_" + Calendar.getInstance().getTimeInMillis() + ".jpg";

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000L);
        values.put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/SenseGallery");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }

        Uri outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) {
            throw new IllegalStateException("Failed to create MediaStore row");
        }

        try {
            try (OutputStream outputStream = resolver.openOutputStream(outputUri, "w")) {
                if (outputStream == null) {
                    throw new IllegalStateException("Failed to open output stream");
                }
                outputStream.write(jpegBytes);
                outputStream.flush();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(outputUri, done, null, null);
            }

            return outputUri;
        } catch (Exception e) {
            resolver.delete(outputUri, null, null);
            throw e;
        }
    }

    private void refreshAfterNewImageSaved(@NonNull Uri savedUri) {
        if (clusterId != -1L) {
            viewModel.loadClusterPhotos(clusterId);
        } else if (photoUriArg == null || photoUriArg.trim().isEmpty()) {
            viewModel.loadPhotos();
        }

        // Keep share behavior unchanged for current photo, but ensure new copy is visible
        // through gallery refresh.
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

        String deletedUri = photoUri.toString();
        Context appContext = requireContext().getApplicationContext();
        imageOperationExecutor.execute(() -> AppDatabase.getInstance(appContext)
                .photoDao()
                .deletePhotoByUri(deletedUri));

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
        imageOperationExecutor.shutdownNow();
        super.onDestroy();
    }

    private static class PhotoMetadata {
        final String fileName;
        final String resolution;
        final String fileSize;
        final long fileSizeBytes;

        PhotoMetadata(String fileName, String resolution, String fileSize, long fileSizeBytes) {
            this.fileName = fileName;
            this.resolution = resolution;
            this.fileSize = fileSize;
            this.fileSizeBytes = fileSizeBytes;
        }
    }
}
