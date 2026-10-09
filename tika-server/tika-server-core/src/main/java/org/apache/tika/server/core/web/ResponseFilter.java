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

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * Adjusts the headers of every response of a request that reached an endpoint (normal and
 * error responses alike), just before they are sent.
 */
public interface ResponseFilter {

    /**
     * @param request  the request
     * @param context  the request's headers and path
     * @param status   the response status
     * @param headers  the response headers, to modify
     */
    void filter(HttpServletRequest request, RequestContext context, int status, HttpHeaders headers);
}
