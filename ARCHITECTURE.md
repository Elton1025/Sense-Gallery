# Sense Gallery Architecture & Implementation Details

Sense Gallery is a completely offline-first, privacy-preserving Android photo gallery application built with Java, Room Database, and ONNX Runtime. It automatically scans your photos, extracts high-dimensional face embeddings, and groups people using advanced hierarchical agglomerative clustering algorithms.

## Flow Pipeline
1. **MediaStore Syncing:** The app safely scans the Android `MediaStore` to acquire Uris for images and caches them in the `Room` Database inside the `photos` table.
2. **FaceProcessingWorker (Background Executor):** Handled by Android's `WorkManager`, this worker coordinates AI processing in the background. It utilizes an asynchronous design combined with a `CountDownLatch` so that face extraction is strictly completed before moving to face grouping.
3. **Face Detection (RetinaFace ONNX):** 
   - Uses the highly accurate `det_10g.onnx` RetinaFace model via `ONNXRuntime`. 
   - Extensively handles multiple output arrays consisting of score tensors, boundary boxes, and keypoints matched manually down 3 specific strides (8, 16, 32 pixels).
   - Generates confidence scores and runs custom `Non-Maximum Suppression (NMS)` combined with Image blur validation calculated via `Laplacian Variance`.
4. **Face Alignment (5-Point Similarity Transform):** 
   - A robust numerical Least-Squares fitting technique computes translation and scaling parameters to align the eye, nose, and mouth keypoints flawlessly to the ArcFace 112x112 default template.
5. **Face Embedding (ArcFace ONNX):** 
   - Using the `arcface_w600k_r50.onnx` model, the aligned 112x112 image is mapped to a highly descriptive 512-dimension vector output. 
6. **Face Clustering (HAC / Average Linkage):** 
   - Groups vectors utilizing `Hierarchical Agglomerative Clustering (HAC)` configured optimally with Average Linkage parameters.
   - Evaluates L2 distance metrics and groups faces belonging to the same entity using an experimentally-tuned similarity threshold of `1.1f`.
   - Populates the `clusters` Room table, assigning generic identities to recognized individuals (e.g., "Person 1").
7. **Search & Renaming System:** 
   - An intuitive `SearchBar` driven UI lets you search via person name filter, which observes changes in the view model.
   - Individual clusters can be renamed seamlessly via a popup `AlertDialog` stored natively as strings in the Room persistent data source.

## Database Schema (Room)
The database operates primarily on 3 core tables leveraging primary key relationships:
- **`PhotoEntity`**: Contains IDs referencing MediaStore URIs.
- **`FaceEntity`**: Each row stores a 512-dimensional vector `byte[]` BLOB mapped to its source `PhotoEntity` and parent `ClusterEntity`.
- **`ClusterEntity`**: A virtual grouping instance representing one unique individual, storing their `label` (name) alongside their reference `coverPhotoId`.
