package com.paymesh.provider.web;

import org.bson.Document;
import java.math.BigDecimal;
import java.util.List;

public final class ProviderViews {
    private ProviderViews() {}
    public static Document stripe(Document p) {
        String state = switch (p.getString("state")) {
            case "REQUIRES_PAYMENT_METHOD", "DECLINED" -> "requires_payment_method";
            case "CREATED" -> "requires_confirmation";
            case "AUTHORISED" -> "requires_capture";
            case "CAPTURED" -> "succeeded";
            default -> "processing";
        };
        Document body = new Document("id", p.getString("_id")).append("object", "payment_intent")
                .append("amount", p.getLong("amount")).append("currency", p.getString("currency").toLowerCase())
                .append("status", state).append("payment_method", p.getString("token"))
                .append("capture_method", p.getBoolean("manualCapture") ? "manual" : "automatic")
                .append("amount_received", state.equals("succeeded") ? p.getLong("amount") : 0L)
                .append("amount_capturable", state.equals("requires_capture") ? p.getLong("amount") : 0L)
                .append("metadata", new Document("order_reference", p.getString("reference")));
        if ("DECLINED".equals(p.getString("state")))
            body.put("last_payment_error", new Document("type", "card_error")
                    .append("code", "card_declined").append("decline_code", p.getString("declineReason")));
        return body;
    }
    public static Document adyen(Document p) {
        String result = switch (p.getString("state")) {
            case "AUTHORISED", "CAPTURE_PROCESSING", "CAPTURED" -> "Authorised";
            case "DECLINED" -> "Refused";
            default -> "Pending";
        };
        Document body = new Document("pspReference", p.getString("_id"))
                .append("merchantReference", p.getString("reference")).append("resultCode", result);
        if ("DECLINED".equals(p.getString("state"))) body.put("refusalReason", p.getString("declineReason"));
        return body;
    }
    public static Document paypal(Document p) {
        String status = switch (p.getString("state")) {
            case "APPROVED", "AUTHORISED", "DECLINED" -> "APPROVED";
            case "CAPTURED" -> "COMPLETED";
            default -> "CREATED";
        };
        Document unit = new Document("custom_id", p.getString("reference"))
                .append("amount", new Document("currency_code", p.getString("currency"))
                        .append("value", BigDecimal.valueOf(p.getLong("amount"), 2).toPlainString()));
        if ("CAPTURED".equals(p.getString("state"))) unit.put("payments", new Document("captures",
                List.of(new Document("id", "cap_" + p.getString("_id")).append("status", "COMPLETED")
                        .append("amount", unit.get("amount")))));
        return new Document("id", p.getString("_id")).append("status", status).append("intent", "CAPTURE")
                .append("purchase_units", List.of(unit))
                .append("links", List.of(new Document("rel", "self").append("method", "GET")
                        .append("href", "/v2/checkout/orders/" + p.getString("_id"))));
    }
}
