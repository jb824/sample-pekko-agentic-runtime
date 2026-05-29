package com.example.agent.llm;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.example.agent.config.AppConfig;
import com.google.protobuf.ByteString;
import dev.langchain4j.model.chat.ChatModel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smg.grpc.common.Common.GetTokenizerChunk;
import smg.grpc.common.Common.GetTokenizerRequest;
import vllm.grpc.engine.VllmEngineGrpc;
import vllm.grpc.engine.VllmEngineOuterClass.GenerateRequest;
import vllm.grpc.engine.VllmEngineOuterClass.GenerateResponse;
import vllm.grpc.engine.VllmEngineOuterClass.SamplingParams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.stream.Stream;

final class VllmGrpcChatModel implements ChatModel {
    private static final Logger LOGGER = LoggerFactory.getLogger(VllmGrpcChatModel.class);

    private final ManagedChannel channel;
    private final VllmEngineGrpc.VllmEngineBlockingStub stub;
    private final String systemPrompt;
    private final int maxTokens;
    private final String tokenizerPath;
    private final double temperature;
    private final Duration timeout;
    private final Object tokenizerLock = new Object();
    private HuggingFaceTokenizer tokenizer;

    VllmGrpcChatModel(AppConfig config) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(
                config.vllmGrpcHost(),
                config.vllmGrpcPort()
        );
        if (config.vllmGrpcPlaintext()) {
            builder.usePlaintext();
        }
        this.channel = builder.build();
        this.stub = VllmEngineGrpc.newBlockingStub(channel);
        this.systemPrompt = config.vllmSystemPrompt();
        this.maxTokens = config.vllmMaxTokens();
        this.tokenizerPath = config.vllmTokenizerPath();
        this.temperature = config.temperature();
        this.timeout = config.llmTimeout();

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "vllm-grpc-shutdown"));
    }

    @Override
    public String chat(String userMessage) {
        String prompt = """
                System:
                %s

                User:
                %s

                Assistant:
                """.formatted(systemPrompt, userMessage);
        LOGGER.info("Sending vLLM gRPC generate prompt:\n{}", prompt);

        SamplingParams samplingParams = SamplingParams.newBuilder()
                .setTemperature((float) temperature)
                .setTopP(1.0F)
                .setRepetitionPenalty(1.0F)
                .setMaxTokens(maxTokens)
                .setSkipSpecialTokens(true)
                .setSpacesBetweenSpecialTokens(true)
                .setN(1)
                .build();

        GenerateRequest request = GenerateRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setText(prompt)
                .setSamplingParams(samplingParams)
                .setStream(false)
                .build();

        try {
            Iterator<GenerateResponse> responses = stub
                    .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .generate(request);
            while (responses.hasNext()) {
                GenerateResponse response = responses.next();
                if (response.hasComplete()) {
                    return decode(response.getComplete().getOutputIdsList());
                }
            }
            throw new IllegalStateException("vLLM gRPC Generate completed without a response");
        } catch (StatusRuntimeException exception) {
            throw new IllegalStateException("vLLM gRPC request failed: " + exception.getStatus(), exception);
        }
    }

    private String decode(List<Integer> outputIds) {
        long[] ids = new long[outputIds.size()];
        for (int i = 0; i < outputIds.size(); i++) {
            ids[i] = Integer.toUnsignedLong(outputIds.get(i));
        }
        synchronized (tokenizerLock) {
            HuggingFaceTokenizer currentTokenizer = tokenizer;
            if (currentTokenizer == null) {
                currentTokenizer = loadTokenizer();
                tokenizer = currentTokenizer;
            }
            return currentTokenizer.decode(ids, true);
        }
    }

    private HuggingFaceTokenizer loadTokenizer() {
        if (tokenizerPath != null && !tokenizerPath.isBlank()) {
            Path path = resolveTokenizerPath(Path.of(tokenizerPath));
            if (!Files.exists(path)) {
                throw new IllegalStateException("VLLM_TOKENIZER_PATH does not exist: " + path);
            }
            try {
                return HuggingFaceTokenizer.newInstance(path);
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Failed to load tokenizer from VLLM_TOKENIZER_PATH: " + path
                                + ". The directory must contain tokenizer.json or compatible tokenizer files.",
                        exception
                );
            }
        }

        try {
            ByteArrayOutputStream tokenizerZip = new ByteArrayOutputStream();
            String expectedSha256 = "";
            Iterator<GetTokenizerChunk> chunks = stub
                    .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .getTokenizer(GetTokenizerRequest.newBuilder().build());
            while (chunks.hasNext()) {
                GetTokenizerChunk chunk = chunks.next();
                ByteString data = chunk.getData();
                data.writeTo(tokenizerZip);
                if (!chunk.getSha256().isBlank()) {
                    expectedSha256 = chunk.getSha256();
                }
            }

            byte[] zipBytes = tokenizerZip.toByteArray();
            if (zipBytes.length == 0) {
                throw new IllegalStateException("vLLM gRPC GetTokenizer returned no tokenizer bytes");
            }
            verifySha256(zipBytes, expectedSha256);

            Path tokenizerDirectory = Files.createTempDirectory("vllm-grpc-tokenizer-");
            unzip(zipBytes, tokenizerDirectory);
            return HuggingFaceTokenizer.newInstance(tokenizerDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load vLLM gRPC tokenizer", exception);
        } catch (StatusRuntimeException exception) {
            throw new IllegalStateException("vLLM gRPC GetTokenizer failed: " + exception.getStatus(), exception);
        }
    }

    private static void verifySha256(byte[] bytes, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            return;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String actualSha256 = HexFormat.of().formatHex(digest.digest(bytes));
            if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                throw new IllegalStateException("vLLM gRPC tokenizer checksum mismatch");
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void unzip(byte[] zipBytes, Path targetDirectory) throws IOException {
        try (InputStream input = new java.io.ByteArrayInputStream(zipBytes);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = targetDirectory.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetDirectory)) {
                    throw new IOException("Unsafe tokenizer zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target);
                }
                zip.closeEntry();
            }
        }
    }

    private static Path resolveTokenizerPath(Path configuredPath) {
        if (Files.isRegularFile(configuredPath)) {
            return configuredPath;
        }
        if (Files.exists(configuredPath.resolve("tokenizer.json"))) {
            return configuredPath;
        }
        Path resolvedSnapshot = singleSnapshotDirectory(configuredPath);
        if (resolvedSnapshot != null) {
            return resolvedSnapshot;
        }
        return configuredPath;
    }

    private static Path singleSnapshotDirectory(Path configuredPath) {
        if (!Files.isDirectory(configuredPath)) {
            return null;
        }
        try (Stream<Path> children = Files.list(configuredPath)) {
            List<Path> snapshotDirectories = children
                    .filter(Files::isDirectory)
                    .filter(path -> Files.exists(path.resolve("tokenizer.json")))
                    .toList();
            return snapshotDirectories.size() == 1 ? snapshotDirectories.getFirst() : null;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect VLLM_TOKENIZER_PATH: " + configuredPath, exception);
        }
    }

    private void shutdown() {
        synchronized (tokenizerLock) {
            if (tokenizer != null) {
                tokenizer.close();
                tokenizer = null;
            }
        }
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
