package com.paymesh.paypal;

import com.paymesh.provider.ProviderSettings;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Provider @PreMatching @Priority(Priorities.AUTHENTICATION)
public class LocalAuthentication implements ContainerRequestFilter, ContainerResponseFilter {
    private final ProviderSettings settings;
    public LocalAuthentication(ProviderSettings settings) { this.settings = settings; }
    @Override public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        if (path.startsWith("/")) path = path.substring(1);
        if (path.equals("health")) return;
        boolean admin = path.startsWith("__sim/");
        String provided = request.getHeaderString(admin ? "X-Simulator-Admin-Key" : "Authorization");
        String expected = admin ? settings.adminKey() : "Bearer " + settings.apiKey();
        if (provided == null || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)))
            request.abortWith(Response.status(401).type("application/json")
                    .entity(Map.of("code", "unauthorized", "message", "Valid local simulator credentials required")).build());
    }
    @Override public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle("Cache-Control", "no-store");
    }
}
