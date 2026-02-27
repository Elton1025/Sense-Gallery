package com.example.sensegallery.ai;

import android.content.Context;
import android.util.Log;

import com.example.sensegallery.data.AppDatabase;
import com.example.sensegallery.data.ClusterDao;
import com.example.sensegallery.data.ClusterEntity;
import com.example.sensegallery.data.EmbeddingTypeConverter;
import com.example.sensegallery.data.FaceDao;
import com.example.sensegallery.data.FaceEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Groups detected faces into identity clusters using Hierarchical
 * Agglomerative Clustering (HAC) with Average Linkage.
 *
 * Optimized with a distance matrix and O(N^2) Lancs-Williams updates.
 * Accuracy vs Speed: Average Linkage provides the best balance for ArcFace R50.
 */
public class FaceClusterer {

    private static final String TAG = "FaceClusterer";

    // L2 Distance Threshold (calibrated for ArcFace R50 512-d embeddings)
    // Same person: L2 ≈ 0.4-0.8. Different person: L2 ≈ 1.2-1.6
    // Threshold: 1.1 with Average Linkage gives high precision and better recall
    // than Complete.
    private static final float L2_DISTANCE_THRESHOLD = 1.1f;

    private final AppDatabase database;
    private final FaceDao faceDao;
    private final ClusterDao clusterDao;

    public FaceClusterer(Context context) {
        this.database = AppDatabase.getInstance(context);
        this.faceDao = database.faceDao();
        this.clusterDao = database.clusterDao();
    }

