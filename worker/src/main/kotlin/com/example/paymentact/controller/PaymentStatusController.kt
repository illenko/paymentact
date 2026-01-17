package com.example.paymentact.controller

import com.example.paymentact.model.CheckStatusQueryResponse
import com.example.paymentact.model.CheckStatusRequest
import com.example.paymentact.model.CheckStatusStartResponse
import com.example.paymentact.model.WorkflowStatus
import com.example.paymentact.service.PaymentStatusService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/payments")
class PaymentStatusController(
    private val paymentStatusService: PaymentStatusService
) {

    private val logger = LoggerFactory.getLogger(PaymentStatusController::class.java)

    @PostMapping("/check-status")
    fun startCheckStatus(
        @Valid @RequestBody request: CheckStatusRequest
    ): ResponseEntity<CheckStatusStartResponse> {
        logger.info("Received check-status request for {} payments", request.paymentIds.size)

        val response = paymentStatusService.startPaymentStatusCheck(request.paymentIds)

        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(response)
    }

    @GetMapping("/check-status/{workflowId}")
    fun getCheckStatus(
        @PathVariable workflowId: String
    ): ResponseEntity<CheckStatusQueryResponse> {
        logger.info("Received status query for workflow {}", workflowId)

        val response = paymentStatusService.getWorkflowStatus(workflowId)

        val status = when (response.status) {
            WorkflowStatus.NOT_FOUND -> HttpStatus.NOT_FOUND
            else -> HttpStatus.OK
        }

        return ResponseEntity
            .status(status)
            .body(response)
    }
}
