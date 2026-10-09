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
package org.apache.tika.server.core;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.tika.server.core.web.MediaTypes;
import org.apache.tika.server.core.web.ResourceComparator;
import org.apache.tika.server.core.web.ResourceModel.ResourceMethod;
import org.apache.tika.server.core.web.WebMediaType;

/**
 * Resource comparator based to produce type.
 * In an ambiguous call, request handler will be chosen based on the type of data it returns.
 */
public class ProduceTypeResourceComparator implements ResourceComparator {

    /**
     * The prioritized MediaType list.
     * The lower in list (higher index value), the higher priority it has.
     * In case of no matching in this list, it will be treated as media type all.
     */
    public static final List<WebMediaType> PRIORITIZED_MEDIA_LIST =
            Arrays.asList(WebMediaType.TEXT_PLAIN_TYPE, WebMediaType.APPLICATION_JSON_TYPE, WebMediaType.TEXT_HTML_TYPE, WebMediaType.TEXT_XML_TYPE);
    /**
     * The logger
     */
    private static final Logger LOG = LoggerFactory.getLogger(ProduceTypeResourceComparator.class);

    /**
     * Compares the method to handle.
     * Gets the maximum priority match for both handlers,
     * and chooses the handler that has the maximum priority match.
     *
     * @param oper1       the first resource handler info.
     * @param oper2       the second resource handler info.
     * @param acceptTypes the acceptable response media types of the request, best first.
     * @return value based on chosen handler. Returns -1 if first, 1 if second and 0 if no decision.
     */
    @Override
    public int compare(ResourceMethod oper1, ResourceMethod oper2, List<WebMediaType> acceptTypes) {
        LOG.debug("Accept Types : {}", acceptTypes);

        int result = compareProduceTypes(oper1, oper2, acceptTypes);

        if (result != 0) {
            String chosen = result == -1 ? oper1.getName() : oper2.getName();
            LOG.debug("Between {} and {}, {} is chosen for handling the current request", oper1.getName(), oper2.getName(), chosen);
        }

        return result;
    }

    /**
     * Compares the method to handle.
     * Gets the maximum priority match for both handlers,
     * and chooses the handler that has the maximum priority match.
     *
     * @param oper1       the first resource handler info.
     * @param oper2       the second resource handler info.
     * @param acceptTypes the list acceptable response mime type, for the message.
     * @return value based on chosen handler. Returns -1 if first, 1 if second and 0 if no decision.
     */
    private int compareProduceTypes(ResourceMethod oper1, ResourceMethod oper2, final List<WebMediaType> acceptTypes) {
        // getting matched produce type for both handlers.
        // this is required if a method can produce multiple types.
        List<WebMediaType> op1Matched = MediaTypes.intersectMimeTypes(acceptTypes, oper1.getProduceTypes(), true);
        List<WebMediaType> op2Matched = MediaTypes.intersectMimeTypes(acceptTypes, oper2.getProduceTypes(), true);

        // calculate the max priority for both handlers
        int oper1Priority = op1Matched
                .stream()
                .mapToInt(PRIORITIZED_MEDIA_LIST::indexOf)
                .max()
                .getAsInt();
        int oper2Priority = op2Matched
                .stream()
                .mapToInt(PRIORITIZED_MEDIA_LIST::indexOf)
                .max()
                .getAsInt();

        // final calculation
        return oper1Priority == oper2Priority ? 0 : (oper1Priority > oper2Priority ? -1 : 1);
    }

}
