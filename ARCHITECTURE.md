# Sense Gallery Architecture

## Overview

Sense Gallery is an offline Android gallery application that combines two independent computer-vision pipelines:

- Face organization, built on RetinaFace and ArcFace.
- Semantic image retrieval, built on CLIP image and text embeddings.

Both pipelines persist their outputs in Room and are exposed through a single search experience in the main activity.

## Architectural Goals

- Keep all media processing on-device.
- Avoid duplicate processing across app launches.
- Persist intermediate outputs so long-running work can resume.
- Separate face clustering from semantic indexing so each pipeline can progress independently.
- Keep the UI responsive by running all inference in background workers or executors.

## High-Level Components

### UI Layer

- `MainActivity`
  Hosts navigation, the global search UI, permission handling, and worker scheduling.
- `GalleryViewModel`
  Loads gallery and cluster state, executes semantic search, and combines person-name and CLIP-based results.
- Fragment layer
  Displays photo grids, cluster lists, cluster details, and the photo viewer.

### Data Layer

- `AppDatabase`
  Room database singleton.
- `PhotoEntity`
  Stores MediaStore URI, timestamps, CLIP processing state, and CLIP embedding.
- `FaceEntity`
  Stores detected face geometry, ArcFace embedding, and optional cluster membership.
- `ClusterEntity`
  Stores user-visible cluster label and cover photo reference.

### Inference Layer

- `FaceDetectionHelper`
  RetinaFace ONNX wrapper.
- `FaceEmbeddingHelper`
  ArcFace ONNX wrapper.
- `FacePipelineProcessor`
  Coordinates bitmap loading, detection, filtering, alignment, and embedding persistence.
- `FaceClusterer`
  Performs hierarchical agglomerative clustering over ArcFace embeddings.
- `CLIPInferenceEngine`
  Wraps the CLIP image and text encoders and manages validated model caching.
- `CLIPTokenizer`
  Pure Java BPE tokenizer for CLIP text input.

### Background Workers

- `FaceProcessingWorker`
  Runs face detection and clustering.
- `CLIPIndexingWorker`
  Runs semantic image indexing.

## End-to-End Data Flow

## 1. Media Synchronization

Both background pipelines begin by reconciling MediaStore with the local Room database.

- MediaStore images are inserted into the `photos` table using `INSERT IGNORE`.
- The database becomes the source of truth for subsequent inference work.
- `mediaStoreUri` is unique, which prevents duplicate photo rows.

## 2. Face Processing Pipeline

The face-processing worker is triggered from the floating action button.

### Step 1: Load Unprocessed Photos

- Query `photos` where `isFaceProcessed = 0`.
- Skip work for photos that already have face rows.

### Step 2: Face Detection

- Load the photo bitmap from the MediaStore URI.
- Run RetinaFace (`det_10g.onnx`).
- Decode multi-scale outputs and run custom non-maximum suppression.

### Step 3: Quality Filtering

- Reject faces below the minimum face size threshold.
- Reject blurry detections using a Laplacian variance heuristic.

### Step 4: Face Alignment

- Use five facial landmarks when available.
- Compute a similarity transform into the ArcFace 112x112 canonical template.
- Fall back to crop-and-resize when landmarks are unavailable.

### Step 5: Face Embedding

- Run ArcFace (`arcface_w600k_r50.onnx`).
- Produce a 512-dimensional embedding.
- Convert the float array into a BLOB for Room storage.

### Step 6: Persist Face Rows

- Insert a `FaceEntity` per accepted face.
- Mark the source photo as face-processed after the photo-level pass completes.

## 3. Face Clustering Pipeline

Clustering runs after face extraction completes.

### Algorithm

- Hierarchical agglomerative clustering
- Average linkage
- Euclidean distance over ArcFace embeddings
- Merge threshold: `1.1f`

### Output

- New clusters are created as needed.
- Faces are assigned to cluster IDs.
- New clusters receive generated labels such as `Person 1`.
- Existing clusters can be renamed by the user.

## 4. Semantic Indexing Pipeline

