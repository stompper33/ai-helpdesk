package com.helpdesk.actions;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Two people changed the same record at once (detected by @Version). Ask the caller to reload. */
@RestControllerAdvice
public class ConcurrencyExceptionHandler {

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Someone else changed this record at the same time. Reload it and try again.");
        pd.setTitle(HttpStatus.CONFLICT.getReasonPhrase());
        pd.setProperty("code", "CONCURRENT_UPDATE");
        return pd;
    }
}
