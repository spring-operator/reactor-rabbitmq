/*
 * Copyright (c) 2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.rabbitmq.docs;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExceptionHandlers;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.ReactorRabbitMq;
import reactor.rabbitmq.RpcClient;
import reactor.rabbitmq.SendOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

// tag::static-import[]
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import static reactor.rabbitmq.ResourcesSpecification.*;
// end::static-import[]

/**
 *
 */
@SuppressWarnings("unused")
public class ApiGuideSender {

    void optionsSimple() {
        // tag::options-simple[]
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.useNio();

        SenderOptions senderOptions =  new SenderOptions()
            .connectionFactory(connectionFactory)                       // <1>
            .resourceCreationScheduler(Schedulers.elastic());           // <2>
        // end::options-simple[]
        // tag::instanciation[]
        Sender sender = ReactorRabbitMq.createSender(senderOptions);
        // end::instanciation[]
        // tag::outbound-message-flux[]
        Flux<OutboundMessage> outboundFlux  =
            Flux.range(1, 10)
                .map(i -> new OutboundMessage(
                    "amq.direct",
                    "routing.key", ("Message " + i).getBytes()
                ));
        // end::outbound-message-flux[]
        Logger log = LoggerFactory.getLogger(ApiGuideSender.class);
        // tag::send-flux[]
        sender.send(outboundFlux)                         // <1>
            .doOnError(e -> log.error("Send failed", e))  // <2>
            .subscribe();                                 // <3>
        // end::send-flux[]
        // tag::resource-declaration[]
        Mono<AMQP.Exchange.DeclareOk> exchange = sender.declareExchange(
            ExchangeSpecification.exchange("my.exchange")
        );
        Mono<AMQP.Queue.DeclareOk> queue = sender.declareQueue(
            QueueSpecification.queue("my.queue")
        );
        Mono<AMQP.Queue.BindOk> binding = sender.bind(
            BindingSpecification.binding().exchange("my.exchange")
                .queue("my.queue").routingKey("a.b")
        );
        // end::resource-declaration[]
        // tag::resource-declaration-static-import[]
        sender.declare(exchange("my.exchange"))
            .then(sender.declare(queue("my.queue")))
            .then(sender.bind(binding("my.exchange", "a.b", "my.queue")))
            .subscribe(r -> System.out.println("Exchange and queue declared and bound"));
        // end::resource-declaration-static-import[]
        // tag::resource-deletion[]
        sender.unbind(binding("my.exchange", "a.b", "my.queue"))
            .then(sender.delete(exchange("my.exchange")))
            .then(sender.delete(queue("my.queue")))
            .subscribe(r -> System.out.println("Exchange and queue unbound and deleted"));
        // end::resource-deletion[]
        // tag::closing[]
        sender.close();
        // end::closing[]
    }

    void optionsConnectionSupplier() {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.useNio();
        // tag::options-connection-supplier[]
        SenderOptions senderOptions =  new SenderOptions()
            .connectionFactory(connectionFactory)
            .connectionSupplier(cf -> cf.newConnection(                                  // <1>
                new Address[] {new Address("192.168.0.1"), new Address("192.168.0.2")},
                "reactive-sender"))
            .resourceCreationScheduler(Schedulers.elastic());
        // end::options-connection-supplier[]
    }

    void publisherConfirms() {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.useNio();

        SenderOptions senderOptions =  new SenderOptions()
            .connectionFactory(connectionFactory)
            .resourceCreationScheduler(Schedulers.elastic());
        Sender sender = ReactorRabbitMq.createSender(senderOptions);
        // tag::publisher-confirms[]
        Flux<OutboundMessage> outboundFlux  = Flux.range(1, 10)
            .map(i -> new OutboundMessage(
                "amq.direct",
                "routing.key", "hello".getBytes()
            ));
        sender.sendWithPublishConfirms(outboundFlux)
            .subscribe(outboundMessageResult -> {
                // outbound message has reached the broker
            });
        // end::publisher-confirms[]
    }

    void rpc() {
        // tag::rpc[]
        String queue = "rpc.server.queue";
        Sender sender = ReactorRabbitMq.createSender();
        RpcClient rpcClient = sender.rpcClient("", queue);  // <1>
        Mono<Delivery> reply = rpcClient.rpc(Mono.just(
            new RpcClient.RpcRequest("hello".getBytes())    // <2>
        ));
        rpcClient.close();                                  // <3>
        // end::rpc[]
    }

    void rpcCorrelationIdProvider() {
        // tag::rpc-supplier[]
        String queue = "rpc.server.queue";
        Supplier<String> correlationIdSupplier = () -> UUID.randomUUID().toString(); // <1>
        Sender sender = ReactorRabbitMq.createSender();
        RpcClient rpcClient = sender.rpcClient(
            "", queue, correlationIdSupplier                                         // <2>
        );
        Mono<Delivery> reply = rpcClient.rpc(Mono.just(
            new RpcClient.RpcRequest("hello".getBytes())
        ));
        rpcClient.close();
        // end::rpc-supplier[]
    }

    void retryExceptionHandler() {
        Flux<OutboundMessage> outboundFlux = null;
        // tag::retry-settings[]
        Sender sender = ReactorRabbitMq.createSender();
        sender.send(outboundFlux, new SendOptions().exceptionHandler(
           new ExceptionHandlers.RetrySendingExceptionHandler(
               Duration.ofSeconds(20), Duration.ofMillis(500), ExceptionHandlers.CONNECTION_RECOVERY_PREDICATE
           )
        ));
        // end::retry-settings[]
    }

}
