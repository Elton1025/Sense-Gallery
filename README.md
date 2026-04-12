# Sense Gallery

Sense Gallery is an offline Android photo gallery focused on two local-first capabilities:

1. Face clustering for organizing photos by person.
2. Semantic image search for retrieving photos by visual content such as `cake`, `sunset`, or `beach`.

The application is built in Java on top of Room, WorkManager, Material components, ONNX Runtime, and an on-device llmedge parser for natural-language query decomposition. All inference runs on-device. No photo content, embeddings, or search queries are sent to a server.

## Core Capabilities

- Face detection with RetinaFace ONNX.
- Face embedding extraction with ArcFace ONNX.
- Hierarchical agglomerative clustering for grouping faces into identity clusters.
- CLIP-based image indexing and text-to-image semantic search.
- Geotagged Places view with map pins for photos containing GPS metadata.
- Person search through user-editable cluster labels.
- Automatic semantic indexing after media permission is granted.
- Manual reprocessing through the floating action button for face processing and CLIP re-indexing.

## Current Search Behavior

The search surface supports two retrieval modes in the same UI:

- Person search: matches cluster labels such as `John`, `Mom`, or `Virat`.
- Semantic search: matches image content using CLIP embeddings, for example `cake`, `dog`, or `sunset at beach`.

For short exact item queries such as `cake`, the semantic ranking applies a stricter precision filter so broad related images such as plates, restaurants, or generic food scenes are less likely to appear.

For multi-person conjunction queries such as `virat and rohit`, the search enforces strict co-occurrence. Results must contain all requested people in the same photo. If a strict conjunction query also includes scene text and no CLIP scene match is found, the result stays empty instead of falling back to broader name-only results.

## Technology Stack

- Language: Java 17
- Minimum SDK: 30
- Target SDK: 36
- Database: Room
- Background execution: WorkManager
- Image loading: Glide
- Navigation/UI: Fragments, Navigation Component, Material 3 search components
- Map UI: OpenStreetMap (osmdroid)
- Inference runtime: ONNX Runtime Android, llmedge (SmolLM parser)

## Models Included

The project currently contains the required model and tokenizer assets under `app/src/main/assets/`:

- `det_10g.onnx`
- `arcface_w600k_r50.onnx`
- `image_encoder.onnx`
- `text_encoder.onnx`
- `SmolLM2-135M-Instruct.Q4_K_M.gguf`
- `tokenizer.json`
- `vocab.json`
- `merges.txt`

## How the App Works

### Face Processing

The face pipeline is triggered manually from the floating action button:

1. Sync photos from MediaStore into Room.
2. Load unprocessed photos.
3. Detect faces with RetinaFace.
4. Reject tiny or blurry faces.
5. Align faces with a 5-point similarity transform.
6. Generate 512-dimensional ArcFace embeddings.
7. Persist face records in Room.
8. Cluster faces with hierarchical agglomerative clustering.

Clusters are stored with generated labels such as `Person 1` until renamed by the user.

### Semantic Indexing

Semantic indexing is triggered automatically after photo permission is granted and can also be restarted from the floating action button.

1. Sync photos from MediaStore into Room.
2. Find photos that do not yet have a CLIP embedding.
3. Load each image as a bitmap.
4. Generate an image embedding with the CLIP image encoder.
5. Persist the embedding to the `photos` table.

The indexing worker retries photos that still have no stored CLIP embedding, which allows recovery from earlier failed indexing runs.

### Search Composition

The main search flow combines both local cluster filtering and CLIP similarity:

- If the query matches cluster labels, cluster photos are returned.
- If the query contains non-person terms, the remaining text is embedded with the CLIP text encoder and matched against indexed photos.
- If both person names and scene text are present, the final result is the intersection of person matches and CLIP scene matches.
- For strict conjunction intent (`and`, `together`, `same photo`), all requested people must co-occur in the same photo, and empty intersection remains empty.

## Project Structure

- `app/src/main/java/com/example/sensegallery/MainActivity.java`
  Main host activity, permission handling, search wiring, and worker scheduling.
- `app/src/main/java/com/example/sensegallery/gallery/GalleryViewModel.java`
  Search orchestration, cluster loading, and semantic ranking logic.
- `app/src/main/java/com/example/sensegallery/workers/FaceProcessingWorker.java`
  Background face processing entry point.
- `app/src/main/java/com/example/sensegallery/workers/CLIPIndexingWorker.java`
  Background semantic indexing entry point.
- `app/src/main/java/com/example/sensegallery/ai/`
  ONNX model wrappers and image processing pipeline classes.
- `app/src/main/java/com/example/sensegallery/data/`
  Room entities, DAOs, converters, and database setup.

## Development Setup

### Prerequisites

- Android Studio with a recent Android SDK installation
- Java 17
- An Android device or emulator with image media available

### Run

1. Open the project in Android Studio.
2. Sync Gradle.
3. Install and run the debug build on a device.
4. Grant the requested media permission.
5. Optionally grant location metadata access to enable fuller Places map coverage.
6. Wait for automatic CLIP indexing to start.
7. Use the floating action button when you want to run face detection and clustering.

## Operational Notes

- The app is designed for on-device execution only.
- CLIP models are loaded directly from bundled assets into ONNX Runtime sessions, and legacy cached copies are cleaned up on startup.
- Room stores image-level CLIP embeddings on `PhotoEntity` and face-level ArcFace embeddings on `FaceEntity`.
- Room also stores optional photo latitude and longitude from EXIF metadata for the Places tab.
- Map and image disk caches are automatically bounded to avoid unbounded storage growth over time.
- WorkManager unique work policies are used to avoid overlapping runs.

## Related Documentation

- `ARCHITECTURE.md`
  Detailed pipeline and system design notes.
- `test_edge_cases.md`
  Expected behavior for important runtime and data edge cases.
