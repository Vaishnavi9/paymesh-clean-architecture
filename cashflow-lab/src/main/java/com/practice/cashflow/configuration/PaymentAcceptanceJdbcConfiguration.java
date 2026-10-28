package com.practice.cashflow.configuration;


import com.practice.cashflow.adapter.out.persistence.JdbcPaymentAcceptanceStore;
import com.practice.cashflow.application.port.in.GetPaymentStatusUseCase;
import com.practice.cashflow.application.port.in.AcceptPaymentUseCase;
import com.practice.cashflow.application.AcceptPaymentService;
import com.practice.cashflow.application.port.out.PaymentAcceptanceStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@Profile("db & acceptance")
public class PaymentAcceptanceJdbcConfiguration {

    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(
            JdbcTemplate jdbcTemplate
    ) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    @Bean
    JdbcTransactionManager paymentTransactionManager(DataSource dataSource) {
        var manager = new JdbcTransactionManager(dataSource);
        manager.setValidateExistingTransaction(true);
        return manager;
    }

    @Bean
    TransactionTemplate paymentTransaction(
            JdbcTransactionManager jdbcTransactionManager
    ) {
        var transactions =
                new TransactionTemplate(jdbcTransactionManager);
        transactions.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );

        transactions.setTimeout(5);

        return transactions;
    }

    @Bean
    JdbcPaymentAcceptanceStore paymentAcceptanceStore(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate paymentTransaction,
            ApplicationEventPublisher events
    ) {
        return new JdbcPaymentAcceptanceStore(
                jdbc,
                paymentTransaction,
                events
        );
    }

    @Bean
    AcceptPaymentUseCase acceptPaymentUseCase(PaymentAcceptanceStore store) {
        return new AcceptPaymentService(store);
    }

    @Bean
    GetPaymentStatusUseCase getPaymentStatusUseCase(PaymentAcceptanceStore store) {
        return store::find;
    }
}
