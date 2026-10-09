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
package org.apache.tika.server.core.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * The response to a failure of a {@link StreamingOutput}: its body follows the output the
 * entity had written.
 *
 * @param <T> the entity type
 */
public class PartialResponse<T> extends ResponseEntity<T> {

    private final byte[] output;

    public PartialResponse(ResponseEntity<T> response, byte[] output) {
        super(response.getBody(), headers(response), response.getStatusCode());
        this.output = output;
    }

    private static HttpHeaders headers(ResponseEntity<?> response) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        return headers;
    }

    /**
     * @return what the failed entity had written
     */
    public byte[] getOutput() {
        return output;
    }
}
