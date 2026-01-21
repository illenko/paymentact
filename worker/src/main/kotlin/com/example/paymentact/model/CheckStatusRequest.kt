package com.example.paymentact.model

import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

data class CheckStatusRequest(
    @field:NotEmpty(message = "paymentIds must not be empty")
    @field:Size(max = 10000, message = "paymentIds must not exceed 10000 items")
    val paymentIds: List<String>
)
