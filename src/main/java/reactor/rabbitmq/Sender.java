/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.impl.AMQImpl;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive abstraction to create resources and send messages.
 */
public class Sender implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Sender.class);

    private static final Function<Connection, Channel> CHANNEL_CREATION_FUNCTION = new ChannelCreationFunction();

    private final Mono<Connection> connectionMono;

    private final AtomicBoolean hasConnection = new AtomicBoolean(false);

    private final Mono<Channel> channelMono;

    private final Scheduler resourceCreationScheduler;

    private final boolean privateResourceCreationScheduler;

    private final Scheduler connectionSubscriptionScheduler;

    private final boolean privateConnectionSubscriptionScheduler;

    public Sender() {
        this(new SenderOptions());
    }

    public Sender(SenderOptions options) {
        this.privateConnectionSubscriptionScheduler = options.getConnectionSubscriptionScheduler() == null;
        this.connectionSubscriptionScheduler = options.getConnectionSubscriptionScheduler() == null ?
            createScheduler() : options.getConnectionSubscriptionScheduler();
        this.connectionMono = Mono.fromCallable(() -> {
            Connection connection = options.getConnectionFactory().newConnection();
            return connection;
        })
            .doOnSubscribe(c -> hasConnection.set(true))
            .subscribeOn(this.connectionSubscriptionScheduler)
            .cache();
        this.privateResourceCreationScheduler = options.getResourceCreationScheduler() == null;
        this.resourceCreationScheduler = options.getResourceCreationScheduler() == null ?
            createScheduler() : options.getResourceCreationScheduler();
        this.channelMono = connectionMono.map(CHANNEL_CREATION_FUNCTION).cache();
    }

    protected Scheduler createScheduler() {
        return Schedulers.elastic();
    }

    public Mono<Void> send(Publisher<OutboundMessage> messages) {
        // TODO using a pool of channels?
        // would be much more efficient if send is called very often
        // less useful if seldom called, only for long or infinite message flux
        final Mono<Channel> currentChannelMono = connectionMono.map(CHANNEL_CREATION_FUNCTION).cache();

        return currentChannelMono.flatMapMany(channel ->
                Flux.from(messages)
                    .doOnNext(message -> {
                        try {
                            channel.basicPublish(
                                    message.getExchange(),
                                    message.getRoutingKey(),
                                    message.getProperties(),
                                    message.getBody()
                            );
                        } catch (IOException e) {
                            //TODO swallow errors? any message error interrupts the Flux
                            throw new ReactorRabbitMqException(e);
                        }
                    })
                    .doOnError(e -> LOGGER.warn("Send failed with exception {}", e))
                    .doFinally(st -> {
                        int channelNumber = channel.getChannelNumber();
                        LOGGER.info("closing channel {} by signal {}", channelNumber, st);
                        try {
                            channel.close();
                        } catch (TimeoutException | IOException e) {
                            LOGGER.warn("Channel {} didn't close normally: {}", channelNumber, e.getMessage());
                        }
                    })
        ).then();
    }

    public Flux<OutboundMessageResult> sendWithPublishConfirms(Publisher<OutboundMessage> messages) {
        // TODO using a pool of channels?
        // would be much more efficient if send is called very often
        // less useful if seldom called, only for long or infinite message flux
        final Mono<Channel> channelMono = connectionMono.map(CHANNEL_CREATION_FUNCTION)
            .map(channel -> {
                try {
                    channel.confirmSelect();
                } catch (IOException e) {
                    throw new ReactorRabbitMqException("Error while setting publisher confirms on channel", e);
                }
                return channel;
            });

        return channelMono.flatMapMany(channel -> new PublishConfirmOperator(messages, channel));
    }

    public Mono<AMQP.Queue.DeclareOk> createQueue(QueueSpecification specification) {
        AMQP.Queue.Declare declare;
        if (specification.getName() == null) {
            declare = new AMQImpl.Queue.Declare.Builder()
                .queue("")
                .durable(false)
                .exclusive(true)
                .autoDelete(true)
                .arguments(null)
                .build();
        } else {
            declare = new AMQImpl.Queue.Declare.Builder()
                .queue(specification.getName())
                .durable(specification.isDurable())
                .exclusive(specification.isExclusive())
                .autoDelete(specification.isAutoDelete())
                .arguments(specification.getArguments())
                .build();
        }

        return channelMono.map(channel -> {
            try {
                return channel.asyncCompletableRpc(declare);
            } catch (IOException e) {
                throw new ReactorRabbitMqException("Error during RPC call", e);
            }
        }).flatMap(future -> Mono.fromCompletionStage(future))
          .flatMap(command -> Mono.just((AMQP.Queue.DeclareOk) command.getMethod()))
          .publishOn(resourceCreationScheduler);
    }

    public Mono<AMQP.Exchange.DeclareOk> createExchange(ExchangeSpecification specification) {
        AMQP.Exchange.Declare declare = new AMQImpl.Exchange.Declare.Builder()
            .exchange(specification.getName())
            .type(specification.getType())
            .durable(specification.isDurable())
            .autoDelete(specification.isAutoDelete())
            .internal(specification.isInternal())
            .arguments(specification.getArguments())
            .build();
        return channelMono.map(channel -> {
            try {
                return channel.asyncCompletableRpc(declare);
            } catch (IOException e) {
                throw new ReactorRabbitMqException("Error during RPC call", e);
            }
        }).flatMap(future -> Mono.fromCompletionStage(future))
          .flatMap(command -> Mono.just((AMQP.Exchange.DeclareOk) command.getMethod()))
          .publishOn(resourceCreationScheduler);
    }

    public Mono<AMQP.Queue.BindOk> bind(BindingSpecification specification) {
        AMQP.Queue.Bind binding = new AMQImpl.Queue.Bind.Builder()
            .exchange(specification.getExchange())
            .queue(specification.getQueue())
            .routingKey(specification.getRoutingKey())
            .arguments(specification.getArguments())
            .build();

        return channelMono.map(channel -> {
            try {
                return channel.asyncCompletableRpc(binding);
            } catch (IOException e) {
                throw new ReactorRabbitMqException("Error during RPC call",e);
            }
        }).flatMap(future -> Mono.fromCompletionStage(future))
          .flatMap(command -> Mono.just((AMQP.Queue.BindOk) command.getMethod()))
          .publishOn(resourceCreationScheduler);
    }

    public void close() {
        if (hasConnection.getAndSet(false)) {
            try {
                // FIXME use timeout on block (should be a parameter of the Sender)
                connectionMono.block().close();
            } catch (IOException e) {
                throw new ReactorRabbitMqException(e);
            }
        }
        if (this.privateConnectionSubscriptionScheduler) {
            this.connectionSubscriptionScheduler.dispose();
        }
        if (this.privateResourceCreationScheduler) {
            this.resourceCreationScheduler.dispose();
        }
    }

    private enum SubscriberState {
        INIT,
        ACTIVE,
        OUTBOUND_DONE,
        COMPLETE
    }

    private static class PublishConfirmOperator
            extends FluxOperator<OutboundMessage, OutboundMessageResult> {

        private final Channel channel;

        public PublishConfirmOperator(Publisher<OutboundMessage> source, Channel channel) {
            super(Flux.from(source));
            this.channel = channel;
        }

        @Override
        public void subscribe(CoreSubscriber<? super OutboundMessageResult> actual) {
            source.subscribe(new PublishConfirmSubscriber(channel, actual));
        }
    }

    private static class PublishConfirmSubscriber implements
        CoreSubscriber<OutboundMessage> {

        private final AtomicReference<SubscriberState> state = new AtomicReference<>(SubscriberState.INIT);

        private final AtomicReference<Throwable> firstException = new AtomicReference<Throwable>();

        private final ConcurrentNavigableMap<Long, OutboundMessage> unconfirmed = new ConcurrentSkipListMap<>();

        private final Channel channel;

        private final Subscriber<? super OutboundMessageResult> subscriber;

        private PublishConfirmSubscriber(Channel channel, Subscriber<? super OutboundMessageResult> subscriber) {
            this.channel = channel;
            this.subscriber = subscriber;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            channel.addConfirmListener(new ConfirmListener() {

                @Override
                public void handleAck(long deliveryTag, boolean multiple) throws IOException {
                    handleAckNack(deliveryTag, multiple, true);
                }

                @Override
                public void handleNack(long deliveryTag, boolean multiple) throws IOException {
                    handleAckNack(deliveryTag, multiple, false);
                }

                private void handleAckNack(long deliveryTag, boolean multiple, boolean ack) {
                    if (multiple) {
                        try {
                            ConcurrentNavigableMap<Long, OutboundMessage> unconfirmedToSend = unconfirmed.headMap(deliveryTag, true);
                            Iterator<Map.Entry<Long, OutboundMessage>> iterator = unconfirmedToSend.entrySet().iterator();
                            while (iterator.hasNext()) {
                                subscriber.onNext(new OutboundMessageResult(iterator.next().getValue(), ack));
                                iterator.remove();
                            }
                        } catch (Exception e) {
                            handleError(e, null);
                        }
                    } else {
                        OutboundMessage outboundMessage = unconfirmed.get(deliveryTag);
                        try {
                            unconfirmed.remove(deliveryTag);
                            subscriber.onNext(new OutboundMessageResult(outboundMessage, ack));
                        } catch (Exception e) {
                            handleError(e, new OutboundMessageResult(outboundMessage, ack));
                        }
                    }
                    if (unconfirmed.size() == 0) {
                        new Thread(() -> {
                            // confirmation listeners are executed in the IO reading thread
                            // so we need to complete in another thread
                            maybeComplete();
                        }).start();
                    }
                }
            });
            state.set(SubscriberState.ACTIVE);
            subscriber.onSubscribe(subscription);
        }

        @Override
        public void onNext(OutboundMessage message) {
            if (checkComplete(message))
                return;

            long nextPublishSeqNo = channel.getNextPublishSeqNo();
            try {
                unconfirmed.putIfAbsent(nextPublishSeqNo, message);
                channel.basicPublish(
                    message.getExchange(),
                    message.getRoutingKey(),
                    message.getProperties(),
                    message.getBody()
                );
            } catch (Exception e) {
                unconfirmed.remove(nextPublishSeqNo);
                handleError(e, new OutboundMessageResult(message, false));
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (state.compareAndSet(SubscriberState.ACTIVE, SubscriberState.COMPLETE) ||
                state.compareAndSet(SubscriberState.OUTBOUND_DONE, SubscriberState.COMPLETE)) {
                closeResources();
                // complete the flux state
                subscriber.onError(throwable);
            } else if (firstException.compareAndSet(null, throwable) && state.get() == SubscriberState.COMPLETE) {
                // already completed, drop the error
                Operators.onErrorDropped(throwable, currentContext());
            }
        }

        @Override
        public void onComplete() {
            if (state.compareAndSet(SubscriberState.ACTIVE, SubscriberState.OUTBOUND_DONE) && unconfirmed.size() == 0) {
                maybeComplete();
            }
        }

        private void handleError(Exception e, OutboundMessageResult result) {
            LOGGER.error("error in publish confirm sending", e);
            boolean complete = checkComplete(e);
            firstException.compareAndSet(null, e);
            if (!complete) {
                if (result != null) {
                    subscriber.onNext(result);
                }
                onError(e);
            }
        }

        private void maybeComplete() {
            boolean done = state.compareAndSet(SubscriberState.OUTBOUND_DONE, SubscriberState.COMPLETE);
            if (done) {
                closeResources();
                subscriber.onComplete();
            }
        }

        private void closeResources() {
            try {
                if (channel.isOpen()) {
                    channel.close();
                }
            } catch (Exception e) {
                // not much we can do here
            }
        }

        public <T> boolean checkComplete(T t) {
            boolean complete = state.get() == SubscriberState.COMPLETE;
            if (complete && firstException.get() == null) {
                Operators.onNextDropped(t, currentContext());
            }
            return complete;
        }
    }

    private static class ChannelCreationFunction implements Function<Connection, Channel> {

        @Override
        public Channel apply(Connection connection) {
            try {
                return connection.createChannel();
            } catch (IOException e) {
                throw new ReactorRabbitMqException("Error while creating channel", e);
            }
        }
    }
}
