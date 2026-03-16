# faster-whisper2

Spring-Boot Speech-to-Text-Proxy fuer Groq mit einfacher Browser-Oberflaeche.

Du kannst Audio aufnehmen oder hochladen, an Groq Whisper senden und den transkribierten Text direkt im Browser weiterverwenden.

## BYOK (Bring Your Own Key) - Wichtig

Diese App ist eine BYOK-App. Du brauchst deinen eigenen Groq API Key.

Einen kostenlosen Groq API Key bekommst du hier:

- https://console.groq.com/keys

## Features

- Spring Boot 3 API (`/api/transcriptions`) fuer Groq Whisper
- Statische Web-UI fuer Aufnahme, Upload, Kopieren und Text-Download
- API Key pro Request ueber `X-Groq-Api-Key` (im Browser LocalStorage gespeichert)
- Automatisches serverseitiges Chunking grosser Uploads mit ffmpeg (Ausgabe als m4a)
- Docker Multi-Stage Build (Maven Build + JRE Runtime)

## Tech-Stack

- Java 17
- Spring Boot 3.4.x
- Maven
- WebClient (Spring WebFlux)
- ffmpeg (Runtime-Abhaengigkeit fuers Chunking)

## Projektstruktur

- `src/main/java/de/hoja/fasterwhisper2/controller/TranscriptionController.java`: API-Endpunkte
- `src/main/java/de/hoja/fasterwhisper2/service/GroqTranscriptionService.java`: Groq-Integration + Chunking
- `src/main/resources/static/index.html`: Browser-UI
- `src/main/resources/application.properties`: Laufzeit-Konfiguration
- `Dockerfile`: Container Build/Runtime

## API

### Health

`GET /api/health`

Antwort:

```json
{
  "status": "UP",
  "service": "faster-whisper2"
}
```

### Transkribieren

`POST /api/transcriptions` (multipart/form-data)

Header:

- `X-Groq-Api-Key: gsk_...`

Form-Felder:

- `file` (required): Audiodatei
- `prompt` (optional)
- `language` (optional)

Antwort:

```json
{
  "text": "..."
}
```

## Konfiguration

Standardwerte in `application.properties`:

```properties
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=100MB

app.transcription.chunk.max-mb=20
app.transcription.chunk.audio-bitrate-kbps=64
app.transcription.chunk.size-safety-factor=0.90
```

Chunking-Verhalten:

- Dateien `<= app.transcription.chunk.max-mb` werden in einem Request gesendet.
- Dateien `> app.transcription.chunk.max-mb` werden mit ffmpeg in m4a-Chunks transkodiert/gesplittet.
- Chunks werden sequenziell an Groq geschickt und zu einem finalen `text` zusammengefuehrt.

## Lokal entwickeln

Voraussetzungen:

- Java 17
- Maven 3.9+
- ffmpeg im PATH (empfohlen fuer gleiche Bedingungen wie in Docker)

Starten:

```bash
mvn spring-boot:run
```

App-URL:

- `http://localhost:8080`

Jar bauen:

```bash
mvn -DskipTests package
```

## Docker

Image bauen:

```bash
docker build -t faster-whisper2 .
```

Container starten:

```bash
docker run --rm -p 8080:8080 faster-whisper2
```

## Hinweise

- Groq API Keys werden nicht serverseitig gespeichert; die UI speichert sie nur im Browser (LocalStorage).
- Sehr lange Transkriptionen koennen je nach Tarif/Modell in Groq Rate-Limits laufen.
- Fuer Produktion sind zusaetzliche Schutzmechanismen sinnvoll (Auth, API Rate-Limit, Monitoring, Logging).

## Lizenz

Derzeit ist keine `LICENSE` enthalten. Fuer ein oeffentliches Repository solltest du eine passende Lizenzdatei ergaenzen.
