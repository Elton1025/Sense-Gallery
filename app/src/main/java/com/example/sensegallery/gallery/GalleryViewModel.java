package com.example.sensegallery.gallery;

import android.app.Application;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.sensegallery.data.AppDatabase;
import com.example.sensegallery.data.ClusterDao;
import com.example.sensegallery.data.ClusterEntity;
import com.example.sensegallery.data.FaceDao;
import com.example.sensegallery.data.FaceEntity;
import com.example.sensegallery.data.PhotoEntity;
import com.example.sensegallery.ui.ClusterDisplayItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ViewModel that queries the device's MediaStore for all images
 * and exposes them as LiveData for the gallery grid.
 * Also handles cluster data loading.
 */
public class GalleryViewModel extends AndroidViewModel {

    private static final String TAG = "GalleryVM";
    private static final float CLIP_THRESHOLD = 0.50f;
    private static final int MAX_CLIP_RESULTS = 20;

    private final MutableLiveData<List<PhotoItem>> photosLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<PhotoItem>> clusterPhotosLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ClusterDisplayItem>> clustersLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<PhotoItem>> semanticSearchResults = new MutableLiveData<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();

    private com.example.sensegallery.ai.CLIPInferenceEngine clipEngine;

    public GalleryViewModel(@NonNull Application application) {
        super(application);
    }

    public LiveData<List<PhotoItem>> getPhotos() {
        return photosLiveData;
    }

    public LiveData<List<PhotoItem>> getSemanticSearchResults() {
        return semanticSearchResults;
    }

    public void loadPhotos() {
        executor.execute(() -> {
            List<PhotoItem> photos = queryMediaStore();
            photosLiveData.postValue(photos);
        });
    }

    private List<PhotoItem> queryMediaStore() {
        List<PhotoItem> photos = new ArrayList<>();
        ContentResolver resolver = getApplication().getContentResolver();

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
        };

        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";

        try (Cursor cursor = resolver.query(collection, projection, null, null, sortOrder)) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    long dateAdded = cursor.getLong(dateAddedColumn);

