package com.ynixt.dmdl.application.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class TransactionConfig {
    @Bean
    fun transactionTemplate(manager: PlatformTransactionManager): TransactionTemplate = TransactionTemplate(manager)
}
