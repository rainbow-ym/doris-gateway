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

import org.apache.shardingsphere.mode.node.path.type.database.metadata.rule.DatabaseRuleNodePath;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NodePathSearcherUnicodeTest {

    @Test
    void shouldFindChineseNamedRuleItem() {
        NodePathSearchCriteria criteria = DatabaseRuleNodePath.createRuleItemNameSearchCriteria("DWD_人口", "mask", "tables");
        Optional<String> actual = NodePathSearcher.find("/metadata/DWD_人口/rules/mask/tables/人口关系网/active_version", criteria);
        assertEquals("人口关系网", actual.orElse(null));
    }

    @Test
    void shouldReturnEmptyInsteadOfThrowingForMissingCapture() {
        NodePathSearchCriteria criteria = DatabaseRuleNodePath.createRuleItemNameSearchCriteria("DWD_人口", "mask", "tables");
        assertFalse(NodePathSearcher.find("/metadata/DWD_人口/rules/mask/tables/", criteria).isPresent());
    }
}
