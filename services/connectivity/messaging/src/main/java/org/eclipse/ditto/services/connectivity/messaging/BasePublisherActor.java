/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.connectivity.messaging;

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;
import static org.eclipse.ditto.model.base.headers.DittoHeaderDefinition.CORRELATION_ID;
import static org.eclipse.ditto.services.connectivity.messaging.validation.ConnectionValidator.resolveConnectionIdPlaceholder;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.eclipse.ditto.model.base.acks.AcknowledgementLabel;
import org.eclipse.ditto.model.base.acks.AcknowledgementRequest;
import org.eclipse.ditto.model.base.acks.DittoAcknowledgementLabel;
import org.eclipse.ditto.model.base.common.CharsetDeterminer;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectivityModelFactory;
import org.eclipse.ditto.model.connectivity.ConnectivityStatus;
import org.eclipse.ditto.model.connectivity.GenericTarget;
import org.eclipse.ditto.model.connectivity.HeaderMapping;
import org.eclipse.ditto.model.connectivity.ReplyTarget;
import org.eclipse.ditto.model.connectivity.ResourceStatus;
import org.eclipse.ditto.model.connectivity.Source;
import org.eclipse.ditto.model.connectivity.Target;
import org.eclipse.ditto.model.placeholders.ExpressionResolver;
import org.eclipse.ditto.model.placeholders.PlaceholderFactory;
import org.eclipse.ditto.protocoladapter.ProtocolAdapter;
import org.eclipse.ditto.services.connectivity.config.ConnectionConfig;
import org.eclipse.ditto.services.connectivity.config.ConnectivityConfig;
import org.eclipse.ditto.services.connectivity.config.DittoConnectivityConfig;
import org.eclipse.ditto.services.connectivity.config.MonitoringConfig;
import org.eclipse.ditto.services.connectivity.config.MonitoringLoggerConfig;
import org.eclipse.ditto.services.connectivity.messaging.internal.ConnectionFailure;
import org.eclipse.ditto.services.connectivity.messaging.internal.ImmutableConnectionFailure;
import org.eclipse.ditto.services.connectivity.messaging.internal.RetrieveAddressStatus;
import org.eclipse.ditto.services.connectivity.messaging.monitoring.ConnectionMonitor;
import org.eclipse.ditto.services.connectivity.messaging.monitoring.ConnectionMonitorRegistry;
import org.eclipse.ditto.services.connectivity.messaging.monitoring.DefaultConnectionMonitorRegistry;
import org.eclipse.ditto.services.connectivity.messaging.monitoring.logs.ConnectionLogger;
import org.eclipse.ditto.services.connectivity.util.ConnectivityMdcEntryKey;
import org.eclipse.ditto.services.models.connectivity.ExternalMessage;
import org.eclipse.ditto.services.models.connectivity.OutboundSignal;
import org.eclipse.ditto.services.utils.akka.logging.DittoLoggerFactory;
import org.eclipse.ditto.services.utils.akka.logging.ThreadSafeDittoLoggingAdapter;
import org.eclipse.ditto.services.utils.config.DefaultScopedConfig;
import org.eclipse.ditto.signals.acks.base.Acknowledgement;
import org.eclipse.ditto.signals.acks.base.Acknowledgements;
import org.eclipse.ditto.signals.base.Signal;
import org.eclipse.ditto.signals.commands.base.CommandResponse;
import org.eclipse.ditto.signals.commands.messages.MessageCommand;
import org.eclipse.ditto.signals.commands.things.ThingCommand;
import org.eclipse.ditto.signals.events.thingsearch.SubscriptionEvent;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.japi.pf.ReceiveBuilder;

/**
 * Base class for publisher actors. Holds the map of configured targets.
 *
 * @param <T> the type of targets for this actor
 */
public abstract class BasePublisherActor<T extends PublishTarget> extends AbstractActor {

    protected final Connection connection;
    protected final Map<Target, ResourceStatus> resourceStatusMap;

    protected final ConnectivityConfig connectivityConfig;
    protected final ConnectionConfig connectionConfig;
    protected final ConnectionLogger connectionLogger;

    /**
     * Common logger for all sub-classes of BasePublisherActor as its MDC already contains the connection ID.
     */
    protected final ThreadSafeDittoLoggingAdapter logger;