Semantic indexing is started automatically after media permission is granted and can also be restarted manually from the floating action button.

### Step 1: Select Photos for Indexing

The worker indexes photos that either:

- are not marked as CLIP-processed, or
- still have no stored CLIP embedding

This makes the indexing path resilient to earlier failed runs.

### Step 2: CLIP Model Initialization

- Load `image_encoder.onnx`
- Load `text_encoder.onnx`
- Load tokenizer assets
- Copy model files from assets into app storage through a validated temp-file flow
- Recopy automatically if a cached file is corrupt or incomplete

### Step 3: Image Embedding

- Load a photo bitmap through Glide.
- Resize to 224x224.
- Normalize using CLIP image statistics.
- Run the CLIP image encoder.
- L2-normalize the output embedding.
- Store the resulting BLOB on `PhotoEntity.clipEmbedding`.

Photos are marked as CLIP-processed only after a valid embedding is successfully stored.

## Search Architecture

## Query Parsing

The search bar supports mixed queries that may include:

- a person reference, such as `John`
- a visual description, such as `cake`
- a combined query, such as `John cake`

`GalleryViewModel` splits the query into words and compares them against cluster labels first. Terms matched to cluster names are treated as person filters. Remaining terms become the CLIP text query.

## CLIP Retrieval

For semantic search:

1. Encode the CLIP text query.
2. Load all stored image embeddings.
3. Compute cosine similarity between the text embedding and each image embedding.
4. Sort results by descending score.
5. Apply relevance filtering.

## Precision Filtering

The ranking system uses two layers of filtering:

- General relevance filtering
  Rejects low-confidence result sets and trims after significant score drops.
- Exact-item precision filtering
  For single-word exact queries such as `cake`, only the strongest score band is kept so broad related scenes are less likely to appear.

## Combined Search Results

- Person-only query
  Return all photos belonging to the matched clusters.
- Semantic-only query
  Return CLIP-ranked photos.
- Person plus semantic query
  Return the intersection of cluster photos and CLIP matches when possible.

If the intersection is empty, the implementation currently falls back to person-only results rather than returning an empty screen.

## Database Model

## `photos`

Purpose:
Stores one row per MediaStore image.

Important fields:

- `id`
- `mediaStoreUri`
- `dateTaken`
- `isFaceProcessed`
- `isClipProcessed`
- `clipEmbedding`

## `faces`

Purpose:
Stores one row per detected face.

Important fields:

- `id`
- `photoId`
- `boxLeft`
- `boxTop`
- `boxRight`
- `boxBottom`
- `faceEmbedding`
- `clusterId`

Relationships:

- `photoId -> photos.id` with `ON DELETE CASCADE`
- `clusterId -> clusters.id` with `ON DELETE SET NULL`

## `clusters`

Purpose:
Stores user-visible face groups.

Important fields:

- `id`
- `label`
- `coverPhotoId`

## Concurrency and Work Scheduling

### Face Processing

- Enqueued as unique work with `ExistingWorkPolicy.KEEP`
- Prevents overlapping face-processing runs
- Uses a `CountDownLatch` inside the worker so clustering starts only after extraction completes

### CLIP Indexing

- Automatically started after permission grant with unique work
- Manual restart from the floating action button uses `ExistingWorkPolicy.REPLACE`
- Allows the user to force a fresh semantic indexing run

### UI Search Execution

- Search queries run on a dedicated single-thread executor in `GalleryViewModel`
- Text changes are debounced in `MainActivity`
- This avoids blocking the main thread during semantic scoring

## Reliability Considerations

- Model files are copied through a temporary file and validated by size before reuse.
- Corrupt cached model copies trigger an automatic recopy.
- CLIP indexing retries rows that still have no image embedding.
- Face and semantic processing are persisted independently so partial progress is not lost.

## Known Limitations

- Semantic search quality is bounded by the CLIP model and local ranking heuristics.
- Exact visual object discrimination can still vary for cluttered scenes.
- Large libraries may require noticeable time for initial indexing.
- Full rebuild verification depends on local Gradle availability in the development environment.
