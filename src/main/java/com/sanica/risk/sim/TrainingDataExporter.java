package com.sanica.risk.sim;

import com.sanica.risk.features.Features;
import com.sanica.risk.features.InMemoryVelocityStore;
import com.sanica.risk.features.VelocityStore;
import com.sanica.risk.sim.TrafficSimulator.LabeledEvent;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Builds the model's training set by replaying simulated traffic through the production feature code
 * ({@link InMemoryVelocityStore} and {@link Features}), in time order, exactly as the live service would see it.
 *
 * <p>Usage: {@code ./gradlew exportTrainingData} writes {@code ml/data/features.csv}.
 */
public final class TrainingDataExporter {

    private TrainingDataExporter() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "ml/data/features.csv");
        int accounts = args.length > 1 ? Integer.parseInt(args[1]) : 3_000;
        long rows = export(out, accounts, 42L);
        System.out.printf("Wrote %d rows to %s%n", rows, out.toAbsolutePath());
    }

    public static long export(Path out, int accounts, long seed) throws IOException {
        List<LabeledEvent> events = new TrafficSimulator(seed)
                .generate(accounts, Instant.parse("2026-09-01T00:00:00Z"), Duration.ofDays(14), 0.08);
        VelocityStore store = new InMemoryVelocityStore();
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(out)) {
            writer.write(String.join(",", Features.MODEL_INPUTS) + ",is_fraud,scenario\n");
            for (LabeledEvent labeled : events) {
                Features features = Features.of(labeled.event(), store.record(labeled.event()));
                StringBuilder line = new StringBuilder();
                for (float value : features.modelInput()) {
                    line.append(String.format(Locale.ROOT, "%.6f", value)).append(',');
                }
                line.append(labeled.fraud() ? 1 : 0).append(',').append(labeled.scenario()).append('\n');
                writer.write(line.toString());
            }
        }
        return events.size();
    }
}
