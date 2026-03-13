package de.hoja.fasterwhisper2.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import de.hoja.fasterwhisper2.service.GroqTranscriptionService;

@RestController
@RequestMapping("/api")
public class TranscriptionController {

    private final GroqTranscriptionService groqTranscriptionService;

    public TranscriptionController(GroqTranscriptionService groqTranscriptionService) {
        this.groqTranscriptionService = groqTranscriptionService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "faster-whisper2"));
    }

    @PostMapping("/transcriptions")
    public ResponseEntity<Map<String, Object>> transcribe(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "prompt", required = false) String prompt,
        @RequestParam(value = "language", required = false) String language,
        @RequestHeader(value = "X-Groq-Api-Key", required = false) String apiKey
    ) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No audio file uploaded."));
        }

        if (!StringUtils.hasText(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Missing Groq API key. Set it in the UI first."));
        }

        Map<String, Object> response = groqTranscriptionService.transcribe(file, prompt, language, apiKey);
        return ResponseEntity.ok(response);
    }
}
