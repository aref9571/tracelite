import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class IngestionBenchmark {

    private static final String KEY_VARIABLE = "TRACELITE_BENCHMARK_INGESTION_KEY";
    private static final int STACK_FRAMES = 30;

    private final URI eventsUri;
    private final String apiKey;
    private final String application;
    private final int issues;
    private final HttpClient client;

    private IngestionBenchmark(URI eventsUri, String apiKey, String application, int issues) {
        this.eventsUri = eventsUri;
        this.apiKey = apiKey;
        this.application = application;
        this.issues = issues;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        String apiKey = System.getenv(KEY_VARIABLE);
        if (apiKey == null || apiKey.isBlank()) {
            exitWithUsage(KEY_VARIABLE + " must contain an ingestion key");
        }
        String baseUrl = options.getOrDefault("url", "http://localhost:8080");
        String application = requiredOption(options, "application");
        int requests = intOption(options, "requests", 5_000, 1);
        int concurrency = intOption(options, "concurrency", 16, 1);
        int issues = intOption(options, "issues", 50, 1);
        int warmup = intOption(options, "warmup", 500, 0);

        IngestionBenchmark benchmark =
                new IngestionBenchmark(URI.create(baseUrl + "/api/v1/events"), apiKey, application, issues);
        System.out.printf(Locale.ROOT, "Target %s, %d requests, %d concurrent, %d distinct issues, %d warm-up%n",
                baseUrl, requests, concurrency, issues, warmup);
        benchmark.run(warmup, concurrency);
        Result result = benchmark.run(requests, concurrency);
        result.print();
        if (result.failures() > 0) {
            System.exit(1);
        }
    }

    private Result run(int requests, int concurrency) throws InterruptedException {
        long[] latencies = new long[requests];
        Map<Integer, LongAdder> statuses = new ConcurrentHashMap<>();
        AtomicInteger nextRequest = new AtomicInteger();
        long startNanos = System.nanoTime();
        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
            for (int worker = 0; worker < concurrency; worker++) {
                executor.submit(() -> {
                    for (int index = nextRequest.getAndIncrement(); index < requests;
                            index = nextRequest.getAndIncrement()) {
                        long requestStart = System.nanoTime();
                        int status = send(index);
                        latencies[index] = System.nanoTime() - requestStart;
                        statuses.computeIfAbsent(status, key -> new LongAdder()).increment();
                    }
                });
            }
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        Map<Integer, Long> statusCounts = new TreeMap<>();
        statuses.forEach((status, count) -> statusCounts.put(status, count.sum()));
        return new Result(requests, elapsedNanos, latencies, statusCounts);
    }

    private int send(int index) {
        HttpRequest request = HttpRequest.newBuilder(eventsUri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(eventJson(index % issues)))
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception exception) {
            return -1;
        }
    }

    private String eventJson(int issueNumber) {
        String exceptionType = "com.example.benchmark.Failure" + issueNumber + "Exception";
        StringBuilder stackTrace = new StringBuilder(exceptionType).append(": benchmark failure");
        for (int frame = 0; frame < STACK_FRAMES; frame++) {
            stackTrace.append("\\n\\tat com.example.benchmark.Service")
                    .append(issueNumber).append(".step").append(frame)
                    .append("(Service.java:").append(100 + frame).append(')');
        }
        return """
                {"application":"%s","environment":"benchmark","exceptionType":"%s",\
                "message":"benchmark failure","stackTrace":"%s","occurredAt":"%s",\
                "context":{"source":"ingestion-benchmark"}}"""
                .formatted(application, exceptionType, stackTrace, Instant.now());
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                exitWithUsage("unexpected argument " + arg);
            }
            int separator = arg.indexOf('=');
            options.put(arg.substring(2, separator), arg.substring(separator + 1));
        }
        return options;
    }

    private static String requiredOption(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            exitWithUsage("--" + name + " is required");
        }
        return value;
    }

    private static int intOption(Map<String, String> options, String name, int defaultValue, int minimum) {
        String value = options.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum) {
                exitWithUsage("--" + name + " must be at least " + minimum);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            exitWithUsage("--" + name + " must be a whole number");
            return defaultValue;
        }
    }

    private static void exitWithUsage(String problem) {
        System.err.println("Error: " + problem);
        System.err.println("Usage: " + KEY_VARIABLE + "=<key> java scripts/benchmark/IngestionBenchmark.java "
                + "--application=<name> [--url=http://localhost:8080] [--requests=5000] [--concurrency=16] "
                + "[--issues=50] [--warmup=500]");
        System.exit(2);
    }

    private record Result(int requests, long elapsedNanos, long[] latencies, Map<Integer, Long> statusCounts) {

        long failures() {
            return statusCounts.entrySet().stream()
                    .filter(entry -> entry.getKey() != 201)
                    .mapToLong(Map.Entry::getValue)
                    .sum();
        }

        void print() {
            long[] sorted = latencies.clone();
            Arrays.sort(sorted);
            double seconds = elapsedNanos / 1e9;
            System.out.printf(Locale.ROOT, "Responses by status (-1 means no response): %s%n", statusCounts);
            System.out.printf(Locale.ROOT, "Duration: %.2f s%n", seconds);
            System.out.printf(Locale.ROOT, "Throughput: %.0f requests/s%n", requests / seconds);
            System.out.printf(Locale.ROOT, "Latency ms: p50 %.1f, p95 %.1f, p99 %.1f, max %.1f%n",
                    percentileMillis(sorted, 50), percentileMillis(sorted, 95),
                    percentileMillis(sorted, 99), sorted[sorted.length - 1] / 1e6);
        }

        private static double percentileMillis(long[] sorted, int percentile) {
            int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
            return sorted[Math.max(index, 0)] / 1e6;
        }
    }
}
