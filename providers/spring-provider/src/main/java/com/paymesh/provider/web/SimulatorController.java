package com.paymesh.provider.web;

import com.paymesh.provider.ProviderSettings;
import com.paymesh.provider.application.PaymentService;
import com.paymesh.provider.persistence.ProviderStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.bson.Document;
import org.springframework.web.bind.annotation.*;
import com.mongodb.client.model.ReplaceOptions;

import java.util.ArrayList;
import java.util.List;
import static com.mongodb.client.model.Filters.eq;

@RestController
public class SimulatorController {
    private final ProviderStore store;
    private final PaymentService payments;
    private final ProviderSettings settings;
    public SimulatorController(ProviderStore store, PaymentService payments, ProviderSettings settings) {
        this.store = store; this.payments = payments; this.settings = settings;
    }
    @GetMapping("/health")
    public Document health() {
        store.ready();
        return new Document("status", "UP").append("provider", settings.kind()).append("simulation", true);
    }
    public record Scenario(@Min(0) @Max(10) int transientFailures, boolean loseResponse,
                           @Min(0) @Max(30000) long delayMs) {}
    @PutMapping("/__sim/scenarios/{reference}")
    public Document scenario(@PathVariable String reference, @Valid @RequestBody Scenario scenario) {
        if (reference.length() > 100) throw new IllegalArgumentException("Reference is too long");
        Document doc = new Document("_id", reference).append("transientFailures", scenario.transientFailures())
                .append("loseResponse", scenario.loseResponse()).append("delayMs", scenario.delayMs());
        store.collection("scenarios").replaceOne(eq("_id", reference), doc, new ReplaceOptions().upsert(true));
        return doc;
    }
    @GetMapping("/__sim/accounts")
    public List<Document> accounts() { return store.collection("accounts").find().into(new ArrayList<>()); }
    @GetMapping("/__sim/payments/{id}")
    public Document payment(@PathVariable String id) { return payments.get(id); }
    @GetMapping("/__sim/payments/{id}/ledger")
    public List<Document> ledger(@PathVariable String id) {
        payments.get(id);
        return store.collection("ledger").find(eq("paymentId", id)).into(new ArrayList<>());
    }
    @GetMapping("/__sim/payments/{id}/webhooks")
    public List<Document> webhooks(@PathVariable String id) {
        payments.get(id);
        return store.collection("webhooks").find(eq("paymentId", id)).into(new ArrayList<>());
    }
}
