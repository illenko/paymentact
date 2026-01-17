package com.example.paymentact.workflow

import com.example.paymentact.model.GatewayResult
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface GatewayWorkflow {

    @WorkflowMethod
    fun processGateway(gateway: String, chunks: List<List<String>>): GatewayResult

    @QueryMethod
    fun getChunkProgress(): ChunkProgress
}

data class ChunkProgress(
    val totalChunks: Int,
    val completedChunks: Int,
    val currentChunkIndex: Int
)
