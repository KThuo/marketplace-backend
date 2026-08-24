package com.hodi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A {@link TransactionTemplate} that always starts a fresh transaction.
 *
 * <p>Needed wherever a write must survive the rollback of the transaction in progress — refresh-token
 * reuse revocation, and audit rows for failed operations. Self-invoking a
 * {@code @Transactional(REQUIRES_NEW)} method does not work: the call bypasses the proxy, so the
 * annotation is silently ignored and the write joins the doomed transaction anyway.
 */
@Configuration
public class TransactionConfig {

    @Bean
    TransactionTemplate newTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
