package com.paymesh.paypal;

import com.paymesh.provider.application.PaymentService;
import com.paymesh.provider.domain.ApiResult;
import com.paymesh.provider.domain.PaymentRules;
import com.paymesh.provider.domain.ProviderError;
import com.paymesh.provider.web.ProviderViews;
import io.smallrye.common.annotation.Blocking;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.bson.Document;
import java.util.List;

@Path("/")
@Blocking // The synchronous MongoDB driver must not run on the event-loop thread.
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PayPalResource {
    private final PaymentService payments;
    public PayPalResource(PaymentService payments) { this.payments = payments; }
    public record Amount(@NotBlank String currency_code, @NotBlank String value) {}
    public record PurchaseUnit(@NotBlank String custom_id, @NotNull @Valid Amount amount) {}
    public record OrderRequest(@NotBlank String intent,
            @NotNull @Size(min=1, max=1) List<@NotNull @Valid PurchaseUnit> purchase_units) {}

    @POST @Path("v2/checkout/orders")
    public Response create(@HeaderParam("PayPal-Request-Id") String key,
            @HeaderParam("X-Simulator-Payment-Method") @DefaultValue("pm_funded") String fundingToken,
            @NotNull @Valid OrderRequest request) {
        if (!"CAPTURE".equals(request.intent())) throw new IllegalArgumentException("Only CAPTURE intent is implemented");
        PurchaseUnit unit = request.purchase_units().getFirst();
        return response(payments.create(key, PaymentRules.decimalToMinor(unit.amount().value()),
                unit.amount().currency_code(), unit.custom_id(), fundingToken, false, false).map(ProviderViews::paypal));
    }
    @GET @Path("v2/checkout/orders/{id}")
    public Document get(@PathParam("id") String id) { return ProviderViews.paypal(payments.get(id)); }

    @POST @Path("v2/checkout/orders/{id}/capture")
    public Response capture(@PathParam("id") String id, @HeaderParam("PayPal-Request-Id") String key) {
        var result = payments.capture(id, key);
        if (result.status() == 422)
            return response(result.map(p -> new Document("name", "UNPROCESSABLE_ENTITY")
                    .append("details", List.of(new Document("issue", "INSTRUMENT_DECLINED")))
                    .append("message", "The simulated funding account declined the payment")));
        return response(result.map(ProviderViews::paypal));
    }
    // Local-only stand-in for the buyer's browser interaction, protected by the admin credential.
    @POST @Path("__sim/orders/{id}/approve")
    public Response approve(@PathParam("id") String id, @HeaderParam("PayPal-Request-Id") String key) {
        return response(payments.approve(id, key).map(ProviderViews::paypal));
    }
    private Response response(ApiResult result) {
        if (result.simulateLostResponse())
            throw new ProviderError(504, "simulated_response_lost",
                    "Provider committed; retry the SAME operation key to recover the result");
        return Response.status(result.status()).header("X-Simulator", "paymesh-local")
                .header("X-Idempotent-Replay", Boolean.toString(result.replayed())).entity(result.body()).build();
    }
}
