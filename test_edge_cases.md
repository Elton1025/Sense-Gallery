# Sense Gallery Runtime Scenarios and Edge Cases

This document captures expected behavior for important runtime scenarios in the current implementation.

## 1. Repeated Worker Triggers

### Scenario

User taps the floating action button multiple times.

### Expected Behavior

- Face pipeline uses unique work with `ExistingWorkPolicy.KEEP`, so overlapping face-processing jobs are not duplicated.
- CLIP indexing from the same action uses `ExistingWorkPolicy.REPLACE`, allowing explicit user-triggered restart.

## 2. Automatic CLIP Indexing After Permission Grant

### Scenario

First launch, user grants media permission.

### Expected Behavior

- App immediately schedules CLIP indexing.
- Face processing does not need to run first for semantic search indexing to begin.

## 3. CLIP Indexing Failure on Individual Photos

### Scenario

Decode/inference fails for one or more photos.

### Expected Behavior

- `isClipProcessed` is set only after a valid embedding is saved.
- Rows with `clipEmbedding = NULL` remain eligible for later retry.

## 4. Missing ACCESS_MEDIA_LOCATION Permission

### Scenario

User grants image read permission but not exact media location.

### Expected Behavior

- CLIP indexing continues normally.
- EXIF GPS extraction may be skipped or partially unavailable.
- Places tab works for photos where coordinates are readable; others remain without map pins.

## 5. Photos Added After Initial Indexing

### Scenario

New camera/download images appear after earlier indexing completed.

### Expected Behavior

- MediaStore sync inserts new rows with `INSERT IGNORE` semantics.
- Existing rows are deduplicated by `mediaStoreUri`.
- Only unprocessed/missing-embedding rows are re-indexed.

## 6. Face Detection With No Valid Faces

### Scenario

Photo has no faces, tiny faces, or blurry detections.

### Expected Behavior

- No face rows are inserted.
- Photo is still marked face-processed for that pass.
- Photo remains independently eligible for CLIP indexing.

## 7. Group Photos With Multiple People

### Scenario

One image contains many people.

### Expected Behavior

- Single `PhotoEntity` row.
- Multiple `FaceEntity` rows linked by `photoId`.
- Same photo can appear in multiple cluster views if faces belong to different clusters.

## 8. Strict Multi-Person Query

### Scenario

Query like `virat and rohit` or `same photo` intent.

### Expected Behavior

- Search requires co-occurrence using `getPhotoIdsWithAllClusters(...)`.
- Only photos containing all requested people are returned.

## 9. Mixed Strict Query With No Scene Intersection

### Scenario

Strict person conjunction plus scene text (for example `virat and rohit beach`) where CLIP scene intersection is empty.

### Expected Behavior

- Result stays empty.
- No fallback to broader name-only results.

## 10. Mixed Non-Strict Query With Empty Intersection

### Scenario

Non-strict name+scene query where intersection is empty.

### Expected Behavior

- App may fallback to name-only results.

## 11. Single-Word Semantic Precision

### Scenario

Query like `cake`.

### Expected Behavior

- Additional exact-term score-band filtering is applied after general relevance filtering.
- Weaker broad matches are trimmed more aggressively than in descriptive multi-word queries.

## 12. Cluster Curation: Move or Split Selected Photos

### Scenario

User selects photos in cluster detail and applies move/split/remove actions.

### Expected Behavior

- Operations run inside Room transactions.
- Affected face rows are reassigned or unclustered.
- Cluster cover photos refresh automatically.
- Empty clusters are deleted.

## 13. Cluster Merge

### Scenario

User merges current cluster into another.

### Expected Behavior

- All source cluster faces move to target cluster.
- Source cluster is deleted when empty.
- UI navigates to target cluster view.

## 14. Compression Save Flow

### Scenario

User compresses a photo from viewer.

### Expected Behavior

- Adaptive JPEG compression computes target bytes from compression level.
- Quality is selected via binary search, with optional downscale retries if needed.
- Compressed copy is saved to MediaStore and appears after refresh.

## 15. Crop Save Flow

### Scenario

User applies one of the crop presets.

### Expected Behavior

- Center crop with selected aspect ratio is encoded to JPEG.
- Saved as a new copy in MediaStore.
- Gallery/cluster list refreshes to surface new image.

## 16. App Kill During Processing

### Scenario

Process dies during face or CLIP worker execution.

### Expected Behavior

- Persisted writes remain durable.
- Later runs continue from DB state rather than full reprocessing.
- CLIP indexing can recover missing rows via retryable selection criteria.

## 17. Optional LLM Parser Asset Absent

### Scenario

SmolLM model asset is not packaged.

### Expected Behavior

- Optional parser path fails closed.
- Search still functions via deterministic token/label heuristic path.
