# Sense Gallery 📸

A privacy-preserving, completely offline-first Android photo gallery app built with Java and modern Machine Learning technologies for grouping photos utilizing advanced unsupervised facial clustering!

## 🚀 Key Features

*   **Offline First**: All image processing and clustering happens 100% on-device! No cloud dependencies or privacy leaks.
*   **Intelligent Facial Clustering**: Groups all of your photos based around the uniquely identified individuals depicted in them.
*   **Face Naming & Searching**: Name the individuals recognized and search for them instantly utilizing the comprehensive gallery search bar.
*   **Asynchronous Processing**: Bulk scanning seamlessly halts, starts, and restores its operations smoothly in the background ensuring that large numbers of images (like thousands of dataset records) are thoroughly processed.

## 🧠 Machine Learning Engine 
Sense Gallery runs highly optimized ONNX Runtime binaries, converting images to feature arrays:
*   **Detection**: `RetinaFace (det_10g.onnx)` to isolate boundary boxes, identify key landmarks, and filter out low-confidence/blurry features utilizing Laplacian Variance matrix analysis. 
*   **Alignment**: Calculates 5-point mathematical Similarity Transforms scaling identified features (eyes, nose, mouth) perfectly matching ArcFace's 112x112 standard input boundaries.
*   **Features Embeddings**: Utilizes the industry standard `ArcFace R50 (arcface_w600k_r50.onnx)` engine computing mathematically robust 512-dimension vector parameters representing structural face mappings.
*   **Clustering Algorithm**: Leverages custom code operating **Hierarchical Agglomerative Clustering (HAC)** with Average Linkage equations grouping Euclidean sets utilizing a strict similarity threshold metric ensuring identities are organized independently.

## 🛠 Tech Stack
- **Language**: Java 17+
- **Database**: SQLite backed by Android Room (PhotoEntity, FaceEntity, ClusterEntity structures). 
- **AI Runtimes**: ONNX Runtime API (`onnxruntime-android`).
- **UI & Navigation**: Fragments & Navigation UI (`BottomNavigationView`), Data binding patterns powered natively by robust MVVM (`GalleryViewModel`). Includes Google's Material Design 3 `SearchBar` layout configuration.
- **Multithreading**: Coordinated using `WorkManager` & custom `java.util.concurrent.CountDownLatch` signaling. 

## �️ Implementation Overview
*If you are interested in diving specifically into how the data pipes scale and execute numerically, explore the `ARCHITECTURE.md` file!*

## 📱 How to Use
1. Clone the project locally alongside `Android Studio`.
2. Extract the model files `det_10g.onnx` and `arcface_w600k_r50.onnx` into `app/src/main/assets/`.
3. Give Storage permissions upon startup.
4. Click the purple Scan button in the bottom right corner of the Photos view! The workers will scan in the background natively.
5. In the Clusters section, tag them and find their generated photo galleries!
