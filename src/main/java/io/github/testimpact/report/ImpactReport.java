package io.github.testimpact.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and writes the JSON report described in spec §10.2.
 *
 * Schema (intentionally close to the spec's example):
 *   buildHash, baseline, changedClasses[], selectedTests, totalTests,
 *   reductionPct, fallbackMode, mapAge, mapEntries, durationMs.
 */
public final class ImpactReport {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public String buildHash;
    public String baseline;
    public List<String> changedClasses;
    public int selectedTests;
    public int totalTests;
    public double reductionPct;
    public boolean fallbackMode;
    public int mapAge;
    public int mapEntries;
    public long durationMs;

    public void writeTo(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("buildHash", buildHash);
        json.put("baseline", baseline);
        json.put("changedClasses", changedClasses);
        json.put("selectedTests", selectedTests);
        json.put("totalTests", totalTests);
        json.put("reductionPct", round1(reductionPct));
        json.put("fallbackMode", fallbackMode);
        json.put("mapAge", mapAge);
        json.put("mapEntries", mapEntries);
        json.put("durationMs", durationMs);
        Files.write(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(json));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
