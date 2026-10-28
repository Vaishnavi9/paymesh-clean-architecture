package com.paymesh.provider.web;

import com.mongodb.MongoException;
import com.paymesh.provider.domain.ProviderError;
import org.bson.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ExceptionHandler {
    @org.springframework.web.bind.annotation.ExceptionHandler(ProviderError.class)
    ResponseEntity<Document> provider(ProviderError error) {
        return ResponseEntity.status(error.status()).body(new Document("code", error.code()).append("message", error.getMessage()));
    }
    @org.springframework.web.bind.annotation.ExceptionHandler({
            IllegalArgumentException.class, MethodArgumentNotValidException.class,
            ServletRequestBindingException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Document> invalid(Exception error) {
        return ResponseEntity.badRequest().body(new Document("code", "invalid_request")
                .append("message", "Check the required fields, amount, currency and operation"));
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(MongoException.class)
    ResponseEntity<Document> database(MongoException error) {
        return ResponseEntity.status(503).body(new Document("code", "storage_unavailable")
                .append("message", "Storage is temporarily unavailable; retain the same operation key"));
    }
}
