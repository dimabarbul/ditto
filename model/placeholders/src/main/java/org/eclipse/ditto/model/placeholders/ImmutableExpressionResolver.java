/*
 * Copyright (c) 2017-2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.model.placeholders;

import static org.eclipse.ditto.model.placeholders.Expression.SEPARATOR;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.model.base.common.Placeholders;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.connectivity.UnresolvedPlaceholderException;

/**
 * Immutable implementation of {@link ExpressionResolver} containing the logic of how an expression is resolved.
 */
@Immutable
final class ImmutableExpressionResolver implements ExpressionResolver {

    private static final String PIPE_PATTERN_STR = "(\\s+\\|\\s+)";
    private static final Pattern PIPE_PATTERN = Pattern.compile(PIPE_PATTERN_STR);

    private static final Function<String, DittoRuntimeException> UNRESOLVED_INPUT_HANDLER = unresolvedInput ->
            UnresolvedPlaceholderException.newBuilder(unresolvedInput).build();

    private final List<PlaceholderResolver<?>> placeholderResolvers;

    ImmutableExpressionResolver(final List<PlaceholderResolver<?>> placeholderResolvers) {
        this.placeholderResolvers = Collections.unmodifiableList(new ArrayList<>(placeholderResolvers));
    }

    @Override
    public String resolve(final String expressionTemplate, final boolean allowUnresolved) {

        String templateInWork = expressionTemplate;
        int placeholdersIdx = 0;
        while (Placeholders.containsAnyPlaceholder(templateInWork) && placeholdersIdx < placeholderResolvers.size()) {
            final PlaceholderResolver<?> placeholderResolver = placeholderResolvers.get(placeholdersIdx);

            final Function<String, Optional<String>> placeholderReplacerFunction =
                    makePlaceholderReplacerFunction(placeholderResolver);

            placeholdersIdx++;
            final boolean isLastPlaceholderResolver = placeholdersIdx == placeholderResolvers.size();
            templateInWork = Placeholders.substitute(templateInWork, placeholderReplacerFunction,
                    UNRESOLVED_INPUT_HANDLER, !isLastPlaceholderResolver || allowUnresolved);
        }

        return templateInWork;
    }

    @Override
    public Optional<String> resolveSinglePlaceholder(final String placeholder) {

        for (final PlaceholderResolver<?> resolver : placeholderResolvers) {

            final Optional<String> resolvedOpt = makePlaceholderReplacerFunction(resolver).apply(placeholder);
            if (resolvedOpt.isPresent()) {
                return resolvedOpt;
            }
        }

        return Optional.empty();
    }

    private Function<String, Optional<String>> makePlaceholderReplacerFunction(
            final PlaceholderResolver<?> placeholderResolver) {

        return template -> {

            final List<String> pipelineStagesExpressions = PIPE_PATTERN.splitAsStream(template)
                    .map(String::trim)
                    .collect(Collectors.toList());

            final String placeholderTemplate =
                    pipelineStagesExpressions.get(0); // the first pipeline stage has to start with a placeholder

            final List<String> pipelineStages = pipelineStagesExpressions.stream()
                    .skip(1)
                    .map(String::trim)
                    .collect(Collectors.toList());
            final Pipeline pipeline = new ImmutablePipeline(ImmutableFunctionExpression.INSTANCE, pipelineStages);

            final Optional<String> pipelineInput = resolvePlaceholder(placeholderResolver, placeholderTemplate);
            return pipeline.execute(pipelineInput, this);
        };
    }

    private Optional<String> resolvePlaceholder(final PlaceholderResolver<?> prefixed, final String placeholder) {

        final int separatorIndex = placeholder.indexOf(SEPARATOR);
        if (separatorIndex == -1) {
            throw UnresolvedPlaceholderException.newBuilder(placeholder).build();
        }
        final String prefix = placeholder.substring(0, separatorIndex).trim();
        final String placeholderWithoutPrefix = placeholder.substring(separatorIndex + 1).trim();

        return Optional.of(prefixed)
                .filter(p -> prefix.equals(p.getPrefix()))
                .filter(p -> p.supports(placeholderWithoutPrefix))
                .flatMap(p -> prefixed.resolve(placeholderWithoutPrefix));
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ImmutableExpressionResolver)) {
            return false;
        }
        final ImmutableExpressionResolver that = (ImmutableExpressionResolver) o;
        return Objects.equals(placeholderResolvers, that.placeholderResolvers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(placeholderResolvers);
    }


    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" +
                "placeholderResolvers=" + placeholderResolvers +
                "]";
    }

}
