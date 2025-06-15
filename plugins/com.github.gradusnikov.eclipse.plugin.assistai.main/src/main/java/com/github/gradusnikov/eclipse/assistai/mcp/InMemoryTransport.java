package com.github.gradusnikov.eclipse.assistai.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.ClientMcpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

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
     * @param objectMapper The ObjectMapper to use for serialization
     * @return A pair containing client and server transports
     */
    public static TransportPair createTransportPair(ObjectMapper objectMapper) {
        // Create message queues for bidirectional communication
        BlockingQueue<McpSchema.JSONRPCMessage> clientToServerQueue = new LinkedBlockingQueue<>();
        BlockingQueue<McpSchema.JSONRPCMessage> serverToClientQueue = new LinkedBlockingQueue<>();
        
        // Create error sinks
        Sinks.Many<String> clientErrorSink = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<String> serverErrorSink = Sinks.many().unicast().onBackpressureBuffer();

        // Create the transports
        InMemoryClientTransport clientTransport = new InMemoryClientTransport(
                serverToClientQueue, clientToServerQueue, clientErrorSink, objectMapper);
        
        InMemoryServerTransport serverTransport = new InMemoryServerTransport(
                clientToServerQueue, serverToClientQueue, serverErrorSink, objectMapper);

        return new TransportPair(clientTransport, serverTransport);
    }

    /**
     * Creates a connected pair of client and server transports with a default ObjectMapper.
     * 
     * @return A pair containing client and server transports
     */
    public static TransportPair createTransportPair() {
        return createTransportPair(new ObjectMapper());
    }

    /**
     * A container for the paired client and server transports.
     */
    public static class TransportPair {
        private final ClientMcpTransport clientTransport;
        private final ServerMcpTransport serverTransport;

        TransportPair(ClientMcpTransport clientTransport, ServerMcpTransport serverTransport) {
            this.clientTransport = clientTransport;
            this.serverTransport = serverTransport;
        }

        public ClientMcpTransport getClientTransport() {
            return clientTransport;
        }

        public ServerMcpTransport getServerTransport() {
            return serverTransport;
        }
    }

    /**
     * Implementation of the client side of the in-memory transport.
     */
    private static class InMemoryClientTransport implements ClientMcpTransport {
        private final BlockingQueue<McpSchema.JSONRPCMessage> inboundQueue;
        private final BlockingQueue<McpSchema.JSONRPCMessage> outboundQueue;
        private final Sinks.Many<McpSchema.JSONRPCMessage> inboundSink;
        private final Sinks.Many<String> errorSink;
        private final ObjectMapper objectMapper;
        private volatile boolean isClosing = false;

        InMemoryClientTransport(
                BlockingQueue<McpSchema.JSONRPCMessage> inboundQueue,
                BlockingQueue<McpSchema.JSONRPCMessage> outboundQueue,
                Sinks.Many<String> errorSink,
                ObjectMapper objectMapper) {
            
            Assert.notNull(inboundQueue, "Inbound queue cannot be null");
            Assert.notNull(outboundQueue, "Outbound queue cannot be null");
            Assert.notNull(errorSink, "Error sink cannot be null");
            Assert.notNull(objectMapper, "ObjectMapper cannot be null");
            
            this.inboundQueue = inboundQueue;
            this.outboundQueue = outboundQueue;
            this.errorSink = errorSink;
            this.objectMapper = objectMapper;
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
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        public Sinks.Many<String> getErrorSink() {
            return errorSink;
        }
    }

    /**
     * Implementation of the server side of the in-memory transport.
     */
    private static class InMemoryServerTransport implements ServerMcpTransport {
        private final BlockingQueue<McpSchema.JSONRPCMessage> inboundQueue;
        private final BlockingQueue<McpSchema.JSONRPCMessage> outboundQueue;
        private final Sinks.Many<McpSchema.JSONRPCMessage> inboundSink;
        private final Sinks.Many<String> errorSink;
        private final ObjectMapper objectMapper;
        private volatile boolean isClosing = false;

        InMemoryServerTransport(
                BlockingQueue<McpSchema.JSONRPCMessage> inboundQueue,
                BlockingQueue<McpSchema.JSONRPCMessage> outboundQueue,
                Sinks.Many<String> errorSink,
                ObjectMapper objectMapper) {
            
            Assert.notNull(inboundQueue, "Inbound queue cannot be null");
            Assert.notNull(outboundQueue, "Outbound queue cannot be null");
            Assert.notNull(errorSink, "Error sink cannot be null");
            Assert.notNull(objectMapper, "ObjectMapper cannot be null");
            
            this.inboundQueue = inboundQueue;
            this.outboundQueue = outboundQueue;
            this.errorSink = errorSink;
            this.objectMapper = objectMapper;
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
//                                logger.error("Failed to emit inbound message: {}", message);
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
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        public Sinks.Many<String> getErrorSink() {
            return errorSink;
        }
    }
}