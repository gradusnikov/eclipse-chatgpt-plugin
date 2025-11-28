package com.github.gradusnikov.eclipse.assistai.mcp;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * An in-memory transport implementation that allows client and server to communicate
 * within the same JVM process. This transport is useful for testing and scenarios where
 * both client and server are running in the same application.
 * 
 * <p>This implementation creates a pair of transports (client and server) that are linked to each other,
 * with messages from one being delivered to the other.</p>
 */
public class InMemoryTransport 
{
    /**
     * Creates a connected pair of client and server transports.
     * 
     * @param jsonMapper The McpJsonMapper to use for serialization
     * @return A pair containing client and server transports
     */
    public static TransportPair createTransportPair(McpJsonMapper jsonMapper) {
        // Create message queues for bidirectional communication
        BlockingQueue<McpSchema.JSONRPCMessage> clientToServerQueue = new LinkedBlockingQueue<>();
        BlockingQueue<McpSchema.JSONRPCMessage> serverToClientQueue = new LinkedBlockingQueue<>();
        
        // Create error sinks
        Sinks.Many<String> clientErrorSink = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<String> serverErrorSink = Sinks.many().unicast().onBackpressureBuffer();

        // Create the transports
        InMemoryClientTransport clientTransport = new InMemoryClientTransport(
                serverToClientQueue, clientToServerQueue, clientErrorSink, jsonMapper);
        
        InMemoryServerTransportProvider serverTransportProvider = new InMemoryServerTransportProvider(
                jsonMapper, clientToServerQueue, serverToClientQueue, serverErrorSink);

        return new TransportPair(clientTransport, serverTransportProvider);
    }

    /**
     * Creates a connected pair of client and server transports with a default JsonMapper.
     * 
     * @return A pair containing client and server transports
     */
    public static TransportPair createTransportPair() {
        McpJsonMapperSupplier jsonMapperSupplier = new JacksonMcpJsonMapperSupplier();
        return createTransportPair(jsonMapperSupplier.get());
    }

    /**
     * A container for the paired client and server transports.
     */
    public static class TransportPair {
        private final McpClientTransport clientTransport;
        private final McpServerTransportProvider serverTransport;

        TransportPair(McpClientTransport clientTransport, McpServerTransportProvider serverTransport) {
            this.clientTransport = clientTransport;
            this.serverTransport = serverTransport;
        }

        public McpClientTransport getClientTransport() {
            return clientTransport;
        }

        public McpServerTransportProvider getServerTransport() {
            return serverTransport;
        }
    }

    /**
     * Implementation of the client side of the in-memory transport.
     */
    private static class InMemoryClientTransport implements McpClientTransport {
        private final BlockingQueue<McpSchema.JSONRPCMessage> inboundQueue;
        private final BlockingQueue<McpSchema.JSONRPCMessage> outboundQueue;
        private final Sinks.Many<McpSchema.JSONRPCMessage> inboundSink;
        private final Sinks.Many<String> errorSink;
        private final McpJsonMapper jsonMapper;
        private volatile boolean isClosing = false;

        InMemoryClientTransport(
                BlockingQueue<McpSchema.JSONRPCMessage> inboundQueue,
                BlockingQueue<McpSchema.JSONRPCMessage> outboundQueue,
                Sinks.Many<String> errorSink,
                McpJsonMapper jsonMapper) {
            
            Assert.notNull(inboundQueue, "Inbound queue cannot be null");
            Assert.notNull(outboundQueue, "Outbound queue cannot be null");
            Assert.notNull(errorSink, "Error sink cannot be null");
            Assert.notNull(jsonMapper, "JsonMapper cannot be null");
            
            this.inboundQueue = inboundQueue;
            this.outboundQueue = outboundQueue;
            this.errorSink = errorSink;
            this.jsonMapper = jsonMapper;
            this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
        }

        @Override
        public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
            return Mono.fromRunnable(() -> {
                // Set up message processing
                setupMessageHandler(handler);
                
                // Start a background thread to poll messages from the inbound queue
                Schedulers.boundedElastic().schedule(() -> {
                    try {
                        while (!isClosing) {
                            McpSchema.JSONRPCMessage message = inboundQueue.take();
                            if (!inboundSink.tryEmitNext(message).isSuccess() && !isClosing) {
//                                logger.error("Failed to emit inbound message: " + message);
                                break;
                            }
                        }
                    } catch (InterruptedException e) {
                        if (!isClosing) {
//                            logger.error("Interrupted while waiting for messages", e);
                        }
                        Thread.currentThread().interrupt();
                    } finally {
                        if (!isClosing) {
                            isClosing = true;
                            inboundSink.tryEmitComplete();
                        }
                    }
                });
            }).subscribeOn(Schedulers.boundedElastic()).then();
        }

