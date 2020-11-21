/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.utils.pubsub;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.ditto.services.utils.pubsub.config.PubSubConfig;
import org.eclipse.ditto.services.utils.pubsub.ddata.Hashes;
import org.eclipse.ditto.services.utils.pubsub.extractors.AckExtractor;
import org.eclipse.ditto.services.utils.pubsub.extractors.PubSubTopicExtractor;
import org.eclipse.ditto.signals.acks.base.Acknowledgement;

import akka.actor.ActorContext;
import akka.actor.ActorRef;

/**
 * Pub-sub factory for tests. Messages are strings. Topics of a message are its prefixes.
 */
public final class TestPubSubFactory extends AbstractPubSubFactory<Acknowledgement> implements Hashes {

    private static final DDataProvider PROVIDER = DDataProvider.of("dc-default");
    private static final LiteralDDataProvider ACKS_PROVIDER = LiteralDDataProvider.of("dc-default", "acks");

    private final Collection<Integer> seeds;

    private TestPubSubFactory(final ActorContext context,
            final PubSubTopicExtractor<Acknowledgement> topicExtractor,
            final AckExtractor<Acknowledgement> ackExtractor,
            final DistributedAcks distributedAcks) {
        super(context, Acknowledgement.class, topicExtractor, PROVIDER, ackExtractor, distributedAcks);
        final PubSubConfig config = PubSubConfig.of(context.system().settings().config().getConfig("ditto.pubsub"));
        seeds = Hashes.digestStringsToIntegers(config.getSeed(), Hashes.HASH_FAMILY_SIZE);
    }

    static DistributedAcks startDistributedAcks(final ActorContext context) {
        return DistributedAcksImpl.create(context, "dc-default", ACKS_PROVIDER);
    }

    static TestPubSubFactory of(final ActorContext context, final AckExtractor<Acknowledgement> ackExtractor,
            final DistributedAcks distributedAcks) {
        return new TestPubSubFactory(context, TestPubSubFactory::getPrefixes, ackExtractor,
                distributedAcks);
    }

    /**
     * @return subscribers of a topic in the distributed data.
     */
    CompletionStage<Collection<ActorRef>> getSubscribers() {
        return ddata.getReader().read().thenApply(Map::keySet);
    }

    @Override
    public Collection<Integer> getSeeds() {
        return seeds;
    }

    private static Collection<String> getPrefixes(final Acknowledgement acknowledgement) {
        final String string = acknowledgement.getLabel().toString();
        return IntStream.range(0, string.length())
                .mapToObj(i -> string.substring(0, i + 1))
                .collect(Collectors.toList());
    }
}
