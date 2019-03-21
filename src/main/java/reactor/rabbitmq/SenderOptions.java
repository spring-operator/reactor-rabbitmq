/*
 * Copyright (c) 2017-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.function.Supplier;

/**
 * Options for {@link Sender} creation.
 */
public class SenderOptions {

    private ConnectionFactory connectionFactory = ((Supplier<ConnectionFactory>) () -> {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.useNio();
        return connectionFactory;
    }).get();

    private Mono<? extends Connection> connectionMono;

    private Scheduler resourceCreationScheduler;

    private Scheduler connectionSubscriptionScheduler;

    private Mono<? extends Channel> resourceManagementChannelMono;

    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    public SenderOptions connectionFactory(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        return this;
    }

    public Scheduler getResourceCreationScheduler() {
        return resourceCreationScheduler;
    }

    /**
     * Resource creation scheduler.
     * It is developer's responsibility to close it if set.
     * @param resourceCreationScheduler
     * @return the current {@link SenderOptions} instance
     */
    public SenderOptions resourceCreationScheduler(Scheduler resourceCreationScheduler) {
        this.resourceCreationScheduler = resourceCreationScheduler;
        return this;
    }

    public Scheduler getConnectionSubscriptionScheduler() {
        return connectionSubscriptionScheduler;
    }

    /**
     * Scheduler used on connection creation subscription.
     * It is developer's responsibility to close it if set.
     * @param connectionSubscriptionScheduler
     * @return the current {@link SenderOptions} instance
     */
    public SenderOptions connectionSubscriptionScheduler(Scheduler connectionSubscriptionScheduler) {
        this.connectionSubscriptionScheduler = connectionSubscriptionScheduler;
        return this;
    }

    public SenderOptions connectionSupplier(Utils.ExceptionFunction<ConnectionFactory, ? extends Connection> function) {
        return this.connectionSupplier(this.connectionFactory, function);
    }

    public SenderOptions connectionSupplier(ConnectionFactory connectionFactory, Utils.ExceptionFunction<ConnectionFactory, ? extends Connection> function) {
        this.connectionMono = Mono.fromCallable(() -> function.apply(connectionFactory));
        return this;
    }

    public SenderOptions connectionMono(Mono<? extends Connection> connectionMono) {
        this.connectionMono = connectionMono;
        return this;
    }

    public Mono<? extends Connection> getConnectionMono() {
        return connectionMono;
    }

    public SenderOptions resourceManagementChannelMono(Mono<? extends Channel> resourceManagementChannelMono) {
        this.resourceManagementChannelMono = resourceManagementChannelMono;
        return this;
    }

    public Mono<? extends Channel> getResourceManagementChannelMono() {
        return resourceManagementChannelMono;
    }
}
