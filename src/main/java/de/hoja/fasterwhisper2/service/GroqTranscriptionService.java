package de.hoja.fasterwhisper2.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    private final WebClient groqWebClient;
    private final String model;
    private final long chunkMaxBytes;
    private final int chunkSeconds;
    private final int chunkAudioBitrateKbps;

    public GroqTranscriptionService(
        WebClient groqWebClient,
        @Value("${groq.model:whisper-large-v3-turbo}") String model,
        @Value("${app.transcription.chunk.max-mb:20}") int chunkMaxMb,
        @Value("${app.transcription.chunk.seconds:50}") int chunkSeconds,
        @Value("${app.transcription.chunk.audio-bitrate-kbps:64}") int chunkAudioBitrateKbps
    ) {
        this.groqWebClient = groqWebClient;
        this.model = model;
        this.chunkMaxBytes = chunkMaxMb * 1024L * 1024L;
        this.chunkSeconds = chunkSeconds;
        this.chunkAudioBitrateKbps = chunkAudioBitrateKbps;
    }

    public Map<String, Object> transcribe(MultipartFile file, String prompt, String language, String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("Missing Groq API key");
        }

        if (file.getSize() <= chunkMaxBytes) {
            return transcribeSingle(file, prompt, language, apiKey);
        }

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

            return requestGroq(bodyBuilder, apiKey);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded audio file.", e);
        }
    }

    private Map<String, Object> transcribeChunked(MultipartFile file, String prompt, String language, String apiKey) {
        Path workingDirectory = null;
        try {
            workingDirectory = Files.createTempDirectory("groq-audio-chunks-");
            Path inputFile = workingDirectory.resolve("input-audio");
            file.transferTo(inputFile.toFile());

            splitIntoM4aChunks(inputFile, workingDirectory);

            List<Path> chunkFiles;
            try (Stream<Path> files = Files.list(workingDirectory)) {
                chunkFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("chunk-"))
                    .filter(path -> path.getFileName().toString().endsWith(".m4a"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .collect(Collectors.toList());
            }

            if (chunkFiles.isEmpty()) {
                throw new RuntimeException("Audio chunking failed: no chunk files were produced.");
            }

            StringBuilder mergedText = new StringBuilder();
            for (Path chunkFile : chunkFiles) {
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
                    if (mergedText.length() > 0) {
                        mergedText.append("\n");
                    }
                    mergedText.append(text.toString().trim());
                }
            }

            return Map.of("text", mergedText.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to split and transcribe uploaded audio.", e);
        } finally {
            deleteDirectoryQuietly(workingDirectory);
        }
    }

    private void splitIntoM4aChunks(Path inputFile, Path workingDirectory) throws IOException {
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
            String.valueOf(chunkSeconds),
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
            throw new RuntimeException("Groq authentication failed. Check your API key.", e);
        } catch (BadRequest e) {
            throw new RuntimeException("Groq request invalid: " + e.getResponseBodyAsString(), e);
        } catch (WebClientResponseException e) {
            throw new RuntimeException("Groq API error: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        }
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
