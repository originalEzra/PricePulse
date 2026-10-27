package com.ezra.pricepulse.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.testcontainers.DockerClientFactory;

final class EvidenceReport {
    private EvidenceReport() { }

    static Map<String, Object> environment() throws Exception {
        var values = new LinkedHashMap<String, Object>();
        values.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        values.put("architecture", System.getProperty("os.arch"));
        values.put("javaRuntime", System.getProperty("java.runtime.version"));
        values.put("jvm", System.getProperty("java.vm.name"));
        values.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        values.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        var docker = DockerClientFactory.instance().client();
        values.put("dockerVersion", docker.versionCmd().exec().getVersion());
        values.put("dockerMemoryBytes", docker.infoCmd().exec().getMemTotal());
        values.put("mysqlImage", "mysql:8.4.7");
        values.put("redisImage", "redis:7.4.6-alpine");
        values.put("rocketMqImage", "apache/rocketmq:5.3.2");
        values.put("baseGitCommit", git("rev-parse", "HEAD"));
        values.put("worktreeDirtyAtRun", !git("status", "--porcelain").isBlank());
        values.put("sourceSha256", sourceSha256());
        return values;
    }

    static Map<String, Object> stats(List<Double> milliseconds) {
        if (milliseconds.isEmpty()) throw new IllegalArgumentException("No samples");
        List<Double> sorted = milliseconds.stream().sorted().toList();
        return Map.of("samples", sorted.size(), "meanMs",
                sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                "minMs", sorted.getFirst(), "p50Ms", percentile(sorted, .50),
                "p95Ms", percentile(sorted, .95), "p99Ms", percentile(sorted, .99),
                "maxMs", sorted.getLast(), "samplesMs", List.copyOf(milliseconds));
    }

    private static double percentile(List<Double> sorted, double quantile) {
        return sorted.get((int) Math.ceil(quantile * sorted.size()) - 1);
    }

    static void write(ObjectMapper json, Map<String, Object> result, String status) throws Exception {
        result.put("status", status);
        result.put("updatedAt", Instant.now().toString());
        Path directory = Path.of("target", "performance");
        Files.createDirectories(directory);
        String raw = json.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        String markdown = markdown(json.valueToTree(result));
        String name = "run-" + result.get("runId");
        Files.writeString(directory.resolve(name + ".json"), raw + "\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(name + ".md"), markdown, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("latest.json"), raw + "\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("latest.md"), markdown, StandardCharsets.UTF_8);
        System.out.println("PERF report " + status + ": " + directory.resolve(name + ".md").toAbsolutePath());
    }

    private static String markdown(JsonNode root) {
        var text = new StringBuilder("# PricePulse performance evidence\n\n");
        text.append("Run: ").append(root.path("runId").asText()).append("\n\n");
        text.append("Status: ").append(root.path("status").asText()).append("\n\n");
        text.append("Started (UTC): ").append(root.path("startedAt").asText()).append("\n\n");
        var environment = root.path("environment");
        text.append("Environment: ").append(environment.path("os").asText()).append(" / ")
                .append(environment.path("architecture").asText()).append(" / Java ")
                .append(environment.path("javaRuntime").asText()).append(" / ")
                .append(environment.path("availableProcessors").asInt()).append(" processors\n\n");
        text.append("Source SHA-256: ").append(environment.path("sourceSha256").asText()).append("\n\n");
        text.append("See adjacent JSON for exact configuration, JVM/Docker limits and raw measurements.\n\n");
        text.append("## Candidate lookup (milliseconds)\n\n");
        text.append("| Rules | Full fetch + Java scan P95 | Redis lookup P95 | Redis P99 | Mean candidates | Mismatches |\n");
        text.append("| --- | --- | --- | --- | --- | --- |\n");
        for (var row : root.path("matching")) {
            text.append(String.format(Locale.ROOT, "| %d | %.3f | %.3f | %.3f | %.2f | %d |\n",
                    row.path("rules").asInt(), row.path("fullFetchScan").path("p95Ms").asDouble(),
                    row.path("redisLookup").path("p95Ms").asDouble(),
                    row.path("redisLookup").path("p99Ms").asDouble(),
                    row.path("meanCandidates").asDouble(), row.path("candidateSetMismatches").asInt()));
        }
        text.append("\nPaired operations alternate order, use the same stock lock and validate equal candidate sets. ")
                .append("Selection only: excludes lock wait, commit and business writes. ")
                .append("Baseline includes reading all indexable rows from MySQL and Java filtering. ")
                .append("Three cold rebuild samples per scale are retained in JSON; they are not a cold-start SLA.\n\n");
        text.append("## Authenticated HTTP workload\n\n");
        text.append("| Actors/stocks | Measured requests | Requests/s | P95 ms | P99 ms | Errors |\n");
        text.append("| --- | --- | --- | --- | --- | --- |\n");
        for (var row : root.path("http")) {
            text.append(String.format(Locale.ROOT, "| %d | %d | %.2f | %.3f | %.3f | %d |\n",
                    row.path("actors").asInt(), row.path("measuredRequests").asInt(),
                    row.path("requestsPerSecond").asDouble(), row.path("latency").path("p95Ms").asDouble(),
                    row.path("latency").path("p99Ms").asDouble(), row.path("errors").asInt()));
        }
        text.append("\nClosed-loop localhost HTTP replay with a real JWT, 1,000 rules/stock and no crossings. ")
                .append("One ordered stream per stock, 100 measured requests/actor after five warmups. ")
                .append("This is an observed workload, not maximum capacity or concurrent-user capacity.\n\n");
        text.append("## Notification burst and recovery\n\n");
        for (String key : List.of("notificationBurst", "publicationFailure", "publisherLeaseRecovery", "redisRecovery")) {
            if (!root.has(key)) continue;
            text.append("### ").append(key).append("\n\n");
            root.path(key).properties().forEach(entry ->
                    text.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n"));
            text.append("\n");
        }
        text.append("Notification end-to-end latency includes intentional consumer suspension and publication time. ")
                .append("Backlog is undelivered business jobs, not a broker-offset measurement. ")
                .append("Drain time is sampled every 50 ms and includes resume scheduling.\n\n");
        text.append("No shared container is stopped. Send errors and the post-send crash window are injected; ")
                .append("only the disposable Redis is actually stopped/restarted. ")
                .append("Test lease/backoff are 1 s / 100 ms, unlike production defaults of 30 s / 2 s. ")
                .append("Publisher scheduling is manual during experiments; normal polling delay is excluded. ")
                .append("Percentiles use nearest-rank samples from one laptop run, not a production SLA.\n");
        return text.toString();
    }

    private static String git(String... arguments) throws Exception {
        var command = new ArrayList<String>(List.of("git"));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) throw new IllegalStateException("Cannot read source revision");
        return output;
    }

    private static String sourceSha256() throws Exception {
        var files = new ArrayList<Path>();
        for (Path root : List.of(Path.of("src/main"), Path.of("src/test/java/com/ezra/pricepulse/performance"))) {
            try (var walk = Files.walk(root)) { walk.filter(Files::isRegularFile).forEach(files::add); }
        }
        files.add(Path.of("pom.xml"));
        files.sort(Comparator.comparing(Path::toString));
        var digest = MessageDigest.getInstance("SHA-256");
        for (Path file : files) {
            digest.update(file.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
