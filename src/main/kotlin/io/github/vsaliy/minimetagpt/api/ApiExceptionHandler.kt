package io.github.vsaliy.minimetagpt.api

import io.github.vsaliy.minimetagpt.orchestration.WorkflowConflictException
import io.github.vsaliy.minimetagpt.orchestration.WorkflowNotFoundException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(WorkflowNotFoundException::class)
    fun notFound(e: WorkflowNotFoundException, req: HttpServletRequest) =
        build(HttpStatus.NOT_FOUND, "WORKFLOW_NOT_FOUND", e.message, req)

    @ExceptionHandler(WorkflowConflictException::class)
    fun conflict(e: WorkflowConflictException, req: HttpServletRequest) =
        build(HttpStatus.CONFLICT, e.code, e.message, req)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException, req: HttpServletRequest) = build(
        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", req,
        e.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" },
    )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException, req: HttpServletRequest) =
        build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or not valid JSON", req)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun badArgument(e: MethodArgumentTypeMismatchException, req: HttpServletRequest) =
        build(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid value for '${e.name}'", req)

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception, req: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.error("Unhandled error on {}", req.requestURI, e)
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", req)
    }

    private fun build(status: HttpStatus, code: String, message: String?, req: HttpServletRequest, details: List<String> = emptyList()) =
        ResponseEntity.status(status).body(ErrorResponse(code, message ?: status.reasonPhrase, details, req.requestURI))
}
