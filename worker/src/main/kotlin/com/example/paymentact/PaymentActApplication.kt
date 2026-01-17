package com.example.paymentact

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PaymentActApplication

fun main(args: Array<String>) {
	runApplication<PaymentActApplication>(*args)
}
