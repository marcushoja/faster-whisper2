package de.hoja.fasterwhisper2.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException.BadRequest;
import org.springframework.web.reactive.function.client.WebClientResponseException.Forbidden;
import org.springframework.web.reactive.function.client.WebClientResponseException.Unauthorized;

@Service
public class GroqTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(GroqTranscriptionService.class);
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final WebClient groqWebClient;
    private final String model;
    private final long chunkMaxBytes;
    private final int chunkAudioBitrateKbps;
    private final double chunkSizeSafetyFactor;

    public GroqTranscriptionService(
        WebClient groqWebClient,
        @Value("${groq.model:whisper-large-v3-turbo}") String model,
        @Value("${app.transcription.chunk.max-mb:20}") int chunkMaxMb,
        @Value("${app.transcription.chunk.audio-bitrate-kbps:64}") int chunkAudioBitrateKbps,
        @Value("${app.transcription.chunk.size-safety-factor:0.90}") double chunkSizeSafetyFactor
    ) {
        if (chunkMaxMb < 1) {
            throw new IllegalArgumentException("app.transcription.chunk.max-mb must be >= 1");
        }
        if (chunkAudioBitrateKbps < 8) {
            throw new IllegalArgumentException("app.transcription.chunk.audio-bitrate-kbps must be >= 8");
        }
        if (chunkSizeSafetyFactor <= 0.0 || chunkSizeSafetyFactor > 1.0) {
            throw new IllegalArgumentException("app.transcription.chunk.size-safety-factor must be > 0 and <= 1");
        }

        this.groqWebClient = groqWebClient;
        this.model = model;
        this.chunkMaxBytes = chunkMaxMb * BYTES_PER_MB;
        this.chunkAudioBitrateKbps = chunkAudioBitrateKbps;
        this.chunkSizeSafetyFactor = chunkSizeSafetyFactor;

        log.info(
            "Groq chunking configured: maxMb={}, bitrateKbps={}, safetyFactor={}",
            chunkMaxMb,
            chunkAudioBitrateKbps,
            chunkSizeSafetyFactor
        );
    }

    public Map<String, Object> transcribe(MultipartFile file, String prompt, String language, String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("Missing Groq API key");
        }

        log.info(
            "Incoming transcription request: file='{}', size={}, chunkThreshold={}",
            file.getOriginalFilename(),
            formatBytes(file.getSize()),
            formatBytes(chunkMaxBytes)
        );

        if (file.getSize() <= chunkMaxBytes) {
            log.info("File is below chunk threshold, sending as single Groq request.");
            return transcribeSingle(file, prompt, language, apiKey);
        }

        log.info("File is above chunk threshold, splitting into m4a chunks.");
        return transcribeChunked(file, prompt, language, apiKey);
    }

    private Map<String, Object> transcribeSingle(MultipartFile file, String prompt, String language, String apiKey) {
        try {
            MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
            bodyBuilder.part("file", new NamedByteArrayResource(file.getBytes(), file.getOriginalFilename()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
            bodyBuilder.part("model", model);

            if (StringUtils.hasText(prompt)) {
                bodyBuilder.part("prompt", prompt);
            }
            if (StringUtils.hasText(language)) {
                bodyBuilder.part("language", language);
            }

            Map<String, Object> response = requestGroq(bodyBuilder, apiKey);
            Object text = response.get("text");
            int textLength = text == null ? 0 : text.toString().length();
            log.info("Single-request transcription completed. textLength={}", textLength);
            return response;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded audio file.", e);
        }
    }

    private Map<String, Object> transcribeChunked(MultipartFile file, String prompt, String language, String apiKey) {
        Path workingDirectory = null;
        try {
            workingDirectory = Files.createTempDirectory("groq-audio-chunks-");
            Path inputFile = workingDirectory.resolve(resolveInputFileName(file.getOriginalFilename()));
            file.transferTo(inputFile.toFile());

            List<Path> chunkFiles = splitIntoM4aChunks(inputFile, workingDirectory);
            log.info("Chunking finished. chunkCount={}", chunkFiles.size());

            StringBuilder mergedText = new StringBuilder();
            for (int i = 0; i < chunkFiles.size(); i++) {
                Path chunkFile = chunkFiles.get(i);
                long chunkSize = Files.size(chunkFile);
                log.info(
                    "Sending chunk {}/{}: file='{}', size={}",
                    i + 1,
                    chunkFiles.size(),
                    chunkFile.getFileName(),
                    formatBytes(chunkSize)
                );

                MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
                bodyBuilder.part("file", new FileSystemResource(chunkFile.toFile()))
                    .contentType(MediaType.parseMediaType("audio/mp4"));
                bodyBuilder.part("model", model);

                if (StringUtils.hasText(prompt)) {
                    bodyBuilder.part("prompt", prompt);
                }
                if (StringUtils.hasText(language)) {
                    bodyBuilder.part("language", language);
                }

                Map<String, Object> chunkResponse = requestGroq(bodyBuilder, apiKey);
                Object text = chunkResponse.get("text");
                if (text != null && StringUtils.hasText(text.toString())) {
                    String chunkText = text.toString().trim();
                    if (mergedText.length() > 0) {
                        mergedText.append("\n");
                    }
                    mergedText.append(chunkText);
                    log.info(
                        "Chunk {}/{} transcribed. chunkTextLength={}",
                        i + 1,
                        chunkFiles.size(),
                        chunkText.length()
                    );
                } else {
                    log.warn("Chunk {}/{} returned empty text.", i + 1, chunkFiles.size());
                }
            }

            log.info("Merged transcription completed. mergedTextLength={}", mergedText.length());
            return Map.of("text", mergedText.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to split and transcribe uploaded audio.", e);
        } finally {
            deleteDirectoryQuietly(workingDirectory);
        }
    }

    private List<Path> splitIntoM4aChunks(Path inputFile, Path workingDirectory) throws IOException {
        long segmentSeconds = estimateChunkSeconds();
        int attempt = 1;

        while (true) {
            deleteChunkFiles(workingDirectory);
            runFfmpegSplit(inputFile, workingDirectory, segmentSeconds);

            List<Path> chunkFiles = listChunkFiles(workingDirectory);
            if (chunkFiles.isEmpty()) {
                throw new RuntimeException("Audio chunking failed: no chunk files were produced.");
            }

            long largestChunkBytes = largestChunkBytes(chunkFiles);
            log.info(
                "Chunking attempt {} produced {} chunks with segmentSeconds={}, largestChunk={}",
                attempt,
                chunkFiles.size(),
                segmentSeconds,
                formatBytes(largestChunkBytes)
            );

            if (largestChunkBytes <= chunkMaxBytes) {
                return chunkFiles;
            }

            if (segmentSeconds <= 1) {
                throw new RuntimeException(
                    "Audio chunking failed: resulting chunks still exceed max-mb. Lower bitrate or increase max-mb."
                );
            }

            long previous = segmentSeconds;
            segmentSeconds = Math.max(1, segmentSeconds / 2);
            attempt++;
            log.warn(
                "Largest chunk {} exceeds limit {}. Reducing segmentSeconds from {} to {} and retrying chunking.",
                formatBytes(largestChunkBytes),
                formatBytes(chunkMaxBytes),
                previous,
                segmentSeconds
            );
        }
    }

    private void runFfmpegSplit(Path inputFile, Path workingDirectory, long segmentSeconds) throws IOException {
        Path outputPattern = workingDirectory.resolve("chunk-%03d.m4a");
        List<String> command = List.of(
            "ffmpeg",
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            inputFile.toString(),
            "-vn",
            "-ac",
            "1",
            "-ar",
            "16000",
            "-c:a",
            "aac",
            "-b:a",
            chunkAudioBitrateKbps + "k",
            "-f",
            "segment",
            "-segment_format",
            "mp4",
            "-segment_time",
            String.valueOf(segmentSeconds),
            "-reset_timestamps",
            "1",
            outputPattern.toString()
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        String ffmpegOutput;
        try (InputStream inputStream = process.getInputStream()) {
            ffmpegOutput = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Audio chunking failed with ffmpeg: " + ffmpegOutput);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Audio chunking interrupted.", e);
        }
    }

    private long estimateChunkSeconds() {
        double targetBytes = chunkMaxBytes * chunkSizeSafetyFactor;
        double bitsPerSecond = chunkAudioBitrateKbps * 1000.0;
        long seconds = (long) Math.floor((targetBytes * 8.0) / bitsPerSecond);
        long clamped = Math.max(1L, seconds);
        log.info(
            "Estimated chunk duration from size target: chunkMax={}, bitrate={}kbps, safetyFactor={}, seconds={}",
            formatBytes(chunkMaxBytes),
            chunkAudioBitrateKbps,
            chunkSizeSafetyFactor,
            clamped
        );
        return clamped;
    }

    private List<Path> listChunkFiles(Path workingDirectory) throws IOException {
        try (Stream<Path> files = Files.list(workingDirectory)) {
            return files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith("chunk-"))
                .filter(path -> path.getFileName().toString().endsWith(".m4a"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .collect(Collectors.toList());
        }
    }

    private long largestChunkBytes(List<Path> chunkFiles) throws IOException {
        long max = 0;
        for (Path chunkFile : chunkFiles) {
            max = Math.max(max, Files.size(chunkFile));
        }
        return max;
    }

    private void deleteChunkFiles(Path workingDirectory) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(workingDirectory, "chunk-*.m4a")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
    }

    private Map<String, Object> requestGroq(MultipartBodyBuilder bodyBuilder, String apiKey) {
        try {
            return groqWebClient.post()
                .uri("/audio/transcriptions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        } catch (Unauthorized | Forbidden e) {
            log.warn("Groq authentication failed: {}", e.getMessage());
            throw new RuntimeException("Groq authentication failed. Check your API key.", e);
        } catch (BadRequest e) {
            log.warn("Groq bad request: {}", e.getResponseBodyAsString());
            throw new RuntimeException("Groq request invalid: " + e.getResponseBodyAsString(), e);
        } catch (WebClientResponseException e) {
            log.warn("Groq API error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Groq API error: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        }
    }

    private String resolveInputFileName(String originalFileName) {
        if (!StringUtils.hasText(originalFileName)) {
            return "input-audio.bin";
        }
        return "input-" + originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String formatBytes(long bytes) {
        return String.format(Locale.ROOT, "%.2f MB", bytes / (double) BYTES_PER_MB);
    }

    private void deleteDirectoryQuietly(Path directory) {
        if (directory == null) {
            return;
        }

        try (Stream<Path> files = Files.walk(directory)) {
            files
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
        } catch (IOException ignored) {
        }
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = StringUtils.hasText(filename) ? filename : "audio.webm";
        }

        @Override
        public String getFilename() {
            return this.filename;
        }
    }
}