    private final ConnectionMonitor responseDroppedMonitor;
    private final ConnectionMonitor responsePublishedMonitor;
    private final ConnectionMonitor responseAcknowledgedMonitor;
    private final ConnectionMonitorRegistry<ConnectionMonitor> connectionMonitorRegistry;
    private final List<Optional<ReplyTarget>> replyTargets;
    private final int acknowledgementSizeBudget;
    private final String clientId;
    protected final ExpressionResolver connectionIdResolver;

    protected BasePublisherActor(final Connection connection, final String clientId) {
        this.connection = checkNotNull(connection, "connection");
        this.clientId = checkNotNull(clientId, "clientId");
        resourceStatusMap = new HashMap<>();
        final List<Target> targets = connection.getTargets();
        targets.forEach(target ->
                resourceStatusMap.put(target,
                        ConnectivityModelFactory.newTargetStatus(getClientId(), ConnectivityStatus.OPEN,
                                target.getAddress(), "Started at " + Instant.now())));

        connectivityConfig = getConnectivityConfig();
        connectionConfig = connectivityConfig.getConnectionConfig();
        final MonitoringConfig monitoringConfig = connectivityConfig.getMonitoringConfig();
        final MonitoringLoggerConfig loggerConfig = monitoringConfig.logger();
        this.connectionLogger = ConnectionLogger.getInstance(connection.getId(), loggerConfig);
        connectionMonitorRegistry = DefaultConnectionMonitorRegistry.fromConfig(monitoringConfig);
        responseDroppedMonitor = connectionMonitorRegistry.forResponseDropped(connection);
        responsePublishedMonitor = connectionMonitorRegistry.forResponsePublished(connection);
        responseAcknowledgedMonitor = connectionMonitorRegistry.forResponseAcknowledged(connection);
        replyTargets = connection.getSources().stream().map(Source::getReplyTarget).collect(Collectors.toList());
        acknowledgementSizeBudget = connectionConfig.getAcknowledgementConfig().getIssuedMaxBytes();
        this.logger = DittoLoggerFactory.getThreadSafeDittoLoggingAdapter(this)
                .withMdcEntry(ConnectivityMdcEntryKey.CONNECTION_ID, connection.getId());

        connectionIdResolver = PlaceholderFactory.newExpressionResolver(PlaceholderFactory.newConnectionIdPlaceholder(),
                connection.getId());
    }

    private ConnectivityConfig getConnectivityConfig() {
        final ActorContext context = getContext();
        final ActorSystem actorSystem = context.getSystem();
        final ActorSystem.Settings settings = actorSystem.settings();
        final DefaultScopedConfig dittoScopedConfig = DefaultScopedConfig.dittoScoped(settings.config());
        return DittoConnectivityConfig.of(dittoScopedConfig);
    }

    @Override
    public Receive createReceive() {
        final ReceiveBuilder receiveBuilder = receiveBuilder();
        preEnhancement(receiveBuilder);

        receiveBuilder.match(OutboundSignal.MultiMapped.class, this::sendMultiMappedOutboundSignal)
                .match(RetrieveAddressStatus.class, ram -> getCurrentTargetStatus().forEach(rs ->
                        getSender().tell(rs, getSelf())));

        postEnhancement(receiveBuilder);
        return receiveBuilder.matchAny(m -> {
            logger.warning("Unknown message: {}", m);
            unhandled(m);
        }).build();
    }

    private Collection<ResourceStatus> getCurrentTargetStatus() {
        return resourceStatusMap.values();
    }

    private void sendMultiMappedOutboundSignal(final OutboundSignal.MultiMapped multiMapped) {
        final int quota = computeMaxAckPayloadBytesForSignal(multiMapped);
        final ExceptionToAcknowledgementConverter errorConverter = getExceptionConverter();
        final List<OutboundSignal.Mapped> mappedOutboundSignals = multiMapped.getMappedOutboundSignals();
        final CompletableFuture<CommandResponse<?>>[] sendMonitorAndResponseFutures = mappedOutboundSignals.stream()
                // message sending step
                .flatMap(outbound -> sendMappedOutboundSignal(outbound, quota))
                // monitor and acknowledge step
                .flatMap(sendingOrDropped -> sendingOrDropped.monitorAndAcknowledge(errorConverter).stream())
                // convert to completable future array for aggregation
                .map(CompletionStage::toCompletableFuture)
                .<CompletableFuture<CommandResponse<?>>>toArray(CompletableFuture[]::new);

        aggregateNonNullFutures(sendMonitorAndResponseFutures)
                .thenAccept(responsesList -> {
                    final ActorRef sender = multiMapped.getSender().orElse(null);

                    final Collection<Acknowledgement> acknowledgements = new ArrayList<>();
                    final Collection<CommandResponse<?>> nonAcknowledgements = new ArrayList<>();
                    responsesList.forEach(response -> {
                        if (response instanceof Acknowledgement) {
                            final Acknowledgement acknowledgement = (Acknowledgement) response;
                            if (shouldPublishAcknowledgement(acknowledgement)) {
                                acknowledgements.add(acknowledgement);
                            }
                        } else {
                            nonAcknowledgements.add(response);
                        }
                    });

                    issueAcknowledgements(multiMapped, sender, acknowledgements);
                    sendBackResponses(multiMapped, sender, nonAcknowledgements);
                })
                .exceptionally(e -> {
                    logger.withCorrelationId(multiMapped.getSource())
                            .error(e, "Message sending failed unexpectedly: <{}>", multiMapped);
                    return null;
                });
    }

