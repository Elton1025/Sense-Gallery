package com.example.sensegallery.ai;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import io.aatricks.llmedge.text.runtime.SmolLM;
import io.aatricks.llmedge.text.runtime.SmolLMJavaCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Light wrapper around llmedge SmolLM for parsing natural language search
 * into structured name + scene intent.
 */
public class LLMQueryParser implements AutoCloseable {

    private static final String TAG = "LLMQueryParser";
    private static final String MODEL_ASSET_NAME = "SmolLM2-135M-Instruct.Q4_K_M.gguf";
    private static final int MAX_NAMES_IN_PROMPT = 80;

    private final Context appContext;
    private final Object lock = new Object();

    private SmolLM smolLM;
    private boolean loaded;
    private boolean disabled;

    public LLMQueryParser(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * @return parsed result, or null if the LLM is unavailable/failed.
     */
    public ParsedQuery parse(String query, List<String> knownNames) {
        if (query == null || query.trim().isEmpty()) {
            return ParsedQuery.empty();
        }

        synchronized (lock) {
            if (!ensureLoadedLocked()) {
                return null;
            }

            try {
                String prompt = buildParserPrompt(query.trim(), knownNames);
                String response = smolLM.getResponse(prompt, 128, 4);
                ParsedQuery parsed = parseJsonResponse(response, knownNames);
                return parsed != null ? parsed : ParsedQuery.empty();
            } catch (Throwable t) {
                Log.w(TAG, "LLM parse failed, falling back to heuristic parser: " + t.getMessage(), t);
                return null;
            }
        }
    }

    private boolean ensureLoadedLocked() {
        if (disabled) {
            return false;
        }
        if (loaded && smolLM != null) {
            return true;
        }

        try {
            String modelPath = copyModelToFilesDir(MODEL_ASSET_NAME, false);
            SmolLM runtime = new SmolLM(false);

            int cpuCount = Runtime.getRuntime().availableProcessors();
            int numThreads = Math.max(2, Math.min(4, cpuCount));
            Integer generationThreads = Integer.valueOf(Math.max(1, Math.min(2, numThreads)));

            SmolLM.InferenceParams params = new SmolLMJavaCompat.InferenceParamsBuilder()
                    .setStoreChats(false)
                    .setNumThreads(numThreads)
                    .setGenerationThreads(generationThreads)
                    .setContextSize(2048L)
                    .setTemperature(0.1f)
                    .setMinP(0.05f)
                    .setThinkingMode(SmolLM.ThinkingMode.DISABLED)
                    .setReasoningBudget(0)
                    .build();

            SmolLMJavaCompat.loadBlocking(runtime, modelPath, params);

            smolLM = runtime;
            loaded = true;
            Log.i(TAG, "SmolLM query parser loaded from " + modelPath);
            return true;
        } catch (Throwable t) {
            disabled = true;
            closeQuietlyLocked();
            Log.e(TAG, "Failed to load SmolLM parser; disabling LLM parsing", t);
            return false;
        }
    }

    private String buildParserPrompt(String userQuery, List<String> knownNames) {
        List<String> cleanedNames = new ArrayList<>();
        if (knownNames != null) {
            Set<String> dedup = new LinkedHashSet<>();
            for (String name : knownNames) {
                if (name == null) {
                    continue;
                }
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    dedup.add(trimmed);
                }
                if (dedup.size() >= MAX_NAMES_IN_PROMPT) {
                    break;
                }
            }
            cleanedNames.addAll(dedup);
        }

        String namesLine = cleanedNames.isEmpty()
                ? "(none)"
                : String.join(", ", cleanedNames);

        return "You are a parser for an offline Android photo gallery search.\n"
                + "Convert the user query to strict JSON with exactly these keys: names, scene, requireAllPeople.\n"
                + "Rules:\n"
                + "- names: array of exact names chosen only from the known names list.\n"
                + "- Include every person the user asks to appear in the same photo.\n"
                + "- scene: short visual description excluding person names.\n"
                + "- requireAllPeople: true only when user intent requires all listed people together (e.g. uses and/together/same photo). Otherwise false.\n"
                + "- If no names: []\n"
                + "- If no scene: \"\"\n"
                + "- If no strict together intent: requireAllPeople = false\n"
                + "- Output JSON only. No markdown. No extra text.\n\n"
                + "Known names: " + namesLine + "\n"
                + "User query: " + escapeForPrompt(userQuery) + "\n"
                + "JSON:";
    }

