package com.talentshift.hub.integration.exception;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NotFoundException.class) ResponseEntity<?> notFound(NotFoundException e) { return error(HttpStatus.NOT_FOUND,e); }
    @ExceptionHandler(ConflictException.class) ResponseEntity<?> conflict(ConflictException e) { return error(HttpStatus.CONFLICT,e); }
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("timestamp", Instant.now(), "error", "Validation failed",
                "details", e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()+": "+f.getDefaultMessage()).toList()));
    }
    private ResponseEntity<?> error(HttpStatus status, Exception e) {
        return ResponseEntity.status(status).body(Map.of("timestamp",Instant.now(),"status",status.value(),"error",e.getMessage()));
    }
}
