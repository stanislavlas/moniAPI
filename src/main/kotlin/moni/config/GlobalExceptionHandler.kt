package moni.config

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest

data class ErrorResponse(
    val message: String,
    val details: String,
    val timestamp: Long = System.currentTimeMillis()
)

/** Thrown when authentication fails (wrong password, no such user, etc.). */
class AuthException(message: String) : RuntimeException(message)

/** Thrown when a resource exists but the caller is not allowed to access it (e.g. email not verified). */
class ForbiddenException(message: String) : RuntimeException(message)

@ControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(Exception::class)
    fun handleAllExceptions(ex: Exception, request: WebRequest): ResponseEntity<ErrorResponse> {
        logger.error("Unhandled exception at ${request.getDescription(false)}", ex)

        val errorResponse = ErrorResponse(
            message = ex.message ?: "An unexpected error occurred",
            details = request.getDescription(false).replace("uri=", "")
        )

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException, request: WebRequest): ResponseEntity<ErrorResponse> {
        logger.error("Invalid argument at ${request.getDescription(false)}: ${ex.message}")

        val errorResponse = ErrorResponse(
            message = ex.message ?: "Invalid argument",
            details = request.getDescription(false).replace("uri=", "")
        )

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFoundException(ex: NoSuchElementException, request: WebRequest): ResponseEntity<ErrorResponse> {
        logger.error("Resource not found at ${request.getDescription(false)}: ${ex.message}")

        val errorResponse = ErrorResponse(
            message = ex.message ?: "Resource not found",
            details = request.getDescription(false).replace("uri=", "")
        )

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse)
    }

    /** Authentication failures (wrong password, user not found) → 401. */
    @ExceptionHandler(AuthException::class)
    fun handleAuthException(ex: AuthException, request: WebRequest): ResponseEntity<ErrorResponse> {
        logger.warn("Auth failure at ${request.getDescription(false)}: ${ex.message}")

        val errorResponse = ErrorResponse(
            message = ex.message ?: "Authentication failed",
            details = request.getDescription(false).replace("uri=", "")
        )

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse)
    }

    /** Bean validation failures → 400 with field errors. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(ex: MethodArgumentNotValidException, request: WebRequest): ResponseEntity<Map<String, Any>> {
        val fieldErrors = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        val body = mapOf(
            "message" to "Validation failed",
            "errors"  to fieldErrors,
            "details" to request.getDescription(false).replace("uri=", ""),
            "timestamp" to System.currentTimeMillis()
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /** Forbidden access (e.g. email not verified) → 403. */
    @ExceptionHandler(ForbiddenException::class)
    fun handleForbiddenException(ex: ForbiddenException, request: WebRequest): ResponseEntity<ErrorResponse> {
        logger.warn("Forbidden at ${request.getDescription(false)}: ${ex.message}")

        val errorResponse = ErrorResponse(
            message = ex.message ?: "Access denied",
            details = request.getDescription(false).replace("uri=", "")
        )

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse)
    }
}