    /**
     * Runs HAC with Average Linkage.
     */
    public void clusterFaces() {
        Log.i(TAG, "Starting HAC clustering (Average Linkage, threshold=" + L2_DISTANCE_THRESHOLD + ")...");

        // 1. Load faces and prepare nodes
        List<FaceEntity> unclusteredFaces = faceDao.getUnclusteredFaces();
        List<ClusterEntity> clusterEntities = clusterDao.getAllClusters();

        if ((unclusteredFaces == null || unclusteredFaces.isEmpty()) && clusterEntities.isEmpty()) {
            Log.i(TAG, "No faces or clusters to process.");
            return;
        }

        List<ClusterNode> nodes = new ArrayList<>();

        // Load existing clusters
        for (ClusterEntity entity : clusterEntities) {
            List<FaceEntity> facesInCluster = faceDao.getFacesInCluster(entity.id);
            if (facesInCluster.isEmpty())
                continue;

            List<float[]> embeddings = new ArrayList<>();
            for (FaceEntity f : facesInCluster) {
                if (f.faceEmbedding == null)
                    continue;
                float[] emb = EmbeddingTypeConverter.toFloatArray(f.faceEmbedding);
                if (emb != null && emb.length > 0)
                    embeddings.add(emb);
            }

            if (!embeddings.isEmpty()) {
                nodes.add(new ClusterNode(entity.id, embeddings, facesInCluster));
            }
        }

        // Add new faces
        if (unclusteredFaces != null) {
            for (FaceEntity face : unclusteredFaces) {
                if (face.faceEmbedding == null)
                    continue;
                float[] emb = EmbeddingTypeConverter.toFloatArray(face.faceEmbedding);
                if (emb != null && emb.length > 0) {
                    nodes.add(new ClusterNode(face, emb));
                }
            }
        }

        int N = nodes.size();
        if (N < 2) {
            Log.i(TAG, "Only " + N + " node(s) found. No merging needed.");
            saveNodesToDb(nodes);
            return;
        }

        Log.i(TAG, "HAC: Initializing distance matrix for " + N + " nodes...");

        // 2. Compute initial distance matrix (Upper triangular)
        float[][] distMatrix = new float[N][N];
        for (int i = 0; i < N; i++) {
            for (int j = i + 1; j < N; j++) {
                distMatrix[i][j] = calculateAverageLinkageDist(nodes.get(i), nodes.get(j));
            }
        }

        // 3. HAC Loop
        int mergesCount = 0;
        long hacStart = System.currentTimeMillis();
        boolean[] isNodeCombined = new boolean[N]; // Tracks which nodes are "deleted"

        while (true) {
            float minDistance = Float.MAX_VALUE;
            int bestI = -1;
            int bestJ = -1;

            // Find pair with world minimum distance
            for (int i = 0; i < N; i++) {
                if (isNodeCombined[i])
                    continue;
                for (int j = i + 1; j < N; j++) {
                    if (isNodeCombined[j])
                        continue;
                    if (distMatrix[i][j] < minDistance) {
                        minDistance = distMatrix[i][j];
                        bestI = i;
                        bestJ = j;
                    }
                }
            }

            if (bestI == -1 || minDistance > L2_DISTANCE_THRESHOLD) {
                Log.i(TAG, "HAC: Stopping. Min distance " + String.format("%.3f", minDistance)
                        + " > threshold " + L2_DISTANCE_THRESHOLD);
                break;
            }

            // Merge J into I
            ClusterNode nodeI = nodes.get(bestI);
            ClusterNode nodeJ = nodes.get(bestJ);

            int sizeI = nodeI.embeddings.size();
            int sizeJ = nodeJ.embeddings.size();

            nodeI.merge(nodeJ);
            isNodeCombined[bestJ] = true;

            // Update distances for node I using Lance-Williams rule for Average Linkage:
            // d(I+J, K) = (sizeI * d(I, K) + sizeJ * d(J, K)) / (sizeI + sizeJ)
            for (int k = 0; k < N; k++) {
                if (k == bestI || k == bestJ || isNodeCombined[k])
                    continue;

                float d_ik = getDist(distMatrix, bestI, k);
                float d_jk = getDist(distMatrix, bestJ, k);
                float newDist = (sizeI * d_ik + sizeJ * d_jk) / (sizeI + sizeJ);

                setDist(distMatrix, bestI, k, newDist);
            }

            mergesCount++;
            if (mergesCount % 10 == 0) {
                Log.d(TAG, "HAC: Merges performed: " + mergesCount);
            }
        }

        Log.i(TAG, "HAC complete: " + mergesCount + " merges in " + (System.currentTimeMillis() - hacStart) + "ms");

        // 4. Save results (only active nodes)
        List<ClusterNode> finalNodes = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            if (!isNodeCombined[i])
                finalNodes.add(nodes.get(i));
        }
        saveNodesToDb(finalNodes);
    }

    private float getDist(float[][] matrix, int i, int j) {
        return (i < j) ? matrix[i][j] : matrix[j][i];
    }

    private void setDist(float[][] matrix, int i, int j, float val) {
        if (i < j)
            matrix[i][j] = val;
        else
            matrix[j][i] = val;
    }

    private void saveNodesToDb(List<ClusterNode> nodes) {
        int facesUpdated = 0;
        int newClusters = 0;
        for (ClusterNode node : nodes) {
            if (!node.isDirty)
                continue;
            long clusterId = node.clusterId;
            if (clusterId == -1 && !node.faces.isEmpty()) {
                ClusterEntity c = new ClusterEntity(
                        "Person " + (clusterDao.getClusterCount() + 1 + newClusters),
                        node.faces.get(0).photoId);
                clusterId = clusterDao.insertCluster(c);
                newClusters++;
            }
            if (clusterId != -1) {
                for (FaceEntity face : node.faces) {
                    if (face.clusterId == null || face.clusterId != clusterId) {
                        face.clusterId = clusterId;
                        faceDao.updateFace(face);
                        facesUpdated++;
                    }
                }
            }
        }
        Log.i(TAG, "Clustering phase finished. Faces updated: " + facesUpdated + ", New clusters: " + newClusters);
    }

    private float calculateAverageLinkageDist(ClusterNode a, ClusterNode b) {
        float sumDist = 0f;
        int count = 0;
        for (float[] embA : a.embeddings) {
            for (float[] embB : b.embeddings) {
                sumDist += calculateL2Distance(embA, embB);
                count++;
            }
        }
        return count > 0 ? sumDist / count : Float.MAX_VALUE;
    }

    private float calculateL2Distance(float[] v1, float[] v2) {
        float sumSq = 0f;
        for (int i = 0; i < v1.length; i++) {
            float diff = v1[i] - v2[i];
            sumSq += diff * diff;
        }
        return (float) Math.sqrt(sumSq);
    }

    private static class ClusterNode {
        long clusterId = -1;
        List<float[]> embeddings = new ArrayList<>();
        List<FaceEntity> faces = new ArrayList<>();
        boolean isDirty = false;

        ClusterNode(FaceEntity face, float[] embedding) {
            this.embeddings.add(embedding);
            this.faces.add(face);
            this.isDirty = true;
        }

        ClusterNode(long clusterId, List<float[]> embeddings, List<FaceEntity> faces) {
            this.clusterId = clusterId;
            this.embeddings.addAll(embeddings);
            this.faces.addAll(faces);
            this.isDirty = false;
        }

        void merge(ClusterNode other) {
            this.embeddings.addAll(other.embeddings);
            this.faces.addAll(other.faces);
            if (this.clusterId == -1 && other.clusterId != -1) {
                this.clusterId = other.clusterId;
            }
            this.isDirty = true;
        }
    }
}