        private void setupMessageHandler(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
            this.inboundSink.asFlux()
                .flatMap(message -> {
                    // Process each message and ignore its result (convert to Mono<Void>)
                    return Mono.just(message)
                        .transform(handler)
                        .contextWrite(ctx -> ctx.put("observation", "inMemoryObservation"))
                        .then();  // Convert to Mono<Void>
                })
                .subscribe();
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.fromCallable(() -> {
                if (isClosing) {
                    return false;
                }
                try {
                    outboundQueue.put(message);
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            })
            .flatMap(success -> success ? Mono.empty() : Mono.error(new RuntimeException("Failed to enqueue message")))
            .subscribeOn(Schedulers.boundedElastic()).then();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                isClosing = true;
                inboundSink.tryEmitComplete();
                errorSink.tryEmitComplete();
            }).then().subscribeOn(Schedulers.boundedElastic());
        }

        @Override
        public <T> T unmarshalFrom( Object data, TypeRef<T> typeRef )
        {
            return jsonMapper.convertValue( data, typeRef );
        }
    }

    /**
     * Provider implementation for in-memory server transport.
     * Follows the pattern from StdioServerTransportProvider.
     */
    private static class InMemoryServerTransportProvider implements McpServerTransportProvider {
        private final McpJsonMapper jsonMapper;
        private final BlockingQueue<McpSchema.JSONRPCMessage> inboundQueue;
        private final BlockingQueue<McpSchema.JSONRPCMessage> outboundQueue;
        private final Sinks.Many<String> errorSink;
        private McpServerSession session;
        private final AtomicBoolean isClosing = new AtomicBoolean(false);

        InMemoryServerTransportProvider(
                McpJsonMapper jsonMapper,
                BlockingQueue<McpSchema.JSONRPCMessage> inboundQueue,
                BlockingQueue<McpSchema.JSONRPCMessage> outboundQueue,
                Sinks.Many<String> errorSink) {
            
            Assert.notNull(jsonMapper, "JsonMapper cannot be null");
            Assert.notNull(inboundQueue, "Inbound queue cannot be null");
            Assert.notNull(outboundQueue, "Outbound queue cannot be null");
            Assert.notNull(errorSink, "Error sink cannot be null");
            
            this.jsonMapper = jsonMapper;
            this.inboundQueue = inboundQueue;
            this.outboundQueue = outboundQueue;
            this.errorSink = errorSink;
        }

        @Override
        public List<String> protocolVersions() {
            return List.of(ProtocolVersions.MCP_2024_11_05);
        }

        @Override
        public void setSessionFactory(McpServerSession.Factory sessionFactory) {
            // Create a single session for the in-memory connection
            var transport = new InMemorySessionTransport();
            this.session = sessionFactory.create(transport);
            transport.initProcessing();
        }

        @Override
        public Mono<Void> notifyClients(String method, Object params) {
            if (this.session == null) {
                return Mono.error(new RuntimeException("No session to notify"));
            }
            return this.session.sendNotification(method, params);
        }

        @Override
        public Mono<Void> closeGracefully() {
            if (this.session == null) {
                return Mono.empty();
            }
            return this.session.closeGracefully();
        }

        /**
         * Implementation of McpServerTransport for the in-memory session.
         */
        private class InMemorySessionTransport implements McpServerTransport {
            private final Sinks.Many<McpSchema.JSONRPCMessage> inboundSink;
            private final Sinks.Many<McpSchema.JSONRPCMessage> outboundSink;
            private final AtomicBoolean isStarted = new AtomicBoolean(false);

            public InMemorySessionTransport() {
                this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
                this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();
            }

            @Override
            public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                return Mono.defer(() -> {
                    if (outboundSink.tryEmitNext(message).isSuccess()) {
                        return Mono.empty();
                    } else {
                        return Mono.error(new RuntimeException("Failed to enqueue message"));
                    }
                });
            }

            @Override
            public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
                return jsonMapper.convertValue(data, typeRef);
            }

            @Override
            public Mono<Void> closeGracefully() {
                return Mono.fromRunnable(() -> {
                    isClosing.set(true);
                    inboundSink.tryEmitComplete();
                });
            }

            @Override
            public void close() {
                isClosing.set(true);
            }

            private void initProcessing() {
                handleIncomingMessages();
                startInboundProcessing();
                startOutboundProcessing();
            }

            private void handleIncomingMessages() {
                this.inboundSink.asFlux()
                    .flatMap(message -> session.handle(message))
                    .doOnTerminate(() -> {
                        this.outboundSink.tryEmitComplete();
                    })
                    .subscribe();
            }

            private void startInboundProcessing() {
                if (isStarted.compareAndSet(false, true)) {
                    Schedulers.boundedElastic().schedule(() -> {
                        try {
                            while (!isClosing.get()) {
                                McpSchema.JSONRPCMessage message = inboundQueue.take();
                                if (!this.inboundSink.tryEmitNext(message).isSuccess()) {
                                    break;
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            isClosing.set(true);
                            if (session != null) {
                                session.close();
                            }
                            inboundSink.tryEmitComplete();
                        }
                    });
                }
            }

            private void startOutboundProcessing() {
                outboundSink.asFlux()
                    .publishOn(Schedulers.boundedElastic())
                    .handle((message, sink) -> {
                        if (message != null && !isClosing.get()) {
                            try {
                                outboundQueue.put(message);
                                sink.next(message);
                            } catch (InterruptedException e) {
                                if (!isClosing.get()) {
                                    sink.error(new RuntimeException(e));
                                }
                                Thread.currentThread().interrupt();
                            }
                        } else if (isClosing.get()) {
                            sink.complete();
                        }
                    })
                    .doOnComplete(() -> isClosing.set(true))
                    .doOnError(e -> {
                        if (!isClosing.get()) {
                            isClosing.set(true);
                        }
                    })
                    .subscribe();
            }
        }
    }
}
