package com.example.sensegallery.ai;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java CLIP BPE tokenizer.
 * Reads vocab.json and merges.txt from assets.
 * No DJL HuggingFace dependency (crashes on ARM64).
 *
 * Output: long[77] = [SOT=49406, ...token_ids..., EOT=49407, 0-padding...]
 */
public class CLIPTokenizer {

    private static final String TAG = "CLIPTokenizer";
    private static final int CONTEXT_LENGTH = 77;
    private static final int SOT_TOKEN = 49406;
    private static final int EOT_TOKEN = 49407;

    private static final Pattern PAT = Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>"
                    + "|'s|'t|'re|'ve|'m|'ll|'d"
                    + "|[\\p{L}]+"
                    + "|[\\p{N}]"
                    + "|[^\\s\\p{L}\\p{N}]+",
            Pattern.CASE_INSENSITIVE);

    private Map<String, Integer> vocab;
    private List<String[]> merges;
    private Map<String, Integer> mergeRanks;
    private boolean initialized = false;
    private String initError = null;

    public CLIPTokenizer(Context context) {
        try {
            loadVocab(context);
            loadMerges(context);
            initialized = true;
            Log.d(TAG, "CLIPTokenizer initialized: " + vocab.size() + " vocab, "
                    + merges.size() + " merges");
        } catch (Exception e) {
            initError = e.getMessage();
            Log.e(TAG, "Failed to init CLIPTokenizer: " + initError, e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getInitError() {
        return initError;
    }

    /**
     * Tokenizes text into long[77] for the CLIP text encoder.
     */
    public long[] tokenize(String text) {
        if (!initialized) {
            Log.e(TAG, "Tokenizer not initialized");
            return new long[CONTEXT_LENGTH];
        }

        long[] result = new long[CONTEXT_LENGTH];
        result[0] = SOT_TOKEN;

        String lowerText = text.toLowerCase().trim();
        List<Integer> tokens = new ArrayList<>();

        Matcher matcher = PAT.matcher(lowerText);
        while (matcher.find()) {
            String word = matcher.group();
            if (word.startsWith("<|") && word.endsWith("|>")) {
                continue; // skip special tokens
            }
            List<String> bpeTokens = bpe(word);
            for (String bpeToken : bpeTokens) {
                Integer id = vocab.get(bpeToken);
                if (id != null) {
                    tokens.add(id);
                }
            }
        }

        // Fill result: SOT + tokens + EOT + padding
        int maxTokens = CONTEXT_LENGTH - 2; // room for SOT and EOT
        int count = Math.min(tokens.size(), maxTokens);
        for (int i = 0; i < count; i++) {
            result[i + 1] = tokens.get(i);
        }
        result[count + 1] = EOT_TOKEN;
        // remaining positions stay 0 (padding)

        Log.d(TAG, "Tokenized \"" + text + "\" -> " + (count + 2) + " tokens");
        return result;
    }

    /**
     * Applies BPE merges to a single word.
     * Adds end-of-word marker on last character.
     */
    private List<String> bpe(String word) {
        if (word.isEmpty())
            return new ArrayList<>();

        // Split into individual characters, add </w> to last
        List<String> wordParts = new ArrayList<>();
        for (int i = 0; i < word.length(); i++) {
            String ch = String.valueOf(word.charAt(i));
            if (i == word.length() - 1) {
                ch = ch + "</w>";
            }
            wordParts.add(ch);
        }

        // Iteratively merge the highest-priority pair
        while (wordParts.size() > 1) {
            int bestIdx = -1;
            int bestRank = Integer.MAX_VALUE;

            for (int i = 0; i < wordParts.size() - 1; i++) {
                String pair = wordParts.get(i) + " " + wordParts.get(i + 1);
                Integer rank = mergeRanks.get(pair);
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bestIdx = i;
                }
            }

            if (bestIdx == -1)
                break; // no more merges possible

            // Merge the pair at bestIdx
            String merged = wordParts.get(bestIdx) + wordParts.get(bestIdx + 1);
            wordParts.set(bestIdx, merged);
            wordParts.remove(bestIdx + 1);
        }

        return wordParts;
    }

    private void loadVocab(Context context) throws Exception {
        InputStream is = context.getAssets().open("vocab.json");
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        is.close();

        JSONObject json = new JSONObject(sb.toString());
        vocab = new HashMap<>(json.length());
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            vocab.put(key, json.getInt(key));
        }
        Log.d(TAG, "Loaded vocab: " + vocab.size() + " entries");
    }

    private void loadMerges(Context context) throws Exception {
        InputStream is = context.getAssets().open("merges.txt");
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        merges = new ArrayList<>();
        mergeRanks = new HashMap<>();

        String line;
        boolean firstLine = true;
        int rank = 0;
        while ((line = reader.readLine()) != null) {
            // Skip header line (usually "#version: ...")
            if (firstLine) {
                firstLine = false;
                if (line.startsWith("#"))
                    continue;
            }
            line = line.trim();
            if (line.isEmpty())
                continue;

            String[] parts = line.split(" ", 2);
            if (parts.length == 2) {
                merges.add(parts);
                mergeRanks.put(parts[0] + " " + parts[1], rank);
                rank++;
            }
        }
        reader.close();
        is.close();
        Log.d(TAG, "Loaded merges: " + merges.size() + " rules");
    }
}
