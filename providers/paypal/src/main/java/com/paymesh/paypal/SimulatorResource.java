package com.paymesh.paypal;

import com.paymesh.provider.application.PaymentService;
import com.paymesh.provider.persistence.ProviderStore;
import com.mongodb.client.model.ReplaceOptions;
import io.smallrye.common.annotation.Blocking;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.bson.Document;
import java.util.ArrayList;
import java.util.List;
import static com.mongodb.client.model.Filters.eq;

@Path("/") @Blocking
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class SimulatorResource {
    private final ProviderStore store;
    private final PaymentService payments;
    public SimulatorResource(ProviderStore store, PaymentService payments) { this.store = store; this.payments = payments; }
    @GET @Path("health")
    public Document health() {
        store.ready();
        return new Document("status", "UP").append("provider", "paypal").append("framework", "quarkus").append("simulation", true);
    }
    public record Scenario(@Min(0) @Max(10) int transientFailures, boolean loseResponse,
                           @Min(0) @Max(30000) long delayMs) {}
    @PUT @Path("__sim/scenarios/{reference}")
    public Document scenario(@PathParam("reference") String reference, @NotNull @Valid Scenario scenario) {
        if (reference.isBlank() || reference.length() > 100) throw new IllegalArgumentException("Invalid reference");
        Document doc = new Document("_id", reference).append("transientFailures", scenario.transientFailures())
                .append("loseResponse", scenario.loseResponse()).append("delayMs", scenario.delayMs());
        store.collection("scenarios").replaceOne(eq("_id", reference), doc, new ReplaceOptions().upsert(true));
        return doc;
    }
    @GET @Path("__sim/accounts")
    public List<Document> accounts() { return store.collection("accounts").find().into(new ArrayList<>()); }
    @GET @Path("__sim/payments/{id}")
    public Document payment(@PathParam("id") String id) { return payments.get(id); }
    @GET @Path("__sim/payments/{id}/ledger")
    public List<Document> ledger(@PathParam("id") String id) {
        payments.get(id);
        return store.collection("ledger").find(eq("paymentId", id)).into(new ArrayList<>());
    }
    @GET @Path("__sim/payments/{id}/webhooks")
    public List<Document> webhooks(@PathParam("id") String id) {
        payments.get(id);
        return store.collection("webhooks").find(eq("paymentId", id)).into(new ArrayList<>());
    }
}
