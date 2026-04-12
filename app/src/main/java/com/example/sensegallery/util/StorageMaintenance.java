package com.example.sensegallery.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Best-effort startup cleanup to keep app storage bounded.
 */
public final class StorageMaintenance {

    private static final String TAG = "StorageMaintenance";
    private static final long MAP_CACHE_MAX_BYTES = 64L * 1024L * 1024L;
    private static final long GLIDE_CACHE_MAX_BYTES = 64L * 1024L * 1024L;

    private StorageMaintenance() {
    }

    public static void runStartupCleanup(Context context) {
        if (context == null) {
            return;
        }

        try {
            // Trim map tile cache if it grew too large.
            trimDirectory(new File(context.getCacheDir(), "osmdroid"), MAP_CACHE_MAX_BYTES);

            // Trim Glide disk cache.
            trimDirectory(new File(context.getCacheDir(), "image_manager_disk_cache"), GLIDE_CACHE_MAX_BYTES);

            // CLIP models are now loaded from assets in memory, so old cache copies can be purged.
            deleteDirectoryContents(new File(context.getCacheDir(), "models"), true);

            // Remove stale temp files used by model copying.
            deleteTempSuffixRecursively(new File(context.getCacheDir(), "llm"));
        } catch (Exception e) {
            Log.w(TAG, "Startup storage cleanup failed: " + e.getMessage());
        }
    }

    private static void trimDirectory(File root, long maxBytes) {
        if (root == null || !root.exists() || !root.isDirectory()) {
            return;
        }

        List<File> files = new ArrayList<>();
        collectFiles(root, files);
        long totalBytes = 0L;
        for (File file : files) {
            totalBytes += file.length();
        }

        if (totalBytes <= maxBytes) {
            return;
        }

        files.sort(Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (totalBytes <= maxBytes) {
                break;
            }
            long size = file.length();
            if (file.delete()) {
                totalBytes -= size;
            }
        }

        deleteEmptyDirectories(root);
    }

    private static void collectFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                collectFiles(child, out);
            } else {
                out.add(child);
            }
        }
    }

    private static void deleteTempSuffixRecursively(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }

        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                deleteTempSuffixRecursively(child);
            } else if (child.getName().endsWith(".tmp")) {
                child.delete();
            }
        }
    }

    private static void deleteEmptyDirectories(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                deleteEmptyDirectories(child);
                File[] nested = child.listFiles();
                if (nested == null || nested.length == 0) {
                    child.delete();
                }
            }
        }
    }

    private static void deleteDirectoryContents(File root, boolean deleteRoot) {
        if (root == null || !root.exists()) {
            return;
        }

        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteDirectoryContents(child, true);
                } else {
                    child.delete();
                }
            }
        }

        if (deleteRoot) {
            root.delete();
        }
    }
}
