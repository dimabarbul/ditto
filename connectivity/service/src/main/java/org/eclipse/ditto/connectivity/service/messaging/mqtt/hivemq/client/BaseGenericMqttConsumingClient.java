/*
 * Copyright (c) 2023 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.client;

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

import java.util.List;
import java.util.Optional;

import org.eclipse.ditto.base.model.common.ConditionChecker;
import org.eclipse.ditto.connectivity.model.Source;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.message.publish.GenericMqttPublish;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttClientIdentifier;
import com.hivemq.client.mqtt.datatypes.MqttTopicFilter;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;

import io.reactivex.Flowable;

/**
 * Base implementation of {@link GenericMqttConsumingClient}.
 */
abstract class BaseGenericMqttConsumingClient<C extends MqttClient> implements GenericMqttConsumingClient {

    private final C mqttClient;

    private BaseGenericMqttConsumingClient(final C mqttClient) {
        this.mqttClient = mqttClient;
    }

    static BaseGenericMqttConsumingClient<Mqtt3AsyncClient> ofMqtt3AsyncClient(
            final Mqtt3AsyncClient mqtt3AsyncClient
    ) {
        return new Mqtt3ConsumingClient(mqtt3AsyncClient);
    }

    static BaseGenericMqttConsumingClient<Mqtt5AsyncClient> ofMqtt5AsyncClient(
            final Mqtt5AsyncClient mqtt5AsyncClient
    ) {
        return new Mqtt5ConsumingClient(mqtt5AsyncClient);
    }

    @Override
    public String toString() {
        final var mqttClientConfig = mqttClient.getConfig();
        final var clientIdentifier = mqttClientConfig.getClientIdentifier();
        return clientIdentifier.toString();
    }

    @Override
    public Flowable<GenericMqttPublish> consumePublishes(final Source source) {
        System.out.println("Returning unsolicited flowable from consuming client for source " + source);
        final List<MqttTopicFilter> topicFilters =
                source.getAddresses().stream()
                        .map(MqttTopicFilter::of)
                        .toList();
        return consumePublishes()
                .filter(publish -> messageHasRightTopicPath(publish, topicFilters));
    }

    /**
     * Filters messages which match any of the given topic filters.
     *
     * @param genericMqttPublish a consumed MQTT message.
     * @param topicFilters the topic filters applied to consumed messages.
     * @return whether the message matches any of the given topic filters.
     */
    protected static boolean messageHasRightTopicPath(final GenericMqttPublish genericMqttPublish,
            final List<MqttTopicFilter> topicFilters) {
        return topicFilters.stream().anyMatch(filter -> filter.matches(genericMqttPublish.getTopic()));
    }

    private static final class Mqtt3ConsumingClient extends BaseGenericMqttConsumingClient<Mqtt3AsyncClient> {

        private final BufferingFlowableWrapper<Mqtt3Publish> bufferingFlowableWrapper;
        private final Optional<MqttClientIdentifier> clientIdentifier;
        private boolean isDisposed = false;

        private Mqtt3ConsumingClient(final Mqtt3AsyncClient mqtt3AsyncClient) {
            super(ConditionChecker.checkNotNull(mqtt3AsyncClient, "mqtt3AsyncClient"));

            clientIdentifier = mqtt3AsyncClient.getConfig().getClientIdentifier();

            System.out.println("Subscribing to unsolicited publishes in consuming client " + clientIdentifier);
            bufferingFlowableWrapper = BufferingFlowableWrapper.of(mqtt3AsyncClient.toRx()
                    .publishes(MqttGlobalPublishFilter.ALL, true));
        }

        @Override
        public Flowable<GenericMqttPublish> consumePublishes() {
            System.out.println("Returning unsolicited flowable from consuming client " + clientIdentifier);
            return bufferingFlowableWrapper.toFlowable().map(GenericMqttPublish::ofMqtt3Publish);
        }

        @Override
        public void stopBufferingPublishes() {
            bufferingFlowableWrapper.stopBuffering();
        }

        @Override
        public void dispose() {
            System.out.println("Disposing unsolicited flowable for client " + clientIdentifier);
            bufferingFlowableWrapper.dispose();
            isDisposed = true;
        }

        @Override
        public boolean isDisposed() {
            return isDisposed;
        }

    }

    private static final class Mqtt5ConsumingClient extends BaseGenericMqttConsumingClient<Mqtt5AsyncClient> {

        private final BufferingFlowableWrapper<Mqtt5Publish> bufferingFlowableWrapper;
        private final Optional<MqttClientIdentifier> clientIdentifier;
        private boolean isDisposed = false;

        private Mqtt5ConsumingClient(final Mqtt5AsyncClient mqtt5AsyncClient) {
            super(checkNotNull(mqtt5AsyncClient, "mqtt5AsyncClient"));

            clientIdentifier = mqtt5AsyncClient.getConfig().getClientIdentifier();

            System.out.println("Subscribing to unsolicited publishes in consuming client " + clientIdentifier);
            bufferingFlowableWrapper = BufferingFlowableWrapper.of(mqtt5AsyncClient.toRx()
                    .publishes(MqttGlobalPublishFilter.ALL, true));
        }

        @Override
        public Flowable<GenericMqttPublish> consumePublishes() {
            System.out.println("Returning unsolicited flowable from consuming client " + clientIdentifier);
            return bufferingFlowableWrapper.toFlowable().map(GenericMqttPublish::ofMqtt5Publish);
        }

        @Override
        public void stopBufferingPublishes() {
            bufferingFlowableWrapper.stopBuffering();
        }

        @Override
        public void dispose() {
            System.out.println("Disposing unsolicited flowable for client " + clientIdentifier);
            bufferingFlowableWrapper.dispose();
            isDisposed = true;
        }

        @Override
        public boolean isDisposed() {
            return isDisposed;
        }

    }

}
