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

import java.util.Optional;

/**
 * Definition of a placeholder variable.
 *
 * @param <T> the type which is required to resolve a placeholder
 */
public interface Placeholder<T> extends ExpressionStage {

    /**
     * Resolves the placeholder variable by name.
     *
     * @param source the source from which to the placeholder is resolved
     * @param name the placeholder variable name (i. e., the part after ':').
     * @return value of the placeholder variable if the placeholder name is supported, or an empty optional otherwise.
     */
    Optional<String> resolve(T source, String name);
}
