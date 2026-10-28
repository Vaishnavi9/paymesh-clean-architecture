package com.paymesh.provider.domain;

import org.bson.Document;

public record ApiResult(int status, Document body, boolean replayed, boolean simulateLostResponse) {
    public ApiResult map(java.util.function.Function<Document, Document> mapper) {
        return new ApiResult(status, mapper.apply(body), replayed, simulateLostResponse);
    }
}
