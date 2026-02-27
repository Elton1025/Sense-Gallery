# Sense Gallery - Edge Cases & Testing

This document logs specific edge cases, user interruptions, and system limits explored during the development of Sense Gallery, providing a behavioral expectation for each scenario.

## 1. Concurrent Execution Limits

### Scenario: Pressing the Scan Button Multiple Times
**Trigger:** User taps the "Scan Face" floating action button repeatedly while a long-running detection phase is actively processing images in the background.
**Expected Behavior:** 
- The Android `WorkManager` enqueue structure leverages `ExistingWorkPolicy.KEEP`.
- The current running instance continues processing photos without interruption.
- The secondary trigger is safely ignored or queued depending on API level, guaranteeing that the database does not receive duplicated entries or run overlapping threads that crash the active neural network context.

## 2. Dynamic Image Additions

### Scenario: Adding Photos During an Active Scan
**Trigger:** User takes a new photo or downloads images from the internet exactly while the background AI process is in the middle of scanning an older block of photos.
**Expected Behavior:** 
- The new photo will **not** be scanned during the current active WorkManager task. 
- The `processAllPhotos` pipeline isolates the `MediaStore` synchronisation exactly once per execution cycle.
- The new image will sit untouched in the phone's gallery. The next time the user manually or automatically triggers the background worker, the `MediaStore` sync captures the new image. Because of internal database verification, the worker flies past the previously detected photos and rapidly isolates only the single newest image.

## 3. Interruptions & Background Process Termination

### Scenario: Swiping App Away / OS Killing the Scan
**Trigger:** User forcibly closes the app from the Recents menu, or Android's extreme battery management kills the process while the worker is precisely halfway through an image queue (e.g., 500 out of 1000 images completed).
**Expected Behavior:**
- **No Data Loss:** `FaceEntity` embeddings are written directly into persistent Room storage synchronously image-by-image. The first 500 face structures are safely preserved indefinitely.
- **Clustering Halt:** The `FaceClusterer.clusterFaces()` calculation is systematically blocked by a `CountDownLatch` and will not trigger if the preceding detection queue is terminated abruptly.
- **Resumption Sync:** By re-opening the application, WorkManager (which logged the incomplete execution) will resume. The detector flies past the 500 existing photos, finishes the remaining 500, and mathematically calculates the identities for all 1000 items as one complete analytical block.

## 4. One-To-Many Photo Mapping

### Scenario: Group Photos with Multiple Individuals
**Trigger:** The system detects an image featuring 15 different individuals (e.g., a group photo of a cricket team).
**Expected Behavior:**
- The system naturally supports one-to-many relationships logically.
- The `PhotoEntity` table holds exactly 1 reference to that image, preventing storage bloat.
- RetinaFace physically generates 15 independent `FaceEntity` structures tied to the specific X and Y coordinates inside that single photo frame.
- Consequently, navigating to "Person A" will show the group photo. Navigating independently to "Person B" will also load the identical group photo efficiently via a unified SQL JOIN execution.

## 5. Destructive File Triggers (Deletion)

### Scenario: Deleting a Processed Image
**Trigger:** User deletes a photo using an external file manager or gallery application after Sense Gallery has already spent battery mapping it to a human identity cluster.
**Expected Behavior:**
- Upon the next execution cycle, the `MediaStore` sync realizes the URI is missing.
- The internal `PhotoEntity` cache deletes the orphaned row.
- `ON DELETE CASCADE` automatically activates securely inside the Room SQLite schema, permanently scrubbing all associated facial `FaceEntity` references. 
- The master `ClusterEntity` (Person Label) is unaffected, cleanly preserving the individual's remaining historical database of pictures.
