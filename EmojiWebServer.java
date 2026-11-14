import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Run from the folder that contains index.html:
 *   javac EmojiWebServer.java
 *   java EmojiWebServer            (needs Java 17 or newer)
 *
 * Optional environment variables:
 *   GEMINI_API_KEY  your key (without it the local fallback is used)
 *   GEMINI_MODEL    model name (default below)
 *
 * This file is plain ASCII on purpose, so it compiles the same on Windows, Mac and Linux.
 */
public class EmojiWebServer {

    private static final int PORT = 8080;

    private static final String GEMINI_API_KEY = System.getenv("GEMINI_API_KEY");
    // Models are tried in order. Set GEMINI_MODEL (comma-separated) to change them.
    private static final List<String> GEMINI_MODELS = Arrays.stream(
                    envOrDefault("GEMINI_MODEL", "gemini-3.7-flash,gemini-3.8-flash").split(","))
            .map(String::trim)
            .filter(m -> !m.isEmpty())
            .toList();

    // Total time allowed for all AI attempts together. A person waiting for their voice should not wait long.
    private static final long AI_BUDGET_MS = 6000;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // Must match the labels in index.html.
    private static final Set<String> VALID_EMOJIS = Set.of(
            "Water", "Hungry", "Bathroom", "Tired", "Yes", "No", "Love", "Help");

    private static final Set<String> VALID_EMOTIONS = Set.of(
            "Happy", "Sad", "Angry", "Scared", "Calm", "Excited");

    // Emojis are written as JSON unicode escapes so this file stays ASCII.
    private static final String EMOJI_JSON = "["
            + "{\"emoji\":\"\\ud83d\\udca7\",\"label\":\"Water\"},"      // water drop
            + "{\"emoji\":\"\\ud83c\\udf4e\",\"label\":\"Hungry\"},"     // apple
            + "{\"emoji\":\"\\ud83d\\udebd\",\"label\":\"Bathroom\"},"   // toilet
            + "{\"emoji\":\"\\ud83d\\ude34\",\"label\":\"Tired\"},"      // sleeping face
            + "{\"emoji\":\"\\ud83d\\udc4d\",\"label\":\"Yes\"},"        // thumbs up
            + "{\"emoji\":\"\\ud83d\\udc4e\",\"label\":\"No\"},"         // thumbs down
            + "{\"emoji\":\"\\u2764\\ufe0f\",\"label\":\"Love\"},"       // red heart
            + "{\"emoji\":\"\\ud83c\\udd98\",\"label\":\"Help\"}"        // SOS
            + "]";

    private static final Map<String, String> POSITIVE = Map.of(
            "Water", "want some water",
            "Hungry", "am hungry",
            "Bathroom", "need to use the bathroom",
            "Tired", "am tired",
            "Help", "need help",
            "Love", "love you");