                    Uri contentUri = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            String.valueOf(id));

                    photos.add(new PhotoItem(contentUri, dateAdded));
                }
            }
        }

        return photos;
    }

    // --- Cluster Detail Photos ---

    public LiveData<List<PhotoItem>> getClusterPhotos() {
        return clusterPhotosLiveData;
    }

    public void loadClusterPhotos(long clusterId) {
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplication());
            FaceDao faceDao = db.faceDao();

            Log.d("GalleryVM", "Loading photos for cluster ID: " + clusterId);

            // Primary approach: JOIN query
            List<PhotoEntity> photoEntities = faceDao.getPhotosForCluster(clusterId);
            Log.d("GalleryVM", "JOIN query returned " + photoEntities.size() + " photos");

            // Debug: check how many faces are in this cluster
            List<FaceEntity> facesInCluster = faceDao.getFacesInCluster(clusterId);
            Log.d("GalleryVM", "Faces in cluster " + clusterId + ": " + facesInCluster.size());

            // If JOIN returns empty but faces exist, do a manual lookup
            if (photoEntities.isEmpty() && !facesInCluster.isEmpty()) {
                Log.d("GalleryVM", "JOIN failed! Trying manual photo lookup...");

                java.util.Set<Long> seenPhotoIds = new java.util.HashSet<>();
                List<PhotoEntity> manualPhotos = new ArrayList<>();

                for (FaceEntity face : facesInCluster) {
                    Log.d("GalleryVM", "  Face id=" + face.id + " photoId=" + face.photoId
                            + " clusterId=" + face.clusterId);

                    if (seenPhotoIds.contains(face.photoId))
                        continue;
                    seenPhotoIds.add(face.photoId);

                    // Look up the photo by ID
                    List<PhotoEntity> allPhotos = db.photoDao().getAllPhotos();
                    for (PhotoEntity p : allPhotos) {
                        if (p.id == face.photoId) {
                            manualPhotos.add(p);
                            Log.d("GalleryVM", "  Found photo: id=" + p.id
                                    + " uri=" + p.mediaStoreUri);
                            break;
                        }
                    }
                }

                photoEntities = manualPhotos;
                Log.d("GalleryVM", "Manual lookup found " + photoEntities.size() + " photos");
            }

            List<PhotoItem> items = new ArrayList<>();
            for (PhotoEntity entity : photoEntities) {
                items.add(new PhotoItem(Uri.parse(entity.mediaStoreUri), entity.dateTaken));
            }

            Log.d("GalleryVM", "Posting " + items.size() + " PhotoItems to LiveData");
            clusterPhotosLiveData.postValue(items);
        });
    }

    // --- Cluster List with Display Info ---

    public LiveData<List<ClusterDisplayItem>> getClusters() {
        return clustersLiveData;
    }

    /**
     * Loads all clusters with their cover photo URIs and face counts.
     * This provides the ClustersAdapter with everything it needs to display
     * cluster cards with face thumbnails and photo counts.
     */
    public void loadClusters() {
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplication());
            ClusterDao clusterDao = db.clusterDao();
            FaceDao faceDao = db.faceDao();

            List<ClusterEntity> entities = clusterDao.getAllClusters();
            List<ClusterDisplayItem> displayItems = new ArrayList<>();

            for (ClusterEntity entity : entities) {
                // Get cover photo URI
                String coverUri = null;
                float boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f;

                if (entity.coverPhotoId != null) {
                    coverUri = clusterDao.getPhotoUri(entity.coverPhotoId);
                    FaceEntity face = faceDao.getFaceForCover(entity.coverPhotoId, entity.id);
                    if (face != null) {
                        boxLeft = face.boxLeft;
                        boxTop = face.boxTop;
                        boxRight = face.boxRight;
                        boxBottom = face.boxBottom;
                    }
                }

                // If no cover photo set, try to get the first photo in the cluster
                if (coverUri == null) {
                    List<PhotoEntity> photos = faceDao.getPhotosForCluster(entity.id);
                    if (!photos.isEmpty()) {
                        PhotoEntity firstPhoto = photos.get(0);
                        coverUri = firstPhoto.mediaStoreUri;
                        // Also update the coverPhotoId for next time
                        clusterDao.updateCoverPhoto(entity.id, firstPhoto.id);

                        FaceEntity face = faceDao.getFaceForCover(firstPhoto.id, entity.id);
                        if (face != null) {
                            boxLeft = face.boxLeft;
                            boxTop = face.boxTop;
                            boxRight = face.boxRight;
                            boxBottom = face.boxBottom;
                        }
                    }
                }

                // Get face count
                int faceCount = clusterDao.getFaceCountInCluster(entity.id);

                displayItems.add(new ClusterDisplayItem(
                        entity.id, entity.label, coverUri, faceCount,
                        boxLeft, boxTop, boxRight, boxBottom));
            }

            // Sort clusters by face count (descending), then alphabetically
            java.util.Collections.sort(displayItems, (a, b) -> {
                int countDiff = Integer.compare(b.faceCount, a.faceCount);
                if (countDiff != 0)
                    return countDiff;
                if (a.label != null && b.label != null)
                    return a.label.compareToIgnoreCase(b.label);
                return 0;
            });

            clustersLiveData.postValue(displayItems);
        });
    }

    public void renameCluster(long clusterId, String newName) {
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplication());
            ClusterDao clusterDao = db.clusterDao();
            clusterDao.updateLabel(clusterId, newName);
            // Refresh the clusters list so observers get the updated name
            loadClusters();
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdown();
        searchExecutor.shutdown();
        if (clipEngine != null) {
            clipEngine.close();
            clipEngine = null;
        }
    }

    // --- Semantic Search ---

    /**
     * Performs semantic search combining face name matching and CLIP similarity.
     * Runs on a separate searchExecutor so it doesn't block indexing.
     *
     * @param query User's text query (e.g., "Virat holding bat")
     */
    public void semanticSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            semanticSearchResults.postValue(new ArrayList<>());
            return;
        }

        searchExecutor.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(getApplication());
                String lowerQuery = query.toLowerCase().trim();

                // Step 1: Check if any cluster names match part of the query
                ClusterDao clusterDao = db.clusterDao();
                FaceDao faceDao = db.faceDao();
                List<ClusterEntity> allClusters = clusterDao.getAllClusters();

                Set<Long> facePhotoIds = new HashSet<>();
                List<String> queryWords = new ArrayList<>();
                for (String w : lowerQuery.split("\\s+")) {
                    if (!w.isEmpty())
                        queryWords.add(w);
                }

                // Word-level matching: check each query word against cluster name words
                // "virat blue" -> ["virat", "blue"]
                // Cluster "virat kohli" has words ["virat", "kohli"]
                // "virat" matches -> add cluster photos, keep "blue" for CLIP
                List<String> unmatchedWords = new ArrayList<>(queryWords);

                for (ClusterEntity cluster : allClusters) {
                    if (cluster.label == null || cluster.label.isEmpty())
                        continue;

                    String[] clusterWords = cluster.label.toLowerCase().split("\\s+");
                    boolean anyWordMatches = false;

                    for (String cw : clusterWords) {
                        for (String qw : queryWords) {
                            if (cw.startsWith(qw) || qw.startsWith(cw)) {
                                anyWordMatches = true;
                                unmatchedWords.remove(qw);
                            }
                        }
                    }

                    if (anyWordMatches) {
                        List<PhotoEntity> clusterPhotos = faceDao.getPhotosForCluster(cluster.id);
                        for (PhotoEntity p : clusterPhotos) {
                            facePhotoIds.add(p.id);
                        }
                    }
                }

                // Remaining unmatched words become the CLIP query
                StringBuilder sb = new StringBuilder();
                for (String w : unmatchedWords) {
                    if (sb.length() > 0)
                        sb.append(" ");
                    sb.append(w);
                }
                String clipQuery = sb.toString().trim();

                boolean hasNameMatch = !facePhotoIds.isEmpty();
                boolean hasClipQuery = !clipQuery.isEmpty();

                Log.d(TAG, "Search: nameMatch=" + hasNameMatch + " (" + facePhotoIds.size()
                        + " photos), clipQuery=\"" + clipQuery + "\"");

                // Step 2: If there's a CLIP text query, run semantic similarity
                List<ScoredPhoto> clipResults = new ArrayList<>();

                if (hasClipQuery) {
                    // Lazy-init CLIP engine
                    if (clipEngine == null) {
                        clipEngine = new com.example.sensegallery.ai.CLIPInferenceEngine(
                                getApplication());
                    }

                    if (clipEngine.isTextEncoderReady()) {
                        float[] textEmbedding = clipEngine.encodeText(clipQuery);
                        if (textEmbedding != null) {
                            com.example.sensegallery.data.PhotoDao photoDao = db.photoDao();
                            List<PhotoEntity> indexed = photoDao.getPhotosWithClipEmbeddings();

                            for (PhotoEntity photo : indexed) {
                                if (photo.clipEmbedding != null) {
                                    float[] imgEmb = com.example.sensegallery.data.EmbeddingTypeConverter
                                            .toFloatArray(photo.clipEmbedding);
                                    float score = com.example.sensegallery.ai.CLIPInferenceEngine
                                            .cosineSimilarity(textEmbedding, imgEmb);

                                    if (score >= CLIP_THRESHOLD) {
                                        clipResults.add(new ScoredPhoto(
                                                photo.id, photo.mediaStoreUri, photo.dateTaken, score));
                                    }
                                }
                            }

                            // Sort by similarity descending
                            clipResults.sort((a, b) -> Float.compare(b.score, a.score));
                            Log.d(TAG, "CLIP found " + clipResults.size()
                                    + " results above threshold " + CLIP_THRESHOLD);

                            // Smart relevance cutoff: detect large score drop
                            clipResults = filterByRelevanceDrop(clipResults);
                        }
                    } else {
                        Log.w(TAG, "CLIP text encoder not ready");
                    }
                }

                // Step 3: Combine results
                List<PhotoItem> finalResults = new ArrayList<>();

                if (hasNameMatch && hasClipQuery) {
                    // INTERSECTION: photos that match BOTH face-name AND CLIP
                    for (ScoredPhoto sp : clipResults) {
                        if (facePhotoIds.contains(sp.photoId)) {
                            finalResults.add(new PhotoItem(
                                    Uri.parse(sp.uri), sp.dateTaken));
                        }
                    }
                    Log.d(TAG, "Intersection: " + finalResults.size() + " results");

                    // Fallback: if intersection is empty (threshold too strict),
                    // show all photos of the matched person instead of nothing
                    if (finalResults.isEmpty()) {
                        Log.d(TAG, "Intersection empty, falling back to name-only results");
                        for (Long photoId : facePhotoIds) {
                            PhotoEntity p = findPhotoById(db, photoId);
                            if (p != null) {
                                finalResults.add(new PhotoItem(
                                        Uri.parse(p.mediaStoreUri), p.dateTaken));
                            }
                        }
                    }
                } else if (hasNameMatch) {
                    // Only face-name match — return those photos
                    for (Long photoId : facePhotoIds) {
                        PhotoEntity p = findPhotoById(db, photoId);
                        if (p != null) {
                            finalResults.add(new PhotoItem(
                                    Uri.parse(p.mediaStoreUri), p.dateTaken));
                        }
                    }
                } else if (hasClipQuery) {
                    // Only CLIP results
                    for (ScoredPhoto sp : clipResults) {
                        finalResults.add(new PhotoItem(
                                Uri.parse(sp.uri), sp.dateTaken));
                    }
                }

                semanticSearchResults.postValue(finalResults);

            } catch (Exception e) {
                Log.e(TAG, "Semantic search error: " + e.getMessage(), e);
                semanticSearchResults.postValue(new ArrayList<>());
            }
        });
    }

    /**
     * Smart relevance filtering: keeps only the truly relevant results by
     * detecting a significant score drop between consecutive results.
     * Also caps at MAX_CLIP_RESULTS.
     *
     * Example scores: [0.35, 0.33, 0.31, 0.30, 0.22, 0.21, 0.20...]
     * A drop from 0.30 to 0.22 (>25% gap relative to top score) triggers cutoff.
     */
    private List<ScoredPhoto> filterByRelevanceDrop(List<ScoredPhoto> sorted) {
        if (sorted.isEmpty())
            return sorted;

        // Cap at MAX results first
        int limit = Math.min(sorted.size(), MAX_CLIP_RESULTS);

        // Detect relevance drop: if score drops more than 40% of the top score's value
        float topScore = sorted.get(0).score;
        float dropThreshold = topScore * 0.40f; // 40% relative gap

        int cutoff = limit;
        for (int i = 1; i < limit; i++) {
            float gap = sorted.get(i - 1).score - sorted.get(i).score;
            if (gap > dropThreshold) {
                cutoff = i;
                Log.d(TAG, "Relevance cutoff at #" + i + ": score dropped from "
                        + String.format("%.3f", sorted.get(i - 1).score) + " to "
                        + String.format("%.3f", sorted.get(i).score)
                        + " (gap=" + String.format("%.3f", gap) + ")");
                break;
            }
        }

        List<ScoredPhoto> filtered = sorted.subList(0, cutoff);
        Log.d(TAG, "Relevance filter: kept " + filtered.size() + " of " + sorted.size()
                + " (top=" + String.format("%.3f", topScore)
                + ", last=" + String.format("%.3f", filtered.get(filtered.size() - 1).score) + ")");
        return new ArrayList<>(filtered);
    }

    private PhotoEntity findPhotoById(AppDatabase db, long photoId) {
        List<PhotoEntity> all = db.photoDao().getAllPhotos();
        for (PhotoEntity p : all) {
            if (p.id == photoId)
                return p;
        }
        return null;
    }

    /** Helper class for scoring CLIP results */
    private static class ScoredPhoto {
        final long photoId;
        final String uri;
        final long dateTaken;
        final float score;

        ScoredPhoto(long photoId, String uri, long dateTaken, float score) {
            this.photoId = photoId;
            this.uri = uri;
            this.dateTaken = dateTaken;
            this.score = score;
        }
    }
}
