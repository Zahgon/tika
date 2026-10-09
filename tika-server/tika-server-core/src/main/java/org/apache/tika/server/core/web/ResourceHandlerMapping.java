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

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Maps every request to a tika-server endpoint with the {@link ResourceSelector}. A request no
 * endpoint serves fails here with its 404, 405, 406 or 415; an OPTIONS request without an
 * endpoint of its own is answered with a {@link FixedResponse}. Being the only mapping, it also
 * answers CORS preflight requests the way any other OPTIONS request is answered.
 */
public class ResourceHandlerMapping implements HandlerMapping, Ordered {

    /**
     * Request attribute holding the {@link ResourceSelector.Selection} of the request.
     */
    public static final String SELECTION_ATTRIBUTE = ResourceHandlerMapping.class.getName() + ".selection";

    private final ResourceSelector selector;
    private final List<PreMatchFilter> preMatchFilters;

    public ResourceHandlerMapping(ResourceSelector selector, List<PreMatchFilter> preMatchFilters) {
        this.selector = selector;
        this.preMatchFilters = List.copyOf(preMatchFilters);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    public ResourceSelector getSelector() {
        return selector;
    }

    @Override
    public HandlerExecutionChain getHandler(HttpServletRequest request) {
        return new HandlerExecutionChain(select(request));
    }

    private Object select(HttpServletRequest request) {
        RequestBody.prepare(request);
        RequestContext context = RequestContext.of(request);
        for (PreMatchFilter filter : preMatchFilters) {
            ResponseEntity<?> response = filter.filter(request, context, selector);
            if (response != null) {
                return new FixedResponse(response);
            }
        }
        String contentType = context.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        String accept = context.getHeaders().getFirst(HttpHeaders.ACCEPT);
        ResourceSelector.Selection selection = selector.select(request.getMethod(), context.getRawPath(), contentType, accept);
        if (selection == null) {
            return new FixedResponse(ResponseEntity.ok().headers(selector.allowResponseHeaders()).build());
        }
        request.setAttribute(SELECTION_ATTRIBUTE, selection);
        return selection;
    }

    /**
     * Answers a request with a fixed response: an OPTIONS request no endpoint serves (200 with an
     * <code>Allow</code> header), or one a {@link PreMatchFilter} answered.
     *
     * @param response the response
     */
    public record FixedResponse(ResponseEntity<?> response) {
    }
}