    private ParsedQuery parseJsonResponse(String rawResponse, List<String> knownNames) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return null;
        }

        String json = extractJsonObject(rawResponse);
        if (json == null) {
            return null;
        }

        JSONObject object;
        try {
            object = new JSONObject(json);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse LLM JSON: " + e.getMessage());
            return null;
        }

        Map<String, String> canonicalByLower = new LinkedHashMap<>();
        if (knownNames != null) {
            for (String name : knownNames) {
                if (name == null) {
                    continue;
                }
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    canonicalByLower.put(trimmed.toLowerCase(Locale.US), trimmed);
                }
            }
        }

        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JSONArray namesJson = object.optJSONArray("names");
        if (namesJson != null) {
            for (int i = 0; i < namesJson.length(); i++) {
                String candidate = namesJson.optString(i, "").trim();
                if (candidate.isEmpty()) {
                    continue;
                }

                String normalized = candidate.toLowerCase(Locale.US);
                String canonical = canonicalByLower.get(normalized);
                if (canonical == null) {
                    for (Map.Entry<String, String> entry : canonicalByLower.entrySet()) {
                        if (entry.getKey().contains(normalized) || normalized.contains(entry.getKey())) {
                            canonical = entry.getValue();
                            break;
                        }
                    }
                }

                if (canonical != null && seen.add(canonical.toLowerCase(Locale.US))) {
                    names.add(canonical);
                }
            }
        }

        String scene = firstNonEmpty(
                object.optString("scene", ""),
                object.optString("description", ""),
                object.optString("clip_query", ""),
                object.optString("query", "")).trim();

        if ("none".equalsIgnoreCase(scene) || "null".equalsIgnoreCase(scene)) {
            scene = "";
        }
        if (scene.length() > 180) {
            scene = scene.substring(0, 180).trim();
        }

        boolean requireAllPeople = object.optBoolean("requireAllPeople", false);
        return new ParsedQuery(names, scene, requireAllPeople);
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private String escapeForPrompt(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\"", "\\\"");
    }

    private String copyModelToFilesDir(String fileName, boolean forceRefresh) throws IOException {
        File llmDir = new File(appContext.getFilesDir(), "llm");
        if (!llmDir.exists() && !llmDir.mkdirs()) {
            throw new IOException("Could not create model cache directory: " + llmDir.getAbsolutePath());
        }

        File destFile = new File(llmDir, fileName);
        long assetLength = getAssetLength(fileName);

        if (!forceRefresh && isUsableCachedFile(destFile, assetLength)) {
            return destFile.getAbsolutePath();
        }

        File tempFile = new File(llmDir, fileName + ".tmp");
        if (tempFile.exists() && !tempFile.delete()) {
            throw new IOException("Could not clear temp model file: " + tempFile.getAbsolutePath());
        }

        long totalBytes = 0L;
        byte[] buffer = new byte[65536];
        try (InputStream is = appContext.getAssets().open(fileName);
                FileOutputStream fos = new FileOutputStream(tempFile)) {
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                totalBytes += read;
            }
            fos.flush();
        }

        if (assetLength > 0 && totalBytes != assetLength) {
            if (!tempFile.delete()) {
                Log.w(TAG, "Failed to delete incomplete temp model: " + tempFile.getAbsolutePath());
            }
            throw new IOException("Copied " + totalBytes + " bytes but expected " + assetLength);
        }

        if (destFile.exists() && !destFile.delete()) {
            throw new IOException("Could not replace model cache: " + destFile.getAbsolutePath());
        }
        if (!tempFile.renameTo(destFile)) {
            throw new IOException("Could not move temp model to cache: " + destFile.getAbsolutePath());
        }

        return destFile.getAbsolutePath();
    }

    private boolean isUsableCachedFile(File cached, long assetLength) {
        if (!cached.exists() || cached.length() <= 0) {
            return false;
        }
        if (assetLength <= 0L) {
            return true;
        }
        return cached.length() == assetLength;
    }

    private long getAssetLength(String fileName) {
        try (AssetFileDescriptor afd = appContext.getAssets().openFd(fileName)) {
            return afd.getLength();
        } catch (Exception e) {
            Log.w(TAG, "Unable to read asset length for " + fileName + ": " + e.getMessage());
            return -1L;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeQuietlyLocked();
        }
    }

    private void closeQuietlyLocked() {
        loaded = false;
        if (smolLM != null) {
            try {
                smolLM.close();
            } catch (Throwable ignored) {
                // no-op
            }
            smolLM = null;
        }
    }

    public static final class ParsedQuery {
        private final List<String> personNames;
        private final String sceneDescription;
        private final boolean requireAllPeople;

        ParsedQuery(List<String> personNames, String sceneDescription, boolean requireAllPeople) {
            this.personNames = personNames == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(personNames));
            this.sceneDescription = sceneDescription == null ? "" : sceneDescription.trim();
            this.requireAllPeople = requireAllPeople;
        }

        public static ParsedQuery empty() {
            return new ParsedQuery(Collections.emptyList(), "", false);
        }

        public List<String> getPersonNames() {
            return personNames;
        }

        public String getSceneDescription() {
            return sceneDescription;
        }

        public boolean isRequireAllPeople() {
            return requireAllPeople;
        }

        public boolean hasContent() {
            return !personNames.isEmpty() || !sceneDescription.isEmpty();
        }
    }
}
