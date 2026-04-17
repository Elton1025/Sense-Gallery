# Sense Gallery Architecture

## Overview

Sense Gallery is an offline Android gallery that combines local face organization and local semantic retrieval:

- Face pipeline: RetinaFace detection, ArcFace embedding, HAC clustering.
- Semantic pipeline: CLIP image indexing, CLIP text retrieval, relevance filtering.

Both pipelines persist state in Room and are orchestrated through WorkManager and ViewModel executors.

## Architectural Goals

- Keep inference and search fully on device.
- Persist intermediate state so work can resume after interruptions.
- Avoid duplicate background runs.
- Keep UI reactive while inference and ranking run off the main thread.
- Allow user correction of clustering mistakes.

## High-Level Components

### UI and Interaction Layer

- `MainActivity`
  - runtime permission handling
  - search UI (Material SearchBar/SearchView)
  - worker scheduling
  - navigation host and chrome visibility control
- `GalleryViewModel`
  - cluster loading
  - mixed person+scene search orchestration
  - CLIP ranking and relevance filtering
- `ClusterDetailFragment`
  - cluster rename, merge, selective move/split/remove curation
- `PhotoViewerFragment`
  - viewer pager, metadata panel, share/crop/compress/delete actions
- `MapFragment`
  - geotagged photo pin rendering with osmdroid

### Data Layer (Room)

- `PhotoEntity`
  - MediaStore URI, capture time, processing flags, CLIP embedding, optional lat/lon
- `FaceEntity`
  - face box, ArcFace embedding, optional cluster ID
- `ClusterEntity`
  - label and cover photo ID
- DAOs
  - optimized queries for cluster photos, strict multi-cluster co-occurrence, and geotagged projections

### Inference Layer

- `FaceDetectionHelper`
  - RetinaFace ONNX inference and landmark-aware decoding
- `FaceEmbeddingHelper`
  - ArcFace ONNX embedding with 2-pass TTA (original + mirrored)
- `FacePipelineProcessor`
  - bitmap loading, quality filtering, alignment, and face persistence
- `FaceClusterer`
  - HAC average-linkage clustering with distance matrix updates
- `CLIPInferenceEngine`
  - CLIP image/text sessions from asset bytes
  - CLIP tokenizer integration

### Background Layer

- `FaceProcessingWorker`
  - executes face extraction then clustering
- `CLIPIndexingWorker`
  - syncs MediaStore, computes image embeddings, updates EXIF geotags

## End-to-End Flow

## 1) Media Synchronization

Both pipelines reconcile MediaStore with Room:

- `INSERT IGNORE` into `photos` prevents duplicate rows.
- `mediaStoreUri` uniqueness acts as deduplication key.

## 2) Face Pipeline

Triggered manually via floating action button.

1. Load photos with `isFaceProcessed = 0`.
2. Decode bitmap with bounded dimensions (up to 2048 side).
3. Detect faces with RetinaFace.
4. Reject tiny and blurry detections.
5. Align with 5-point similarity transform to 112x112 template.
6. Extract ArcFace embeddings (512-d, L2-normalized).
7. Insert face rows and mark photo face-processed.

## 3) Clustering Pipeline

Runs after face extraction in the same worker.

- Algorithm: HAC with average linkage.
- Distance: Euclidean/L2 on embeddings.
- Merge threshold: `1.1f`.
- Inputs: existing clusters + newly unclustered faces.
- Outputs:
  - create new `Person N` clusters when needed
  - update face `clusterId`
  - preserve existing clusters unless merged by HAC

## 4) Semantic Indexing Pipeline

Auto-started after media permission; manually restartable.

1. Sync MediaStore into Room.
2. Select rows where `isClipProcessed = 0` or embedding is missing.
3. Load image bitmap (224x224 target via Glide).
4. Encode with CLIP image encoder.
5. Persist embedding blob and mark clip-processed.
6. Read EXIF GPS (with `ACCESS_MEDIA_LOCATION` when available) and store lat/lon.

## 5) Search Pipeline

`GalleryViewModel.semanticSearch(query)` composes two branches:

- Person branch
  - match cluster labels by exact/word/prefix scoring
  - strict co-occurrence query support via `FaceDao.getPhotoIdsWithAllClusters(...)`
- Scene branch
  - tokenize residual query text (excluding stop words and consumed name tokens)
  - CLIP text embedding + cosine similarity over indexed photos
  - score filtering using confidence floors and relevance-drop cutoff
  - stricter banding for exact single-word item queries

Result fusion:

- Name-only: return person matches.
- Scene-only: return CLIP ranking.
- Name+scene: return intersection.
- If name+scene intersection is empty:
  - strict conjunction intent (`and`, `together`, `same photo`) -> keep empty
  - non-strict mixed query -> fallback to name-only photos

## Query Understanding Notes

- The code includes an optional `LLMQueryParser` path.
- Current shipped assets do not include a SmolLM `.gguf` model, so parsing effectively relies on deterministic token/label heuristics.

## Cluster Curation Architecture

Cluster correction happens in `ClusterDetailFragment` within Room transactions:

- Merge current cluster into another (`moveAllFacesToCluster`).
- Move selected photo faces to another cluster (`moveClusterFacesInPhotos`).
- Split selected photo faces into new cluster.
- Remove selected photo faces from current cluster (set `clusterId = null`).
- Refresh cover-photo pointer after edits and delete empty clusters.

## Photo Editing Utilities

`PhotoViewerFragment` provides non-destructive editing:

- crop presets (square, portrait, story, landscape)
- adaptive JPEG compression
  - compression-level slider
  - target byte estimation
  - binary search over JPEG quality
  - optional iterative downscale loop
- save edited copy to MediaStore (and refresh visible lists)

## Geotag and Places Architecture

- EXIF coordinates are extracted in CLIP indexing worker.
- Map UI queries `PhotoDao.getPhotosWithLocation()`.
- Map markers open photo viewer directly for selected URI.
- osmdroid cache paths and max/trim thresholds are configured per app cache directory.

## Concurrency and Scheduling

- Face processing unique work: `KEEP`.
- CLIP indexing unique work:
  - `KEEP` on startup permission flow
  - `REPLACE` on manual re-index trigger
- Search:
  - dedicated `searchExecutor`
  - text-change debounce in `MainActivity`

## Storage Maintenance

On startup, `StorageMaintenance.runStartupCleanup(...)`:

- trims osmdroid and Glide disk caches to bounded sizes
- purges legacy CLIP model cache directory
- removes stale temp files

## Reliability Characteristics

- Independent persistence for face and CLIP pipelines.
- CLIP retry behavior for rows with missing embeddings.
- WorkManager unique names avoid overlapping worker storms.
- UI remains responsive because heavy work stays off the main thread.

## Known Limits

- Retrieval quality depends on model generalization and score heuristics.
- Initial indexing time increases with library size.
- HAC threshold is static and may need per-device/per-collection tuning.
