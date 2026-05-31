package org.mifos.creditbureau.cb_ild.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when CDC returns 5xx — CDC is down or overloaded.
 *
 * HTTP 503 to Angular.
 * CAN retry — CDC may recover.
 *
 * Angular shows: "Credit bureau temporarily unavailable — try again later"
 *
 * Security:
 *   clientId stored internally — never in Angular message
 *   httpStatus stored for retry logic — never in Angular message
 */
@Slf4j
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class CdcServerException extends RuntimeException {

    private final Long clientId;
    private final int httpStatus;

    public CdcServerException(Long clientId, int httpStatus) {
        super("CDC server error — retry eligible");
        this.clientId = clientId;
        this.httpStatus = httpStatus;
        log.error("CDC server error for clientId: {} — httpStatus: {}",
                clientId, httpStatus);
    }

    public Long getClientId() {
        return clientId;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
