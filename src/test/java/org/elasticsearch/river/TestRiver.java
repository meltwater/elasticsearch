/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.river;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;

/**
 *
 */
public class TestRiver extends AbstractRiverComponent implements River {

    private final String riverIndexName;
    private final Client client;

    @Inject
    public TestRiver(RiverName riverName, @RiverIndexName String riverIndexName, RiverSettings settings, Client client) {
        super(riverName, settings);
        logger.info("create");
        this.client = client;
        this.riverIndexName = riverIndexName;
    }

    @Override
    public void start() {
        logger.info("start");
        if (!client.prepareIndex(riverIndexName, riverName.getName(), "_status").setSource("created", true).get().isCreated()) {
            logger.warn("_status record wasn't created");
        }
    }

    @Override
    public void close() {
        logger.info("close");
    }
}
