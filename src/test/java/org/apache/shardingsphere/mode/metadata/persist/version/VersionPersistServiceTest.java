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

package org.apache.shardingsphere.mode.metadata.persist.version;

import org.apache.shardingsphere.mode.node.path.type.database.metadata.rule.DatabaseRuleItem;
import org.apache.shardingsphere.mode.node.path.type.database.metadata.rule.DatabaseRuleNodePath;
import org.apache.shardingsphere.mode.node.path.version.VersionNodePath;
import org.apache.shardingsphere.mode.spi.repository.PersistRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertNull;

class VersionPersistServiceTest {

    @Test
    void shouldTreatEmptyActiveVersionAsMissing() {
        PersistRepository repository = (PersistRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{PersistRepository.class}, (proxy, method, args) -> "query".equals(method.getName()) ? "" : null);
        VersionNodePath path = new VersionNodePath(new DatabaseRuleNodePath("DWD_人口", "single", new DatabaseRuleItem("default_data_source")));
        assertNull(new VersionPersistService(repository).loadContent(path));
    }
}
