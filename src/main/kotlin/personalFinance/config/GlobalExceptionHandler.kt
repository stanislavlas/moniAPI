package personalFinance.config

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest

data class ErrorResponse(
    val message: String,
    val details: String,
    val timestamp: Long = System.currentTimeMillis()
)

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
}
