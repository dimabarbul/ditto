/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
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

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttClientIdentifier;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import org.eclipse.ditto.base.model.common.ConditionChecker;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.message.publish.GenericMqttPublish;

import java.util.Optional;

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

/**
 * Base implementation of {@link GenericMqttUnsolicitedPublishesClient}.
 */
abstract class BaseGenericMqttUnsolicitedPublishesClient<C extends MqttClient> implements GenericMqttUnsolicitedPublishesClient {

    private final C mqttClient;

    private BaseGenericMqttUnsolicitedPublishesClient(final C mqttClient) {
        this.mqttClient = mqttClient;
    }

    static BaseGenericMqttUnsolicitedPublishesClient<Mqtt3AsyncClient> ofMqtt3AsyncClient(
            final Mqtt3AsyncClient mqtt3AsyncClient
    ) {
        return new Mqtt3UnsolicitedPublishesClient(mqtt3AsyncClient);
    }

    static BaseGenericMqttUnsolicitedPublishesClient<Mqtt5AsyncClient> ofMqtt5AsyncClient(
            final Mqtt5AsyncClient mqtt5AsyncClient
    ) {
        return new Mqtt5UnsolicitedPublishesClient(mqtt5AsyncClient);
    }

    @Override
    public String toString() {
        final var mqttClientConfig = mqttClient.getConfig();
        final var clientIdentifier = mqttClientConfig.getClientIdentifier();
        return clientIdentifier.toString();
    }

    private static final class Mqtt3UnsolicitedPublishesClient extends BaseGenericMqttUnsolicitedPublishesClient<Mqtt3AsyncClient> {

        private final Flowable<Mqtt3Publish> unsolicitedPublishes;
        private final Disposable unsolicitedPublishesDisposable;
        private final Optional<MqttClientIdentifier> clientIdentifier;
        private boolean isDisposed = false;

        private Mqtt3UnsolicitedPublishesClient(final Mqtt3AsyncClient mqtt3AsyncClient) {
            super(ConditionChecker.checkNotNull(mqtt3AsyncClient, "mqtt3AsyncClient"));

            clientIdentifier = mqtt3AsyncClient.getConfig().getClientIdentifier();

            System.out.println("Subscribing to unsolicited publishes in unsolicited publishes client " + clientIdentifier);
            unsolicitedPublishes = mqtt3AsyncClient.toRx()
                    .publishes(MqttGlobalPublishFilter.UNSOLICITED, true)
                    .doOnEach(s -> System.out.println("In unsolicited publishes for client " + clientIdentifier + ": " + s.toString()))
                    .replay()
                    .autoConnect();
            unsolicitedPublishesDisposable = unsolicitedPublishes.subscribe();
        }

        @Override
        public Flowable<GenericMqttPublish> unsolicitedPublishes() {
            System.out.println("Returning unsolicited flowable from unsolicited publishes client " + clientIdentifier);
            return unsolicitedPublishes.map(GenericMqttPublish::ofMqtt3Publish);
        }

        @Override
        public void dispose() {
            System.out.println("Disposing unsolicited flowable for client " + clientIdentifier);
            unsolicitedPublishesDisposable.dispose();
            isDisposed = true;
        }

        @Override
        public boolean isDisposed() {
            return isDisposed;
        }

    }

    private static final class Mqtt5UnsolicitedPublishesClient extends BaseGenericMqttUnsolicitedPublishesClient<Mqtt5AsyncClient> {

        private final Flowable<Mqtt5Publish> unsolicitedPublishes;
        private final Disposable unsolicitedPublishesDisposable;
        private final Optional<MqttClientIdentifier> clientIdentifier;
        private boolean isDisposed = false;

        private Mqtt5UnsolicitedPublishesClient(final Mqtt5AsyncClient mqtt5AsyncClient) {
            super(checkNotNull(mqtt5AsyncClient, "mqtt5AsyncClient"));

            clientIdentifier = mqtt5AsyncClient.getConfig().getClientIdentifier();

            System.out.println("Subscribing to unsolicited publishes in unsolicited publishes client " + clientIdentifier);
            unsolicitedPublishes = mqtt5AsyncClient.toRx()
                    .publishes(MqttGlobalPublishFilter.UNSOLICITED, true)
                    .doOnEach(s -> System.out.println("In unsolicited publishes for client " + clientIdentifier + ": " + s.toString()))
                    .replay()
                    .autoConnect();
            unsolicitedPublishesDisposable = unsolicitedPublishes.subscribe();
        }

        @Override
        public Flowable<GenericMqttPublish> unsolicitedPublishes() {
            System.out.println("Returning unsolicited flowable from unsolicited publishes client " + clientIdentifier);
            return unsolicitedPublishes.map(GenericMqttPublish::ofMqtt5Publish);
        }

        @Override
        public void dispose() {
            System.out.println("Disposing unsolicited flowable for client " + clientIdentifier);
            unsolicitedPublishesDisposable.dispose();
            isDisposed = true;
        }

        @Override
        public boolean isDisposed() {
            return isDisposed;
        }

    }

}
