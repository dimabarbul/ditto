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

import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.message.publish.GenericMqttPublish;

import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;

/**
 * Represents an MQTT client which is capable of reading unsolicited publishes from a broker.
 * Unsolicited publishes are the ones that have no listener registered. For example, publishes
 * from previous session (they are delivered right after the client connected and before it
 * subscribes to any topic) are unsolicited. Another example of unsolicited publish is the one
 * that is sent after the client subscribed to some topic, but before the listener is registered.
 * Unsolicited publishes are read with manual acknowledgement, so they must be acknowledged.
 * This interface abstracts MQTT protocol version 3 and 5.
 */
public interface GenericMqttUnsolicitedPublishesClient extends Disposable {

    Flowable<GenericMqttPublish> unsolicitedPublishes();

}
