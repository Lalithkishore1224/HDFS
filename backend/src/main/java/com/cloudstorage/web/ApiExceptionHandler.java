package com.cloudstorage.web;

import com.cloudstorage.account.AccountNotFoundException;
import com.cloudstorage.account.InvalidAccountChangeException;
import com.cloudstorage.account.InvalidCredentialsException;
import com.cloudstorage.account.TooManyAttemptsException;
import com.cloudstorage.account.UsernameTakenException;
import com.cloudstorage.file.NotPreviewableException;
import com.cloudstorage.file.QuotaExceededException;
import com.cloudstorage.file.StoredFileNotFoundException;
import com.cloudstorage.folder.DuplicateFolderNameException;
import com.cloudstorage.folder.FolderNotFoundException;
import com.cloudstorage.folder.InvalidFolderMoveException;
import java.io.UncheckedIOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates domain exceptions into RFC 7807 problem responses the UI can display. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler({StoredFileNotFoundException.class, FolderNotFoundException.class, AccountNotFoundException.class})
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(NotPreviewableException.class)
    ProblemDetail notPreviewable(NotPreviewableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail wrongCredentials(InvalidCredentialsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    ProblemDetail tooManyAttempts(TooManyAttemptsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(UsernameTakenException.class)
    ProblemDetail usernameTaken(UsernameTakenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(DuplicateFolderNameException.class)
    ProblemDetail conflict(DuplicateFolderNameException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidAccountChangeException.class)
    ProblemDetail invalidAccountChange(InvalidAccountChangeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidFolderMoveException.class)
    ProblemDetail invalidMove(InvalidFolderMoveException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(QuotaExceededException.class)
    ProblemDetail quotaExceeded(QuotaExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Not enough storage space left");
    }

    /** The bytes of a file cannot be read right now, for example because every node holding it is offline. */
    @ExceptionHandler(UncheckedIOException.class)
    ProblemDetail storageUnavailable(UncheckedIOException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "This file is temporarily unavailable");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException e) {
        String reason = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, reason);
    }
}
