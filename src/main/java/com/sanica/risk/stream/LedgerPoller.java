package com.sanica.risk.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanica.risk.domain.Channel;
import com.sanica.risk.domain.TransactionEvent;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge to the payments-ledger service (project 1) for deployments where the ledger does not publish to the
 * stream itself: it polls {@code GET /transactions} and forwards transactions it has not seen yet, oldest first.
 * These are post-booking checks (flag for review, freeze the account); the pre-authorisation path is
 * {@code POST /v1/score}, which the ledger calls before it books a transfer.
 */
public final class LedgerPoller implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LedgerPoller.class);
    private static final int PAGE = 200;
    private static final int REMEMBERED = 10_000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI transactionsUri;
    private final ObjectMapper mapper;
    private final Consumer<TransactionEvent> sink;
    private final SequencedSet<String> seen = new LinkedHashSet<>();
    private ScheduledExecutorService scheduler;

    public LedgerPoller(String ledgerUrl, ObjectMapper mapper, Consumer<TransactionEvent> sink) {
        this.transactionsUri = URI.create(ledgerUrl.replaceAll("/+$", "") + "/transactions?limit=" + PAGE);
        this.mapper = mapper;
        this.sink = sink;
    }

    /** The ledger's transaction representation; only the fields risk scoring needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LedgerTransaction(String id, String type, String sourceAccountId, String destinationAccountId,
                             String sourceAmount, String sourceCurrency, OffsetDateTime createdAt) {
    }

    public LedgerPoller start(Duration interval) {
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("ledger-poller").factory());
        scheduler.scheduleWithFixedDelay(this::safePoll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Polling ledger at {} every {} ms", transactionsUri, interval.toMillis());
        return this;
    }

    private void safePoll() {
        try {
            pollOnce();
        } catch (Exception e) {
            log.warn("Ledger poll failed: {}", e.getMessage());
        }
    }

    /** Fetches the latest page and forwards unseen transactions. Returns how many were forwarded. */
    public synchronized int pollOnce() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(transactionsUri).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ledger returned HTTP " + response.statusCode());
        }
        List<LedgerTransaction> page = List.of(mapper.readValue(response.body(), LedgerTransaction[].class));
        List<LedgerTransaction> fresh = new ArrayList<>();
        for (LedgerTransaction tx : page.reversed()) {
            if (!seen.contains(tx.id())) {
                fresh.add(tx);
            }
        }
        for (LedgerTransaction tx : fresh) {
            sink.accept(toEvent(tx));
            seen.add(tx.id());
            if (seen.size() > REMEMBERED) {
                seen.removeFirst();
            }
        }
        return fresh.size();
    }

    static TransactionEvent toEvent(LedgerTransaction tx) {
        boolean deposit = "DEPOSIT".equals(tx.type());
        // Deposits are money arriving in the destination account; transfers are spent by the source account.
        return new TransactionEvent(
                tx.id(),
                deposit ? tx.destinationAccountId() : tx.sourceAccountId(),
                deposit ? tx.sourceAccountId() : tx.destinationAccountId(),
                new BigDecimal(tx.sourceAmount()),
                tx.sourceCurrency(),
                deposit ? Channel.DEPOSIT : Channel.TRANSFER,
                null,
                null,
                tx.createdAt().toInstant());
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