    protected abstract boolean shouldPublishAcknowledgement(final Acknowledgement acknowledgement);

    private void issueAcknowledgements(final OutboundSignal.MultiMapped multiMapped, @Nullable final ActorRef sender,
            final Collection<Acknowledgement> ackList) {

        final ThreadSafeDittoLoggingAdapter l = logger.withCorrelationId(multiMapped.getSource());
        if (Objects.equals(sender, getSelf())) {
            l.debug("Not sending acks <{}> because sender is this publisher actor," +
                    " which can't handle acknowledgements.", ackList);
        } else if (!ackList.isEmpty() && sender != null) {
            final Acknowledgements aggregatedAcks = appendConnectionId(
                    Acknowledgements.of(ackList, multiMapped.getSource().getDittoHeaders()));
            l.debug("Message sent. Replying to <{}>: <{}>", sender, aggregatedAcks);
            sender.tell(aggregatedAcks, getSelf());
        } else if (ackList.isEmpty()) {
            l.debug("Message sent: No acks requested.");
        } else {
            l.error("Message sent: Acks requested, but no sender: <{}>", multiMapped.getSource());
        }
    }

    private void sendBackResponses(final OutboundSignal.MultiMapped multiMapped, @Nullable final ActorRef sender,
            final Collection<CommandResponse<?>> nonAcknowledgementsResponses) {

        final ThreadSafeDittoLoggingAdapter l = logger.withCorrelationId(multiMapped.getSource());
        if (!nonAcknowledgementsResponses.isEmpty() && sender != null) {
            nonAcknowledgementsResponses.forEach(response -> {
                l.debug("CommandResponse created from HTTP response. Replying to <{}>: <{}>", sender, response);
                sender.tell(response, getSelf());
            });
        } else if (nonAcknowledgementsResponses.isEmpty()) {
            l.debug("No CommandResponse created from HTTP response.");
        } else {
            l.error("CommandResponse created from HTTP response, but no sender: <{}>", multiMapped.getSource());
        }
    }

    /**
     * Gets the converter from publisher exceptions to Acknowledgements.
     * Override to handle client-specific exceptions.
     *
     * @return the converter.
     */
    protected ExceptionToAcknowledgementConverter getExceptionConverter() {
        return DefaultExceptionToAcknowledgementConverter.getInstance();
    }

