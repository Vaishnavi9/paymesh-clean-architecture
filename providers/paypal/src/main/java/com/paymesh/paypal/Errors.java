package com.paymesh.paypal;

import com.mongodb.MongoException;
import com.paymesh.provider.domain.ProviderError;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;

@Provider
public class Errors implements ExceptionMapper<Exception> {
    @Override public Response toResponse(Exception error) {
        if (error instanceof ProviderError e) return response(e.status(), e.code(), e.getMessage());
        if (error instanceof IllegalArgumentException || error instanceof ConstraintViolationException)
            return response(400, "invalid_request", "Check required fields, amount, currency and operation");
        if (error instanceof MongoException)
            return response(503, "storage_unavailable", "Storage is temporarily unavailable; retain the same operation key");
        if (error instanceof WebApplicationException e)
            return response(e.getResponse().getStatus(), "invalid_request", "Check the request format and endpoint");
        return response(500, "internal_error", "Unexpected simulator error");
    }
    private Response response(int status, String code, String message) {
        return Response.status(status).type("application/json").entity(Map.of("code", code, "message", message)).build();
    }
}
