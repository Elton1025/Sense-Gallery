# Sense Gallery

Sense Gallery is an offline Android photo gallery centered on local-first photo intelligence:

1. Face detection and clustering for organizing photos by person.
2. Semantic image search for retrieving photos by visual content.

All core inference runs on device. The app does not upload photos, embeddings, or search queries to any remote server.

## Core Capabilities

- RetinaFace ONNX face detection.
- ArcFace ONNX face embeddings (512-dimensional).
- Hierarchical agglomerative clustering for identity grouping.
- User cluster curation for false detections and grouping mistakes:
  - rename cluster
  - merge clusters
  - move selected photos to another cluster
  - split selected photos into a new person
  - remove selected photos from a cluster
- CLIP-based image indexing and text-to-image semantic search.
- Geotag extraction from EXIF metadata and Places map view.
- Photo viewer tools: share, crop presets, adaptive JPEG compression, metadata panel, and delete.
- Automatic semantic indexing after media permission grant.
- Manual reprocessing via floating action button (face pipeline + CLIP re-indexing).

## Current Search Behavior

Search combines person retrieval and semantic retrieval in one surface:

- Person search matches cluster labels such as `John`, `Mom`, or `Virat`.
- Semantic search matches image content with CLIP text/image embeddings.

For short exact single-term scene queries (for example `cake`), ranking applies stricter score-band filtering to reduce broad related results.

For strict multi-person conjunction queries (for example `virat and rohit`, `same photo`, `together`), all requested people must co-occur in the same photo. If strict person conjunction and scene text are both present, empty intersection remains empty.

For non-strict mixed queries where name+scene intersection is empty, the app may fall back to name-only results.

## Technology Stack

- Language: Java 17
- Minimum SDK: 30
- Target SDK: 36
- Database: Room
- Background execution: WorkManager
- Image loading: Glide
- Navigation/UI: Fragments, Navigation Component, Material 3
- Map UI: OpenStreetMap (osmdroid)
- Inference runtime: ONNX Runtime Android

## Models and Assets Included

Assets under `app/src/main/assets/`:

- `det_10g.onnx`
- `arcface_w600k_r50.onnx`
- `image_encoder.onnx`
- `text_encoder.onnx`
- `tokenizer.json`
- `vocab.json`
- `merges.txt`

Note: this build does not package a SmolLM `.gguf` model asset.

## How the App Works

### Face Processing (Manual Trigger)

1. Sync MediaStore photos into Room.
2. Load photos where face processing is incomplete.
3. Detect faces with RetinaFace.
4. Reject tiny or blurry faces.
5. Align faces using a 5-point similarity transform.
6. Generate ArcFace embeddings.
7. Store face rows in Room.
8. Run HAC clustering and assign/update cluster IDs.

### Semantic Indexing (Auto + Manual Trigger)

1. Sync MediaStore photos into Room.
2. Select photos with missing CLIP embedding.
3. Load bitmap using Glide.
4. Generate CLIP image embedding.
5. Persist embedding to `photos.clipEmbedding` and mark clip-processed.
6. Extract EXIF latitude/longitude (when available/allowed) and persist to photo row.

### Cluster Curation

From cluster detail, users can correct clustering outcomes:

- rename current cluster
- merge into another cluster
- enter selection mode and move selected photos to another cluster
- split selected photos into a new person cluster
- remove selected photos from the cluster (set unclustered)

Cluster cover photos are refreshed automatically, and empty clusters are removed.

### Photo Editing Utilities

In photo viewer:

- crop to square, portrait, story, or landscape presets
- adaptive JPEG compression with level control, quality/max-side tuning, and target-size search
- save edited copies to MediaStore (`Pictures/SenseGallery` on Android 10+)

## Project Structure

- `app/src/main/java/com/example/sensegallery/MainActivity.java`
  Permission handling, global search, worker scheduling, and navigation shell.
- `app/src/main/java/com/example/sensegallery/gallery/GalleryViewModel.java`
  Search composition, cluster loading, and ranking/filtering logic.
- `app/src/main/java/com/example/sensegallery/workers/FaceProcessingWorker.java`
  Face extraction + clustering background entry point.
- `app/src/main/java/com/example/sensegallery/workers/CLIPIndexingWorker.java`
  CLIP indexing and geotag extraction background entry point.
- `app/src/main/java/com/example/sensegallery/ui/ClusterDetailFragment.java`
  Cluster rename/merge/move/split/remove workflows.
- `app/src/main/java/com/example/sensegallery/ui/PhotoViewerFragment.java`
  Full-screen viewer with share/crop/compress/delete/info actions.
- `app/src/main/java/com/example/sensegallery/ui/MapFragment.java`
  Places tab rendering geotagged photos on OSM map tiles.

## Development Setup

### Prerequisites

- Android Studio with a recent Android SDK installation
- Java 17
- Android device or emulator with image media

### Run

1. Open the project in Android Studio.
2. Sync Gradle.
3. Install and run debug build.
4. Grant image media permission.
5. Optionally grant `ACCESS_MEDIA_LOCATION` for fuller Places coverage.
6. Wait for automatic CLIP indexing.
7. Use the floating action button to run face processing and re-indexing on demand.

## Operational Notes

- Execution is on-device only.
- CLIP ONNX sessions are created directly from asset bytes.
- Startup cleanup bounds disk growth by trimming map/glide caches and removing obsolete model cache copies.
- Room stores CLIP embeddings at photo level and ArcFace embeddings at face level.
- Room also stores optional EXIF latitude/longitude for the Places tab.
- WorkManager unique work names prevent overlapping background runs.

## Related Documentation

- `ARCHITECTURE.md` for system architecture and pipeline details.
- `test_edge_cases.md` for expected runtime behavior under edge conditions.
