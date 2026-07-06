package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.risk.RiskRejectedException;
import finos.traderx.ordermatcher.risk.RiskRejectionBody;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class RiskExceptionHandler {
    @ExceptionHandler(RiskRejectedException.class)
    public ResponseEntity<RiskRejectionBody> rejected(RiskRejectedException ex) {
        HttpStatus status = ex.reason().name().contains("STALE") ? HttpStatus.SERVICE_UNAVAILABLE
            : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(new RiskRejectionBody(ex.clientOrderId(), "REJECTED", ex.reason(),
            ex.policyVersion(), ex.commandSequence()));
    }
}
