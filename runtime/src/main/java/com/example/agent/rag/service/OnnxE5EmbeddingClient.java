package com.example.agent.rag.service;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OnnxE5EmbeddingClient implements TextEmbeddingClient {
    private static final Logger LOG = LoggerFactory.getLogger(OnnxE5EmbeddingClient.class);
    private final HuggingFaceTokenizer tokenizer;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final boolean expectsTokenTypeIds;

    public OnnxE5EmbeddingClient(String modelUri, String tokenizerUri) {
        try {
            if (modelUri == null || modelUri.isBlank() || tokenizerUri == null || tokenizerUri.isBlank()) {
                throw new IllegalArgumentException("ONNX model_uri and tokenizer_uri are required");
            }
            this.tokenizer = HuggingFaceTokenizer.newInstance(Path.of(tokenizerUri));
            this.environment = OrtEnvironment.getEnvironment();
            this.session = environment.createSession(modelUri, new SessionOptions());
            this.expectsTokenTypeIds = session.getInputNames().contains("token_type_ids");
            LOG.info(
                    "Initialized ONNX E5 embedder model={} tokenizer={} inputs={} outputs={}",
                    modelUri,
                    tokenizerUri,
                    session.getInputNames(),
                    session.getOutputNames()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize ONNX E5 embedding client", exception);
        }
    }

    @Override
    public List<Float> embed(String text) {
        return embedPassage(text);
    }

    @Override
    public List<Float> embedQuery(String text) {
        return embedE5("query: " + blankSafe(text));
    }

    @Override
    public List<Float> embedPassage(String text) {
        return embedE5("passage: " + blankSafe(text));
    }

    private List<Float> embedE5(String text) {
        try {
            Encoding encoding = tokenizer.encode(text);
            long[][] inputIds = new long[][]{toLongArray(encoding.getIds())};
            long[][] attentionMask = new long[][]{toLongArray(encoding.getAttentionMask())};
            long[][] tokenTypeIds = new long[][]{zerosLike(inputIds[0].length)};

            try (
                    OnnxTensor inputIdsTensor = OnnxTensor.createTensor(environment, inputIds);
                    OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(environment, attentionMask);
                    OnnxTensor tokenTypeIdsTensor = expectsTokenTypeIds
                            ? OnnxTensor.createTensor(environment, tokenTypeIds)
                            : null
            ) {
                Map<String, OnnxTensor> input = new HashMap<>();
                input.put("input_ids", inputIdsTensor);
                input.put("attention_mask", attentionMaskTensor);
                if (expectsTokenTypeIds) {
                    input.put("token_type_ids", tokenTypeIdsTensor);
                }
                try (OrtSession.Result result = session.run(input)) {
                    float[][][] tokenEmbeddings = first3dFloatTensor(result);
                    float[] pooled = meanPool(tokenEmbeddings[0], attentionMask[0]);
                    return normalize(pooled);
                }
            }
        } catch (Exception exception) {
            LOG.error(
                    "ONNX embedding inference failed text_prefix='{}' input_names={} expects_token_type_ids={} cause={}",
                    shorten(text, 80),
                    session.getInputNames(),
                    expectsTokenTypeIds,
                    exception.toString()
            );
            throw new IllegalStateException("ONNX embedding inference failed", exception);
        }
    }

    private static long[] toLongArray(long[] values) {
        return values;
    }

    private static long[] zerosLike(int length) {
        return new long[Math.max(1, length)];
    }

    private static float[][][] first3dFloatTensor(OrtSession.Result result) throws OrtException {
        for (int i = 0; i < result.size(); i++) {
            Object value = result.get(i).getValue();
            if (value instanceof float[][][] tensor3d) {
                return tensor3d;
            }
        }
        throw new IllegalStateException("No float[batch][seq][hidden] output found in ONNX result");
    }

    private static float[] meanPool(float[][] tokenEmbeddings, long[] attentionMask) {
        int dims = tokenEmbeddings[0].length;
        float[] sum = new float[dims];
        int count = 0;
        for (int i = 0; i < tokenEmbeddings.length && i < attentionMask.length; i++) {
            if (attentionMask[i] == 0L) {
                continue;
            }
            float[] token = tokenEmbeddings[i];
            for (int d = 0; d < dims; d++) {
                sum[d] += token[d];
            }
            count++;
        }
        if (count == 0) {
            return sum;
        }
        for (int d = 0; d < dims; d++) {
            sum[d] /= count;
        }
        return sum;
    }

    private static List<Float> normalize(float[] vector) {
        double norm = 0.0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        List<Float> normalized = new ArrayList<>(vector.length);
        if (norm == 0.0) {
            for (float value : vector) {
                normalized.add(value);
            }
            return normalized;
        }
        for (float value : vector) {
            normalized.add((float) (value / norm));
        }
        return normalized;
    }

    private static String blankSafe(String value) {
        return value == null ? "" : value;
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }
}
