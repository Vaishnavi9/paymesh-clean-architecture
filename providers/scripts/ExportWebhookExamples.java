import com.paymesh.provider.application.WebhookDelivery;
import org.bson.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Export persisted demo events through the real production-code mapper.
 * These are serialised examples, not a claim of HTTP delivery to a receiver.
 * Run with provider-core/target/classes and the MongoDB bson JAR on the classpath.
 */
public class ExportWebhookExamples {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Provide demo-results.json and output.json");
        Document input = Document.parse("{\"cases\":" + Files.readString(Path.of(args[0])) + "}");
        List<Document> examples = new ArrayList<>();
        for (Document entry : input.getList("cases", Document.class)) {
            for (Document job : entry.getList("webhookJobs", Document.class)) {
                Document event = job.get("payload", Document.class);
                Document payment = event.get("payment", Document.class);
                // HTTP JSON loses BSON int64 information for small numbers. Restore that type.
                payment.put("amount", ((Number) payment.get("amount")).longValue());
                String rawBody = WebhookDelivery.payload(job).toJson();
                String timestamp = Long.toString(Instant.parse(event.getString("occurredAt")).getEpochSecond());
                Document headers = new Document("X-Simulator-Event-Id", job.getString("_id"))
                        .append("X-Simulator-Timestamp", timestamp)
                        .append("X-Simulator-Signature", WebhookDelivery.sign("local-webhook-secret", timestamp + "." + rawBody));
                examples.add(new Document("provider", entry.getString("provider"))
                        .append("scenario", entry.getString("case"))
                        .append("source", "Persisted local event serialised by WebhookDelivery.payload; not an HTTP receipt")
                        .append("headers", headers).append("rawBody", rawBody)
                        .append("payload", Document.parse(rawBody)));
            }
        }
        String json = new Document("examples", examples).toJson();
        Files.writeString(Path.of(args[1]), json + "\n");
        System.out.println("Exported " + examples.size() + " examples using the actual Java webhook mapper.");
    }
}
