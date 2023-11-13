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
package org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.subscribing;

import java.util.Objects;

import javax.annotation.Nullable;

import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.client.MqttSubscribeException;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.message.subscribe.GenericMqttSubscribe;

/**
 * Represents the successful subscription of an MQTT client to one or more
 * MQTT topics via one particular Subscribe message ({@link GenericMqttSubscribe}).
 */
final class SubscribeSuccess extends SubscribeResult {

    private SubscribeSuccess(final org.eclipse.ditto.connectivity.model.Source connectionSource) {

        super(connectionSource);
    }

    /**
     * Returns a new instance of {@code SubscribeSuccess} for the specified arguments.
     *
     * @param connectionSource the connection source which is associated with the returned subscribe success.
     * @return the instance.
     * @throws NullPointerException if any argument is {@code null}.
     */
    static SubscribeSuccess newInstance(final org.eclipse.ditto.connectivity.model.Source connectionSource) {

        return new SubscribeSuccess(connectionSource);
    }

    @Override
    public boolean isSuccess() {
        return true;
    }

    /**
     * Throws always an IllegalStateException.
     */
    @Override
    public MqttSubscribeException getErrorOrThrow() {
        throw new IllegalStateException("Success cannot provide an error.");
    }

    @Override
    public boolean equals(@Nullable final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode());
    }

}