    private static final Map<String, String> NEGATIVE = Map.of(
            "Water", "do not want any water",
            "Hungry", "am not hungry",
            "Bathroom", "do not need the bathroom",
            "Tired", "am not tired",
            "Help", "do not need help");

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    public static void main(String[] args) throws IOException {

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Page: only "/" and "/index.html"
        server.createContext("/", getOnly(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/") && !path.equals("/index.html")) {
                sendText(exchange, 404, "Not found", "text/plain");
                return;
            }
            Path html = Path.of("index.html");
            if (!Files.exists(html)) {
                sendText(exchange, 404, "index.html not found. Run the server from the folder that contains it.",
                        "text/plain");
                return;
            }
            sendBytes(exchange, 200, Files.readAllBytes(html), "text/html; charset=UTF-8");
        }));

        // Emoji cards
        server.createContext("/api/emojis", getOnly(exchange ->
                sendText(exchange, 200, EMOJI_JSON, "application/json")));

        // Sentence generation
        server.createContext("/api/combine", getOnly(exchange -> {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());

            String rawItems = params.getOrDefault("items", "");
            String emotion = params.getOrDefault("emotion", "Calm");

            if (rawItems.length() > 200) {
                sendText(exchange, 400, "Input is too long", "text/plain");
                return;
            }

            List<String> items = Arrays.stream(rawItems.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();

            if (items.isEmpty()) {
                sendText(exchange, 200, "Tap emojis to form a sentence.", "text/plain");
                return;
            }
            if (items.size() > VALID_EMOJIS.size() || !VALID_EMOJIS.containsAll(items)) {
                sendText(exchange, 400, "Invalid emoji selection", "text/plain");
                return;
            }
            if (!VALID_EMOTIONS.contains(emotion)) {
                emotion = "Calm";
            }

            sendText(exchange, 200, generateSentence(items, emotion), "text/plain");
        }));

        // Several people (or one slow AI call) must not block everyone else.
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("Emotion-Aware AI Emoji Server running!");
        System.out.println("Open http://localhost:" + PORT);
        System.out.println("Serving page from: " + Path.of("index.html").toAbsolutePath());
        if (!Files.exists(Path.of("index.html"))) {
            System.out.println("WARNING: index.html was not found in this folder.");
        }
        if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank()) {
            System.out.println("Gemini API key not found. Using local fallback.");
        } else {
            System.out.println("Gemini models (tried in order): " + GEMINI_MODELS);
        }
    }

    // ---------- Request helpers ----------

    // GET only, with errors caught so one bad request never leaves a client hanging.
    private static HttpHandler getOnly(Handler handler) {
        return exchange -> {
            try {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendText(exchange, 405, "Method not allowed", "text/plain");
                    return;
                }
                handler.handle(exchange);
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendText(exchange, 500, "Unable to generate sentence", "text/plain");
                } catch (Exception ignored) {
                    // response already started
                }
            } finally {
                exchange.close();
            }
        };
    }

    private static Map<String, String> parseQueryParams(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                map.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    // ---------- Sentence generation ----------

    private static String generateSentence(List<String> items, String emotion) {
        if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank()) {
            return localFallback(items, emotion);
        }

        String prompt = "You are an AAC communication assistant. "
                + "Help a child communicate their needs. "
                + "Mood: " + emotion + ". "
                + "Selected concepts: " + String.join(", ", items) + ". "
                + "Create ONE short, natural, first-person sentence. "
                + "Use simple language. "
                + "Interpret No as a refusal or negation where appropriate. "
                + "Do not invent additional needs. "
                + "Do not use quotation marks. "
                + "Output only the sentence.";

        // maxOutputTokens is generous because some models spend part of it on internal reasoning.
        String body = "{\"contents\":[{\"parts\":[{\"text\":\"" + escapeJson(prompt) + "\"}]}],"
                + "\"generationConfig\":{\"temperature\":0.4,\"maxOutputTokens\":400}}";

        long deadline = System.nanoTime() + AI_BUDGET_MS * 1_000_000L;

        for (String model : GEMINI_MODELS) {
            long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (leftMs < 500) {
                break; // out of time: use the local sentence
            }

            try {
                String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                        + model + ":generateContent";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(Math.min(leftMs, 3500)))
                        .header("Content-Type", "application/json")
                        .header("x-goog-api-key", GEMINI_API_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response =
                        HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status == 200) {
                    String result = cleanSentence(extractText(response.body()));
                    if (!result.isBlank()) {
                        System.out.println("Gemini " + model + " [" + emotion + "]: " + result);
                        return result;
                    }
                    System.err.println("Gemini " + model + " returned no text. Trying next model.");
                    continue;
                }

                String snippet = response.body() == null ? "" : response.body();
                if (snippet.length() > 300) {
                    snippet = snippet.substring(0, 300);
                }
                System.err.println("Gemini " + model + " error " + status + ": " + snippet);

                // 400, 401 and 403 mean a key or request problem: other models would fail the same way.
                if (status == 400 || status == 401 || status == 403) {
                    break;
                }
                // 404, 429, 500, 503 and so on: try the next model.

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Gemini request interrupted.");
                break;
            } catch (Exception e) {
                // Timeout or connection problem: try the next model.
                System.err.println("Gemini " + model + " failed: " + e.getMessage());
            }
        }

        return localFallback(items, emotion);
    }

    // Keep only the first line, drop wrapping quotes, and cap the length.
    private static String cleanSentence(String text) {
        if (text == null) {
            return "";
        }
        String s = text.strip();
        int newline = s.indexOf('\n');
        if (newline >= 0) {
            s = s.substring(0, newline).strip();
        }
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            boolean straight = first == '"' && last == '"';
            boolean curly = first == '\u201C' && last == '\u201D';
            if (straight || curly) {
                s = s.substring(1, s.length() - 1).strip();
            }
        }
        if (s.length() > 200) {
            s = s.substring(0, 200).strip();
        }
        return s;
    }

    private static String escapeJson(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    // Joins every "text" value in the reply (a reply can contain several parts).
    private static String extractText(String json) {
        Pattern pattern = Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = pattern.matcher(json);
        StringBuilder all = new StringBuilder();
        while (matcher.find()) {
            all.append(decodeJsonString(matcher.group(1)));
        }
        return all.toString().trim();
    }

    private static String decodeJsonString(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        try {
                            out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append("\\u");
                        }
                    } else {
                        out.append("\\u");
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    // Used when there is no API key or the AI is unavailable. Mirrors the fallback in index.html.
    private static String localFallback(List<String> items, String emotion) {
        boolean no = items.contains("No");
        Map<String, String> phrases = no ? NEGATIVE : POSITIVE;

        List<String> clauses = new ArrayList<>();
        for (String item : items) {
            if (item.equals("Yes") || item.equals("No")) {
                continue;
            }
            String clause = phrases.get(item);
            if (clause != null) {
                clauses.add(clause);
            }
        }

        if (clauses.isEmpty()) {
            return no ? "No, thank you." : "Yes, please!";
        }

        String end = (emotion.equals("Happy") || emotion.equals("Excited")) ? "!" : ".";
        return "I " + String.join(" and I ", clauses) + end;
    }

    // ---------- Responses ----------

    private static void sendText(HttpExchange exchange, int status, String text, String type) throws IOException {
        sendBytes(exchange, status, text.getBytes(StandardCharsets.UTF_8), type + "; charset=UTF-8");
    }

    private static void sendBytes(HttpExchange exchange, int status, byte[] bytes, String contentType)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
