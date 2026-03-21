# Sense Gallery Runtime Scenarios and Edge Cases

This document records expected runtime behavior for the most important operational scenarios in Sense Gallery.

## 1. Repeated Worker Triggers

### Scenario

The user taps the floating action button multiple times while background work is already running.

### Expected Behavior

- Face processing is enqueued as unique work with `ExistingWorkPolicy.KEEP`.
- A currently running face-processing job is not duplicated.
- CLIP indexing triggered from the same action is enqueued with `ExistingWorkPolicy.REPLACE`, allowing the semantic indexing pass to restart cleanly when the user explicitly requests it.

## 2. Automatic Semantic Indexing After Permission Grant

### Scenario

The app is launched for the first time and the user grants media permission.

### Expected Behavior

- The app immediately schedules CLIP indexing in the background.
- No manual face-processing action is required for semantic search to begin working.
- Search quality improves progressively as more photos finish indexing.

## 3. Failed CLIP Indexing Attempt

### Scenario

A photo fails during semantic indexing due to bitmap decode issues, model initialization problems, or temporary runtime failures.

### Expected Behavior

- The photo is not marked as successfully CLIP-processed unless an embedding is actually saved.
- Photos that still have `clipEmbedding = NULL` remain eligible for retry on later runs.
- A subsequent app launch or manual restart can recover the missing embedding.

## 4. Corrupt Cached ONNX Model Copy

### Scenario

The app previously copied a model file into internal storage, but the cached file is partial or invalid.

### Expected Behavior

- The CLIP inference layer validates the cached file before reuse.
- If ONNX session creation fails, the model cache is recopied from packaged assets.
- Semantic indexing can recover without requiring the user to clear app storage manually.

## 5. Photos Added After Initial Indexing

### Scenario

The user captures or downloads new photos after the first indexing pass has already completed.

### Expected Behavior

- New MediaStore entries are inserted into the `photos` table on the next indexing or face-processing sync.
- Existing photos are deduplicated by `mediaStoreUri`.
- Only photos without the required processed state or embedding are revisited.

## 6. Face Detection With No Valid Faces

### Scenario

A photo contains no faces, only tiny faces, or only blurry faces.

### Expected Behavior

- No `FaceEntity` rows are stored for that photo.
- The photo is still considered processed for the face pipeline.
- The image remains eligible for semantic indexing because face processing and CLIP indexing are independent workflows.

## 7. Group Photos With Multiple People

### Scenario

A single photo contains many people.

### Expected Behavior

- One `PhotoEntity` row represents the source image.
- Multiple `FaceEntity` rows can reference the same photo.
- The same image can appear in several cluster detail screens because multiple faces in that image may belong to different clusters.

## 8. Search Query With Person and Object Terms

### Scenario

The user searches with a mixed query such as `John cake`.

### Expected Behavior

- Cluster labels are matched first.
- Remaining unmatched terms are embedded and used for CLIP retrieval.
- The result set prefers photos that satisfy both the person filter and the semantic filter.
- If no intersection exists, the current implementation falls back to person-only results.

## 9. Single-Word Semantic Query Precision

### Scenario

The user searches for a short exact item term such as `cake`.

### Expected Behavior

- The ranking path applies a stricter score-band filter than it does for broader descriptive queries.
- The UI should prefer highly confident `cake` images and suppress broader related food or restaurant images when their score falls outside the top confidence band.

## 10. Semantic Search Before Index Completion

### Scenario

The user begins typing semantic queries before CLIP indexing has finished.

### Expected Behavior

- Person-name search can still work because cluster labels and semantic indexing are separate.
- Semantic-only results may be incomplete or empty until enough photos have stored CLIP embeddings.
- Once indexing progresses, the same query should return a broader and more stable result set.

## 11. Deleting a Previously Indexed Photo

### Scenario

The user deletes a photo outside the app after it has already been processed.

### Expected Behavior

- On the next synchronization pass, the photo row should no longer be reintroduced if the MediaStore entry is gone.
- Face rows linked to that photo are protected by foreign key cascade behavior and should be removed with the photo record.
- Cluster entities remain available for any remaining photos that still belong to the same person.

## 12. App Termination During Long-Running Work

### Scenario

The process is killed while face processing or CLIP indexing is in progress.

### Expected Behavior

- Completed database writes remain durable because embeddings and entities are persisted incrementally.
- The next scheduled or manual run continues from the persisted state rather than reprocessing the entire library unnecessarily.
- CLIP indexing can revisit incomplete rows because missing embeddings remain retryable.
