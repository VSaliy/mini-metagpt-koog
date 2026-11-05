package io.github.vsaliy.minimetagpt.api

import io.github.vsaliy.minimetagpt.domain.QualityReportArtifact
import io.github.vsaliy.minimetagpt.orchestration.WorkflowService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowController(private val service: WorkflowService) {

    @PostMapping
    fun create(@Valid @RequestBody body: CreateWorkflowRequest): ResponseEntity<WorkflowResponse> {
        val workflow = service.create(body.requirement!!.trim())
        return ResponseEntity.accepted()
            .location(URI.create("/api/v1/workflows/${workflow.id}"))
            .body(WorkflowResponse.of(workflow))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): WorkflowResponse = WorkflowResponse.of(service.get(id), service.history(id))

    @GetMapping("/{id}/artifacts")
    fun artifacts(@PathVariable id: UUID) = ArtifactsResponse(id, service.artifacts(id))

    @GetMapping("/{id}/report")
    fun report(@PathVariable id: UUID): QualityReportArtifact = service.report(id)

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: UUID): ResponseEntity<WorkflowResponse> =
        ResponseEntity.accepted().body(WorkflowResponse.of(service.cancel(id)))

    /** Approves execution (compile + test) of the generated, untrusted code. */
    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: UUID): ResponseEntity<WorkflowResponse> =
        ResponseEntity.accepted().body(WorkflowResponse.of(service.decide(id, true)))

    /** Declines execution; the workflow completes with a NOT_EXECUTED quality report. */
    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: UUID): ResponseEntity<WorkflowResponse> =
        ResponseEntity.accepted().body(WorkflowResponse.of(service.decide(id, false)))
}