    private static <T> CompletionStage<List<T>> aggregateNonNullFutures(final CompletableFuture<T>[] futures) {
        return CompletableFuture.allOf(futures)
                .thenApply(aVoid -> Arrays.stream(futures)
                        .map(CompletableFuture::join)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()));
    }

    private Acknowledgements appendConnectionId(final Acknowledgements acknowledgements) {
        return InboundMappingProcessorActor.appendConnectionIdToAcknowledgements(acknowledgements, connection.getId());
    }

    private int computeMaxAckPayloadBytesForSignal(final OutboundSignal.MultiMapped multiMapped) {
        final List<OutboundSignal.Mapped> mappedOutboundSignals = multiMapped.getMappedOutboundSignals();
        final int numberOfSignals = mappedOutboundSignals.size();
        return 0 == numberOfSignals ? acknowledgementSizeBudget : acknowledgementSizeBudget / numberOfSignals;
    }

    private Stream<SendingOrDropped> sendMappedOutboundSignal(final OutboundSignal.Mapped outbound,
            final int maxPayloadBytesForSignal) {

        final ExternalMessage message = outbound.getExternalMessage();
        final String correlationId = message.getHeaders().get(CORRELATION_ID.getKey());
        final Signal<?> outboundSource = outbound.getSource();
        final List<Target> outboundTargets = outbound.getTargets();

        final ThreadSafeDittoLoggingAdapter l = logger.withCorrelationId(correlationId);
        l.info("Publishing mapped message of type <{}> to targets <{}>", outboundSource.getType(), outboundTargets);

        final Optional<SendingContext> replyTargetSendingContext = getSendingContext(outbound);

        final List<SendingContext> sendingContexts = replyTargetSendingContext.map(List::of)
                .orElseGet(() -> outboundTargets.stream()
                        .map(target -> getSendingContextForTarget(outbound, target))
                        .collect(Collectors.toList()));

        if (sendingContexts.isEmpty()) {
            // Message dropped: neither reply-target nor target is available for the message.
            if (l.isDebugEnabled()) {
                l.debug("Message without GenericTarget dropped: <{}>", outbound.getExternalMessage());
            } else {
                l.info("Message without GenericTarget dropped.");
            }
            return Stream.empty();
        } else {
            // message not dropped
            final ExpressionResolver resolver = Resolvers.forOutbound(outbound, connection.getId());
            final int acks = (int) sendingContexts.stream().filter(SendingContext::shouldAcknowledge).count();
            final int maxPayloadBytes = acks == 0 ? maxPayloadBytesForSignal : maxPayloadBytesForSignal / acks;
            return sendingContexts.stream()
                    .map(sendingContext -> tryToPublishToGenericTarget(resolver, sendingContext,
                            maxPayloadBytesForSignal, maxPayloadBytes, l));
        }
    }

    private Optional<SendingContext> getSendingContext(final OutboundSignal.Mapped mappedOutboundSignal) {
        final Optional<SendingContext> result;
        if (isResponseOrErrorOrSearchEvent(mappedOutboundSignal)) {
            final Signal<?> source = mappedOutboundSignal.getSource();
            final DittoHeaders dittoHeaders = source.getDittoHeaders();
            result = dittoHeaders.getReplyTarget()
                    .flatMap(this::getReplyTargetByIndex)
                    .map(replyTarget -> getSendingContextForReplyTarget(mappedOutboundSignal, replyTarget));
        } else {
            result = Optional.empty();
        }
        return result;
    }

    /**
     * Checks whether the passed in {@code outboundSignal} is a response or an error or a search event.
     * Those messages are supposed to be published at the reply target of the source whence the original command came.
     *
     * @param outboundSignal the OutboundSignal to check.
     * @return {@code true} if the OutboundSignal is a response or an error, {@code false} otherwise
     */
    private static boolean isResponseOrErrorOrSearchEvent(final OutboundSignal.Mapped outboundSignal) {
        final ExternalMessage externalMessage = outboundSignal.getExternalMessage();
        return externalMessage.isResponse() ||
                externalMessage.isError() ||
                outboundSignal.getSource() instanceof SubscriptionEvent;
    }

    private Optional<ReplyTarget> getReplyTargetByIndex(final int replyTargetIndex) {
        return 0 <= replyTargetIndex && replyTargetIndex < replyTargets.size()
                ? replyTargets.get(replyTargetIndex)
                : Optional.empty();
    }

    private SendingContext getSendingContextForReplyTarget(final OutboundSignal.Mapped outboundSignal,
            final ReplyTarget replyTarget) {

        return SendingContext.newBuilder()
                .mappedOutboundSignal(outboundSignal)
                .externalMessage(outboundSignal.getExternalMessage())
                .genericTarget(replyTarget)
                .droppedMonitor(responseDroppedMonitor)
                .publishedMonitor(responsePublishedMonitor)
                .acknowledgedMonitor(responseAcknowledgedMonitor)
                .build();
    }

    private SendingContext getSendingContextForTarget(final OutboundSignal.Mapped outboundSignal, final Target target) {
        final String originalAddress = target.getOriginalAddress();
        final ConnectionMonitor publishedMonitor =
                connectionMonitorRegistry.forOutboundPublished(connection, originalAddress);

        final ConnectionMonitor droppedMonitor =
                connectionMonitorRegistry.forOutboundDropped(connection, originalAddress);

        final boolean targetAckRequested = isTargetAckRequested(outboundSignal, target);

        @Nullable final ConnectionMonitor acknowledgedMonitor = targetAckRequested
                ? connectionMonitorRegistry.forOutboundAcknowledged(connection, originalAddress)
                : null;
        @Nullable final Target autoAckTarget = targetAckRequested ? target : null;

        return SendingContext.newBuilder()
                .mappedOutboundSignal(outboundSignal)
                .externalMessage(outboundSignal.getExternalMessage())
                .genericTarget(target)
                .publishedMonitor(publishedMonitor)
                .droppedMonitor(droppedMonitor)
                .acknowledgedMonitor(acknowledgedMonitor)
                .autoAckTarget(autoAckTarget)
                .build();
    }

    private SendingOrDropped tryToPublishToGenericTarget(final ExpressionResolver resolver,
            final SendingContext sendingContext,
            final int maxTotalMessageSize,
            final int quota,
            final ThreadSafeDittoLoggingAdapter logger) {

        try {
            return publishToGenericTarget(resolver, sendingContext, maxTotalMessageSize, quota, logger);
        } catch (final Exception e) {
            return new Sending(sendingContext, CompletableFuture.failedFuture(e), connectionIdResolver, logger);
        }
    }

    private SendingOrDropped publishToGenericTarget(final ExpressionResolver resolver,
            final SendingContext sendingContext,
            final int maxTotalMessageSize,
            final int quota,
            final ThreadSafeDittoLoggingAdapter logger) {

        final OutboundSignal.Mapped outbound = sendingContext.getMappedOutboundSignal();
        final GenericTarget genericTarget = sendingContext.getGenericTarget();
        final String address = genericTarget.getAddress();
        final Optional<T> publishTargetOptional = resolveTargetAddress(resolver, address).map(this::toPublishTarget);

        final SendingOrDropped result;
        if (publishTargetOptional.isPresent()) {
            final Signal<?> outboundSource = outbound.getSource();
            logger.info("Publishing mapped message of type <{}> to address <{}>", outboundSource.getType(), address);
            logger.debug("Publishing mapped message of type <{}> to address <{}>: {}", outboundSource.getType(),
                    address, sendingContext.getExternalMessage());
            final T publishTarget = publishTargetOptional.get();
            @Nullable final Target autoAckTarget = sendingContext.getAutoAckTarget().orElse(null);
            final HeaderMapping headerMapping = genericTarget.getHeaderMapping().orElse(null);
            final ExternalMessage mappedMessage = applyHeaderMapping(resolver, outbound, headerMapping);
            final CompletionStage<CommandResponse<?>> responsesFuture = publishMessage(outboundSource,
                    autoAckTarget,
                    publishTarget,
                    mappedMessage,
                    maxTotalMessageSize,
                    quota
            );
            // set the external message after header mapping for the result of header mapping to show up in log
            result = new Sending(sendingContext.setExternalMessage(mappedMessage), responsesFuture,
                    connectionIdResolver, logger);
        } else {
            result = new Dropped(sendingContext, "Signal dropped, target address unresolved: {0}");
        }
        return result;
    }

    private static ExternalMessage applyHeaderMapping(final ExpressionResolver expressionResolver,
            final OutboundSignal.Mapped outboundSignal, @Nullable final HeaderMapping headerMapping) {

        final OutboundSignalToExternalMessage outboundSignalToExternalMessage =
                OutboundSignalToExternalMessage.newInstance(outboundSignal, expressionResolver, headerMapping);

        return outboundSignalToExternalMessage.get();
    }

    /**
     * Provides the possibility to add custom matchers before applying the default matchers of the BasePublisherActor.
     *
     * @param receiveBuilder the ReceiveBuilder to add other matchers to.
     */
    protected abstract void preEnhancement(ReceiveBuilder receiveBuilder);

    /**
     * Provides the possibility to add custom matchers after applying the default matchers of the BasePublisherActor.
     *
     * @param receiveBuilder the ReceiveBuilder to add other matchers to.
     */
    protected abstract void postEnhancement(ReceiveBuilder receiveBuilder);

    /**
     * Converts the passed {@code address} to a {@link PublishTarget} of type {@code <T>}.
     *
     * @param address the address to convert to a {@link PublishTarget} of type {@code <T>}.
     * @return the instance of type {@code <T>}
     */
    protected abstract T toPublishTarget(String address);

    /**
     * Publish a message. Construct the acknowledgement regardless of any request for diagnostic purposes.
     *
     * @param signal the nullable Target for getting even more information about the configured Target to publish to.
     * @param autoAckTarget if set, this is the Target from which {@code Acknowledgement}s should automatically be
     * produced and delivered.
     * @param publishTarget the {@link PublishTarget} to publish to.
     * @param message the {@link ExternalMessage} to publish.
     * @param maxTotalMessageSize the total max message size in bytes of the payload of an automatically created
     * response.
     * @param ackSizeQuota budget in bytes for how large the payload of this acknowledgement can be, or 0 to not
     * send any acknowledgement.
     * @return future of command responses (acknowledgements and potentially responses to live commands / live messages)
     * to reply to sender, or future of null if ackSizeQuota is 0.
     */
    protected abstract CompletionStage<CommandResponse<?>> publishMessage(Signal<?> signal,
            @Nullable Target autoAckTarget,
            T publishTarget,
            ExternalMessage message,
            int maxTotalMessageSize,
            int ackSizeQuota);

    /**
     * Decode a byte buffer according to the charset specified in an external message.
     *
     * @param buffer the byte buffer.
     * @param message the external message.
     * @return the decoded string.
     */
    protected static String decodeAsHumanReadable(@Nullable final ByteBuffer buffer, final ExternalMessage message) {
        if (buffer != null) {
            return getCharsetFromMessage(message).decode(buffer).toString();
        } else {
            return "<empty>";
        }
    }

    /**
     * Determine charset from an external message.
     *
     * @param message the external message.
     * @return its charset.
     */
    protected static Charset getCharsetFromMessage(final ExternalMessage message) {
        return message.findContentType()
                .map(BasePublisherActor::determineCharset)
                .orElse(StandardCharsets.UTF_8);
    }

    /**
     * Escalate an error to the parent to trigger a restart.
     * This is thread safe because self and parent are thread-safe.
     *
     * @param error the encountered failure.
     * @param description description of the failure.
     */
    protected void escalate(final Throwable error, final String description) {
        final ConnectionFailure failure = new ImmutableConnectionFailure(getSelf(), error, description);
        getContext().getParent().tell(failure, getSelf());
    }

    /**
     * Extract acknowledgement label from an auto-ack target.
     *
     * @param target the target.
     * @return the configured auto-ack label if any exists, or an empty optional.
     */
    protected Optional<AcknowledgementLabel> getAcknowledgementLabel(@Nullable final Target target) {
        return Optional.ofNullable(target).flatMap(Target::getIssuedAcknowledgementLabel)
                .flatMap(ackLabel -> resolveConnectionIdPlaceholder(connectionIdResolver, ackLabel));
    }

    /**
     * Get the default client ID identifying the client actor should client count be more than 1.
     *
     * @return the client identifier.
     */
    protected String getClientId() {
        return clientId;
    }

    /**
     * Resolve target address.
     * If not resolvable, the returned Optional will be empty.
     */
    private static Optional<String> resolveTargetAddress(final ExpressionResolver resolver, final String value) {
        return resolver.resolve(value).toOptional();
    }

    private static Charset determineCharset(final CharSequence contentType) {
        return CharsetDeterminer.getInstance().apply(contentType);
    }

    private boolean isTargetAckRequested(final OutboundSignal.Mapped mapped, final Target target) {
        final Signal<?> source = mapped.getSource();
        final DittoHeaders dittoHeaders = source.getDittoHeaders();
        final Set<AcknowledgementRequest> acknowledgementRequests = dittoHeaders.getAcknowledgementRequests();
        final Set<AcknowledgementLabel> requestedAcks = acknowledgementRequests.stream()
                .map(AcknowledgementRequest::getLabel)
                .collect(Collectors.toSet());

        if (target.getIssuedAcknowledgementLabel()
                .filter(DittoAcknowledgementLabel.LIVE_RESPONSE::equals)
                .isPresent()) {
            return dittoHeaders.isResponseRequired() && isLiveSignal(source);
        } else {
            return target.getIssuedAcknowledgementLabel()
                    .flatMap(ackLabel -> resolveConnectionIdPlaceholder(connectionIdResolver, ackLabel))
                    .filter(requestedAcks::contains)
                    .isPresent();
        }
    }

    private static boolean isLiveSignal(final Signal<?> signal) {
        return signal instanceof MessageCommand ||
                (signal instanceof ThingCommand && ProtocolAdapter.isLiveSignal(signal));
    }

}
