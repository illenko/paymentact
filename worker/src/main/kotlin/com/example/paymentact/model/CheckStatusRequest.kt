package com.example.paymentact.model

import jakarta.validation.constraints.NotEmpty

data class CheckStatusRequest(
    @field:NotEmpty(message = "paymentIds must not be empty")
    val paymentIds: List<String>
)
