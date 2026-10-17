/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.mode.node.path.engine.searcher;

import org.apache.shardingsphere.mode.node.path.NodePath;
import org.apache.shardingsphere.mode.node.path.engine.generator.NodePathGenerator;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compatibility override for ShardingSphere 5.5.3 node-path matching.
 *
 * <p>The upstream matcher uses {@code \w} without Unicode mode and wraps a
 * nullable capture with {@code Optional.of}. Chinese rule item names can
 * therefore match the optional path prefix while producing a null group.</p>
 */
public final class NodePathSearcher {

    private static final String START_PATTERN = "^";

    private NodePathSearcher() {
    }

    public static Optional<String> find(final String path, final NodePathSearchCriteria criteria) {
        Matcher matcher = createPattern(criteria.getSearchExample(), criteria.isContainsChildPath()).matcher(path);
        return matcher.find() ? Optional.ofNullable(matcher.group(criteria.getGroupIndex())) : Optional.empty();
    }

    public static String get(final String path, final NodePathSearchCriteria criteria) {
        return find(path, criteria).orElseThrow(() -> new IllegalArgumentException(String.format("Can not find node segment in path: %s", path)));
    }

    public static boolean isMatchedPath(final String path, final NodePathSearchCriteria criteria) {
        return createPattern(criteria.getSearchExample(), criteria.isContainsChildPath()).matcher(path).find();
    }

    private static Pattern createPattern(final NodePath searchExample, final boolean containsChildPath) {
        String endPattern = containsChildPath ? "?" : "$";
        return Pattern.compile(START_PATTERN + NodePathGenerator.toPath(searchExample) + endPattern,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    }
}
