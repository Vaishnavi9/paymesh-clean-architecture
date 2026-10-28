package com.paymesh.provider.persistence;

import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.*;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import java.util.List;
import java.util.function.Function;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.setOnInsert;

public class ProviderStore {
    private final MongoClient client;
    private final MongoDatabase db;
    public ProviderStore(MongoClient client, MongoDatabase db) {
        this.client = client;
        this.db = db;
    }
    public MongoCollection<Document> collection(String name) { return db.getCollection(name); }

        public void initialise() {
        // Create collections/indexes before any multi-document transaction.
        for (String name : List.of("payments", "accounts", "idempotency", "ledger", "webhooks", "scenarios"))
            collection(name).createIndex(Indexes.ascending("createdAt"));
        collection("payments").createIndex(Indexes.compoundIndex(
                Indexes.ascending("state"), Indexes.ascending("nextAttemptAt")));
        collection("webhooks").createIndex(Indexes.compoundIndex(
                Indexes.ascending("state"), Indexes.ascending("nextAttemptAt")));
        collection("ledger").createIndex(Indexes.ascending("operation"), new IndexOptions().unique(true));
        seed("pm_funded", "EUR", 100_000L, false);
        seed("pm_low", "EUR", 100L, false);
        seed("pm_blocked", "EUR", 100_000L, true);
        seed("pm_usd", "USD", 100_000L, false);
        seed("merchant_EUR", "EUR", 0L, false);
        seed("merchant_USD", "USD", 0L, false);
    }

    private void seed(String id, String currency, long balance, boolean blocked) {
        Document account = new Document("currency", currency).append("available", balance)
                .append("reserved", 0L).append("blocked", blocked);
        collection("accounts").updateOne(eq("_id", id), new Document("$setOnInsert", account),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    public <T> T transaction(Function<ClientSession, T> operation) {
        try (ClientSession session = client.startSession()) {
            // The driver retries transient transaction/commit errors. The callback must contain no HTTP calls.
            return session.withTransaction(() -> operation.apply(session),
                    TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT)
                            .writeConcern(WriteConcern.MAJORITY).build());
        }
    }

    public Document payment(String id) { return collection("payments").find(eq("_id", id)).first(); }
    public boolean ready() {
        return db.runCommand(new Document("ping", 1)).getDouble("ok") == 1.0;
    }
}
