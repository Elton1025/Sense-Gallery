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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    private static final float MIN_CLIP_SCORE = 0.18f;
    private static final float LOW_CONFIDENCE_CLIP_SCORE = 0.12f;
    private static final float EXACT_QUERY_RELATIVE_SCORE_FLOOR = 0.92f;
    private static final float EXACT_QUERY_SCORE_MARGIN = 0.04f;
    private static final int MAX_CLIP_RESULTS = 20;
        private static final Set<String> STRICT_CONJUNCTION_WORDS = new HashSet<>(Arrays.asList(
            "and", "together", "both", "with"));
    private static final Set<String> QUERY_STOP_WORDS = new HashSet<>(Arrays.asList(
            "show", "me", "photo", "photos", "image", "images", "pic", "pics", "picture",
            "pictures", "of", "in", "the", "a", "an", "this", "that", "with", "and", "together",
            "single", "one", "same", "where", "there", "are", "is", "from", "to", "at", "on", "for"));

    private final MutableLiveData<List<PhotoItem>> photosLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<PhotoItem>> clusterPhotosLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ClusterDisplayItem>> clustersLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<PhotoItem>> semanticSearchResults = new MutableLiveData<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();

    private com.example.sensegallery.ai.CLIPInferenceEngine clipEngine;
    private com.example.sensegallery.ai.LLMQueryParser llmQueryParser;

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
            Set<Long> seenPhotoIds = new HashSet<>();
            for (PhotoEntity entity : photoEntities) {
                if (!seenPhotoIds.add(entity.id)) {
                    continue;
                }
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
        * Loads all clusters with their cover photo URIs and unique photo counts.
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

                // Get unique photo count for card display
                int photoCount = clusterDao.getPhotoCountInCluster(entity.id);

                displayItems.add(new ClusterDisplayItem(
                        entity.id, entity.label, coverUri, photoCount,
                        boxLeft, boxTop, boxRight, boxBottom));
            }

            // Sort clusters by photo count (descending), then alphabetically
            java.util.Collections.sort(displayItems, (a, b) -> {
                int countDiff = Integer.compare(b.photoCount, a.photoCount);
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
        if (llmQueryParser != null) {
            llmQueryParser.close();
            llmQueryParser = null;
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

                // Step 1: Parse query and resolve person names from cluster labels
                ClusterDao clusterDao = db.clusterDao();
                FaceDao faceDao = db.faceDao();
                List<ClusterEntity> allClusters = clusterDao.getAllClusters();

                List<String> queryWords = tokenizeQuery(lowerQuery);
                Set<String> consumedNameWords = new HashSet<>();
                Set<Long> facePhotoIds = new HashSet<>();

                com.example.sensegallery.ai.LLMQueryParser.ParsedQuery llmParsedQuery = null;
                if (shouldUseLlmParser(queryWords)) {
                    llmParsedQuery = getOrCreateLlmQueryParser().parse(
                            lowerQuery,
                            getKnownClusterLabels(allClusters));
                }

                List<NameClusterMatch> nameMatches = new ArrayList<>();
                if (llmParsedQuery != null && !llmParsedQuery.getPersonNames().isEmpty()) {
                    nameMatches = findExactNameMatches(llmParsedQuery.getPersonNames(), allClusters);
                    for (String resolvedName : llmParsedQuery.getPersonNames()) {
                        consumedNameWords.addAll(tokenizeQuery(resolvedName.toLowerCase()));
                    }
                }

                if (nameMatches.isEmpty()) {
                    nameMatches = findNameMatches(queryWords, lowerQuery, allClusters);
                }

                boolean requireAllPeople = shouldRequireAllPeople(
                        lowerQuery,
                        queryWords,
                        llmParsedQuery,
                        nameMatches);

                if (!nameMatches.isEmpty()) {
                    if (nameMatches.size() == 1) {
                        NameClusterMatch single = nameMatches.get(0);
                        facePhotoIds = getPhotoIdsForCluster(faceDao, single.clusterId);
                        consumedNameWords.addAll(single.matchedQueryWords);
                    } else {
                        List<Long> clusterIds = new ArrayList<>();
                        for (NameClusterMatch match : nameMatches) {
                            clusterIds.add(match.clusterId);
                            consumedNameWords.addAll(match.matchedQueryWords);
                        }

                        if (!clusterIds.isEmpty()) {
                            facePhotoIds = new HashSet<>(
                                    faceDao.getPhotoIdsWithAllClusters(clusterIds, clusterIds.size()));
                        }
                    }
                }

                // Remaining words (excluding person names + stop words) become CLIP query.
                String clipQuery = "";
                if (llmParsedQuery != null) {
                    clipQuery = llmParsedQuery.getSceneDescription();
                }
                if (clipQuery == null || clipQuery.trim().isEmpty()) {
                    clipQuery = buildClipQuery(queryWords, consumedNameWords);
                }
                clipQuery = clipQuery.trim();

                boolean hasNameMatch = !facePhotoIds.isEmpty();
                boolean hasClipQuery = !clipQuery.isEmpty();

                Log.d(TAG, "Search: nameMatch=" + hasNameMatch + " (" + facePhotoIds.size()
                    + " photos), clipQuery=\"" + clipQuery + "\", strictTogether="
                    + requireAllPeople);

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
                            if (indexed.isEmpty()) {
                                Log.w(TAG, "No CLIP-indexed photos available yet");
                            }

                            Log.d(TAG, "CLIP evaluating " + indexed.size()
                                    + " indexed photos for query \"" + clipQuery + "\"");

                            for (PhotoEntity photo : indexed) {
                                if (photo.clipEmbedding == null) {
                                    continue;
                                }

                                float[] imgEmb = com.example.sensegallery.data.EmbeddingTypeConverter
                                        .toFloatArray(photo.clipEmbedding);
                                if (imgEmb == null || imgEmb.length != textEmbedding.length) {
                                    continue;
                                }

                                float score = com.example.sensegallery.ai.CLIPInferenceEngine
                                        .cosineSimilarity(textEmbedding, imgEmb);
                                if (Float.isNaN(score) || Float.isInfinite(score)) {
                                    continue;
                                }

                                clipResults.add(new ScoredPhoto(
                                        photo.id, photo.mediaStoreUri, photo.dateTaken, score));
                            }

                            clipResults.sort((a, b) -> Float.compare(b.score, a.score));
                            clipResults = filterClipResults(clipResults, clipQuery);

                            if (!clipResults.isEmpty()) {
                                Log.d(TAG, "CLIP kept " + clipResults.size()
                                        + " results, best score=" + clipResults.get(0).score);
                            } else {
                                Log.d(TAG, "CLIP found no relevant results for query \"" + clipQuery + "\"");
                            }
                        } else {
                            Log.w(TAG, "CLIP text embedding was null for query \"" + clipQuery + "\"");
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
                        if (requireAllPeople) {
                            Log.d(TAG, "Intersection empty for strict together query; returning empty results");
                        } else {
                            Log.d(TAG, "Intersection empty, falling back to name-only results");
                            for (Long photoId : facePhotoIds) {
                                PhotoEntity p = findPhotoById(db, photoId);
                                if (p != null) {
                                    finalResults.add(new PhotoItem(
                                            Uri.parse(p.mediaStoreUri), p.dateTaken));
                                }
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

    private synchronized com.example.sensegallery.ai.LLMQueryParser getOrCreateLlmQueryParser() {
        if (llmQueryParser == null) {
            llmQueryParser = new com.example.sensegallery.ai.LLMQueryParser(getApplication());
        }
        return llmQueryParser;
    }

    private boolean shouldUseLlmParser(List<String> queryWords) {
        if (queryWords == null || queryWords.isEmpty()) {
            return false;
        }
        if (queryWords.size() >= 3) {
            return true;
        }
        for (String word : queryWords) {
            if ("and".equals(word) || "with".equals(word) || "together".equals(word)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldRequireAllPeople(
            String lowerQuery,
            List<String> queryWords,
            com.example.sensegallery.ai.LLMQueryParser.ParsedQuery llmParsedQuery,
            List<NameClusterMatch> nameMatches) {
        if (nameMatches == null || nameMatches.size() < 2) {
            return false;
        }
        if (llmParsedQuery != null && llmParsedQuery.isRequireAllPeople()) {
            return true;
        }
        return hasHeuristicStrictTogetherIntent(lowerQuery, queryWords);
    }

    private boolean hasHeuristicStrictTogetherIntent(String lowerQuery, List<String> queryWords) {
        if (lowerQuery != null) {
            if (lowerQuery.contains("same photo") || lowerQuery.contains("same picture")) {
                return true;
            }
        }
        if (queryWords == null || queryWords.isEmpty()) {
            return false;
        }
        for (String word : queryWords) {
            if (STRICT_CONJUNCTION_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private List<String> getKnownClusterLabels(List<ClusterEntity> allClusters) {
        Set<String> names = new LinkedHashSet<>();
        for (ClusterEntity cluster : allClusters) {
            if (cluster.label == null) {
                continue;
            }
            String trimmed = cluster.label.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return new ArrayList<>(names);
    }

    private List<NameClusterMatch> findExactNameMatches(List<String> resolvedNames, List<ClusterEntity> allClusters) {
        List<NameClusterMatch> matches = new ArrayList<>();
        Set<Long> seenClusterIds = new HashSet<>();

        for (String resolvedName : resolvedNames) {
            if (resolvedName == null) {
                continue;
            }
            String lowerResolved = resolvedName.trim().toLowerCase();
            if (lowerResolved.isEmpty()) {
                continue;
            }

            for (ClusterEntity cluster : allClusters) {
                if (cluster.label == null) {
                    continue;
                }
                String lowerLabel = cluster.label.trim().toLowerCase();
                if (lowerLabel.isEmpty()) {
                    continue;
                }

                if (lowerLabel.equals(lowerResolved) && !seenClusterIds.contains(cluster.id)) {
                    Set<String> matchedWords = new HashSet<>(tokenizeQuery(lowerResolved));
                    matches.add(new NameClusterMatch(cluster.id, 200, matchedWords));
                    seenClusterIds.add(cluster.id);
                }
            }
        }

        matches.sort(Comparator.comparingInt((NameClusterMatch m) -> m.score).reversed());
        return matches;
    }

    private List<String> tokenizeQuery(String lowerQuery) {
        if (lowerQuery == null || lowerQuery.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> words = new ArrayList<>();
        for (String raw : lowerQuery.split("[^a-z0-9]+")) {
            String w = raw.trim();
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        return words;
    }

    private List<NameClusterMatch> findNameMatches(List<String> queryWords,
            String lowerQuery,
            List<ClusterEntity> allClusters) {
        List<NameClusterMatch> matches = new ArrayList<>();
        Set<Long> seenClusterIds = new HashSet<>();

        for (ClusterEntity cluster : allClusters) {
            if (cluster.label == null) {
                continue;
            }

            String label = cluster.label.trim().toLowerCase();
            if (label.isEmpty()) {
                continue;
            }

            Set<String> matchedQueryWords = new HashSet<>();
            int score = 0;

            if (lowerQuery.contains(label)) {
                score += 100;
                matchedQueryWords.addAll(tokenizeQuery(label));
            }

            List<String> labelWords = tokenizeQuery(label);
            for (String queryWord : queryWords) {
                if (queryWord.length() < 2) {
                    continue;
                }
                for (String labelWord : labelWords) {
                    if (labelWord.equals(queryWord)) {
                        score += 20;
                        matchedQueryWords.add(queryWord);
                    } else if (queryWord.length() >= 3 && labelWord.startsWith(queryWord)) {
                        score += 10;
                        matchedQueryWords.add(queryWord);
                    }
                }
            }

            if (score > 0 && !seenClusterIds.contains(cluster.id)) {
                matches.add(new NameClusterMatch(cluster.id, score, matchedQueryWords));
                seenClusterIds.add(cluster.id);
            }
        }

        matches.sort(Comparator.comparingInt((NameClusterMatch m) -> m.score).reversed());
        return matches;
    }

    private Set<Long> getPhotoIdsForCluster(FaceDao faceDao, long clusterId) {
        Set<Long> ids = new HashSet<>();
        List<PhotoEntity> photos = faceDao.getPhotosForCluster(clusterId);
        for (PhotoEntity photo : photos) {
            ids.add(photo.id);
        }
        return ids;
    }

    private String buildClipQuery(List<String> queryWords, Set<String> consumedNameWords) {
        StringBuilder sb = new StringBuilder();
        for (String word : queryWords) {
            if (consumedNameWords.contains(word)) {
                continue;
            }
            if (QUERY_STOP_WORDS.contains(word)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(word);
        }
        return sb.toString().trim();
    }

    /**
     * Smart relevance filtering: keeps only the truly relevant results by
     * detecting a significant score drop between consecutive results.
     * Also caps at MAX_CLIP_RESULTS.
     *
     * Example scores: [0.35, 0.33, 0.31, 0.30, 0.22, 0.21, 0.20...]
     * A drop from 0.30 to 0.22 (>25% gap relative to top score) triggers cutoff.
     */
    private List<ScoredPhoto> filterClipResults(List<ScoredPhoto> sorted, String clipQuery) {
        List<ScoredPhoto> filtered = filterByRelevanceDrop(sorted);
        if (filtered.isEmpty()) {
            return filtered;
        }

        if (!isExactItemQuery(clipQuery)) {
            return filtered;
        }

        float topScore = filtered.get(0).score;
        float scoreFloor = Math.max(
                MIN_CLIP_SCORE,
                Math.max(topScore * EXACT_QUERY_RELATIVE_SCORE_FLOOR, topScore - EXACT_QUERY_SCORE_MARGIN));

        List<ScoredPhoto> strict = new ArrayList<>();
        for (ScoredPhoto photo : filtered) {
            if (photo.score >= scoreFloor) {
                strict.add(photo);
            }
        }

        if (strict.isEmpty()) {
            strict.add(filtered.get(0));
        }

        Log.d(TAG, "Exact-query filter for \"" + clipQuery + "\" kept " + strict.size()
                + " of " + filtered.size() + " results (top="
                + String.format("%.3f", topScore) + ", floor="
                + String.format("%.3f", scoreFloor) + ")");
        return strict;
    }

    private List<ScoredPhoto> filterByRelevanceDrop(List<ScoredPhoto> sorted) {
        if (sorted.isEmpty())
            return sorted;

        int limit = Math.min(sorted.size(), MAX_CLIP_RESULTS);
        float topScore = sorted.get(0).score;
        if (topScore < LOW_CONFIDENCE_CLIP_SCORE) {
            Log.d(TAG, "Rejecting CLIP results because top score is too low: "
                    + String.format("%.3f", topScore));
            return new ArrayList<>();
        }

        int cutoff = limit;
        for (int i = 0; i < limit; i++) {
            float score = sorted.get(i).score;
            if (score < MIN_CLIP_SCORE) {
                cutoff = Math.max(1, i);
                break;
            }
        }

        float dropThreshold = Math.max(0.03f, topScore * 0.20f);
        for (int i = 1; i < cutoff; i++) {
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

        List<ScoredPhoto> filtered = sorted.subList(0, Math.max(1, cutoff));
        Log.d(TAG, "Relevance filter: kept " + filtered.size() + " of " + sorted.size()
                + " (top=" + String.format("%.3f", topScore)
                + ", last=" + String.format("%.3f", filtered.get(filtered.size() - 1).score) + ")");
        return new ArrayList<>(filtered);
    }

    private boolean isExactItemQuery(String clipQuery) {
        if (clipQuery == null) {
            return false;
        }

        String[] words = clipQuery.trim().split("\\s+");
        if (words.length != 1) {
            return false;
        }

        String word = words[0];
        return word.length() >= 3 && word.matches("[a-z0-9-]+");
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

    private static class NameClusterMatch {
        final long clusterId;
        final int score;
        final Set<String> matchedQueryWords;

        NameClusterMatch(long clusterId, int score, Set<String> matchedQueryWords) {
            this.clusterId = clusterId;
            this.score = score;
            this.matchedQueryWords = matchedQueryWords;
        }
    }
}
