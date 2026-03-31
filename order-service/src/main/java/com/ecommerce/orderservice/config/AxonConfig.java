package com.ecommerce.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.config.ConfigurerModule;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventhandling.tokenstore.jpa.JpaTokenStore;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.JpaEventStorageEngine;
import org.axonframework.messaging.interceptors.BeanValidationInterceptor;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.jpa.JpaSagaStore;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.json.JacksonSerializer;
import org.axonframework.spring.config.AxonConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Axon Framework infrastructure configuration.
 *
 * <ul>
 *   <li>Uses Jackson as the serialiser for all Axon messages (events, commands, sagas, tokens).</li>
 *   <li>Uses {@link JpaEventStorageEngine} backed by PostgreSQL as the event store.</li>
 *   <li>Uses {@link JpaTokenStore} for tracking processor checkpoints.</li>
 *   <li>Uses {@link JpaSagaStore} for saga state persistence.</li>
 *   <li>Registers a {@link BeanValidationInterceptor} on the command bus so that
 *       all incoming commands are validated before reaching the aggregate.</li>
 * </ul>
 */
@Configuration
public class AxonConfig {

    // ─────────────────────────────────────────────────────────────────────────
    // Jackson / Serialisation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Configures the shared {@link ObjectMapper} to handle Java 8 time types
     * (used by both Spring MVC and Axon).
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Jackson-based Axon serialiser used for events, snapshots, saga state, and tokens.
     * Declared {@link Primary} so it takes precedence over the default XStream serialiser.
     */
    @Bean
    @Primary
    public Serializer axonSerializer(ObjectMapper objectMapper) {
        return JacksonSerializer.builder().objectMapper(objectMapper).build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event Store — JPA-backed (PostgreSQL)
    // ─────────────────────────────────────────────────────────────────────────

    @Bean
    public EventStorageEngine eventStorageEngine(
            @Qualifier("axonSerializer") Serializer serializer,
            EntityManagerProvider entityManagerProvider,
            TransactionManager transactionManager,
            AxonConfiguration configuration) {

        return JpaEventStorageEngine.builder()
                .snapshotSerializer(serializer)
                .eventSerializer(serializer)
                .upcasterChain(configuration.upcasterChain())
                .entityManagerProvider(entityManagerProvider)
                .transactionManager(transactionManager)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Token Store — JPA-backed (tracking processors checkpoint)
    // ─────────────────────────────────────────────────────────────────────────

    @Bean
    public TokenStore tokenStore(
            EntityManagerProvider entityManagerProvider,
            @Qualifier("axonSerializer") Serializer serializer) {

        return JpaTokenStore.builder()
                .entityManagerProvider(entityManagerProvider)
                .serializer(serializer)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Saga Store — JPA-backed
    // ─────────────────────────────────────────────────────────────────────────

    @Bean
    public SagaStore<?> sagaStore(
            EntityManagerProvider entityManagerProvider,
            @Qualifier("axonSerializer") Serializer serializer) {

        return JpaSagaStore.builder()
                .entityManagerProvider(entityManagerProvider)
                .serializer(serializer)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command Bus interceptors
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Registers a JSR-380 validation interceptor on the command bus so that
     * {@code @Valid} / {@code @NotNull} etc. annotations on command records are
     * enforced before the command reaches the aggregate.
     */
    @Bean
    public ConfigurerModule commandBusConfigurer() {
        return configurer -> configurer.onInitialize(config ->
                config.commandBus().registerDispatchInterceptor(
                        new BeanValidationInterceptor<>()));
    }
}
