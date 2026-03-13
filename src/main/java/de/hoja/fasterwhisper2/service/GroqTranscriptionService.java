package de.hoja.fasterwhisper2.service;

import java.io.IOException;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException.BadRequest;
import org.springframework.web.reactive.function.client.WebClientResponseException.Unauthorized;
import org.springframework.web.reactive.function.client.WebClientResponseException.Forbidden;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.http.client.MultipartBodyBuilder;

@Service
public class GroqTranscriptionService {

    private final WebClient groqWebClient;
    private final String model;

    public GroqTranscriptionService(
        WebClient groqWebClient,
        @Value("${groq.model:whisper-large-v3-turbo}") String model
    ) {
        this.groqWebClient = groqWebClient;
        this.model = model;
    }

    public Map<String, Object> transcribe(MultipartFile file, String prompt, String language, String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("Missing Groq API key");
        }

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
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded audio file.", e);
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
