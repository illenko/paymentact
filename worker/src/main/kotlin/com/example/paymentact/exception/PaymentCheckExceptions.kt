package com.example.paymentact.exception

/**
 * Base exception for payment status check errors
 */
sealed class PaymentCheckException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Thrown when a payment is not found in Elasticsearch
 */
class PaymentNotFoundException(
    val paymentId: String
) : PaymentCheckException("Payment not found: $paymentId")

/**
 * Thrown when Elasticsearch lookup fails
 */
class ElasticsearchException(
    val paymentId: String,
    message: String,
    cause: Throwable? = null
) : PaymentCheckException("ES lookup failed for payment $paymentId: $message", cause)

/**
 * Thrown when IDB facade call fails
 */
class IdbFacadeException(
    val gateway: String,
    val paymentIds: List<String>,
    message: String,
    cause: Throwable? = null
) : PaymentCheckException("IDB facade call failed for gateway $gateway: $message", cause)

/**
 * Thrown when PGI gateway call fails
 */
class PgiGatewayException(
    val gateway: String,
    val paymentId: String,
    message: String,
    cause: Throwable? = null
) : PaymentCheckException("PGI gateway call failed for payment $paymentId on gateway $gateway: $message", cause)
