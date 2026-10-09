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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import org.apache.tika.server.core.web.ResourceModel.ResourceMethod;

/**
 * Cross-origin resource sharing, as tika-server's <code>cors</code> setting has always applied it
 * (ported from Apache CXF's CrossOriginResourceSharingFilter).
 * <p>
 * Before routing, a request is checked against the endpoint its method would reach whatever its
 * content type and accepted types:
 * <ul>
 *     <li>An OPTIONS request with exactly one origin is a preflight. It is answered 200 at once:
 *     with the CORS headers when it names exactly one <code>Access-Control-Request-Method</code>
 *     that an endpoint of the path serves and its origin is allowed, without them otherwise. When
 *     no endpoint serves the requested method, it is routed as any OPTIONS request.</li>
 *     <li>Any other request with allowed origins (its <code>Origin</code> values, split at spaces)
 *     gets <code>Access-Control-Allow-Origin</code> and <code>Access-Control-Allow-Credentials</code>
 *     on whatever response it ends with, errors included.</li>
 * </ul>
 * The allowed origin is <code>*</code> when every origin is allowed, else the request's origins.
 */
public class CorsFilter implements PreMatchFilter, ResponseFilter {

    private static final String STATE = CorsFilter.class.getName();
    private static final String SIMPLE = "simple";
    private static final String PREFLIGHT = "preflight";
    private static final String ORIGIN = STATE + ".origin";
    private static final String METHODS = STATE + ".methods";
    private static final String HEADERS = STATE + ".headers";

    private final List<String> allowOrigins;

    /**
     * @param allowOrigins the allowed origins; empty allows all
     */
    public CorsFilter(List<String> allowOrigins) {
        this.allowOrigins = new ArrayList<>(allowOrigins);
    }

    @Override
    public ResponseEntity<?> filter(HttpServletRequest request, RequestContext context, ResourceSelector selector) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if ("OPTIONS".equals(request.getMethod())) {
            return preflight(request, context, selector, accept);
        }
        selector.findMethod(request.getMethod(), context.getRawPath(), accept);
        List<String> origins = values(context, HttpHeaders.ORIGIN, " ");
        if (!origins.isEmpty() && allowed(origins)) {
            request.setAttribute(ORIGIN, allowOrigins.isEmpty() ? "*" : String.join(" ", origins));
            request.setAttribute(STATE, SIMPLE);
        }
        return null;
    }

    private ResponseEntity<?> preflight(HttpServletRequest request, RequestContext context, ResourceSelector selector, String accept) {
        List<String> origins = values(context, HttpHeaders.ORIGIN, " ");
        if (origins.size() != 1) {
            return null;
        }
        List<String> methods = values(context, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, ",");
        if (methods.size() != 1) {
            return ResponseEntity.ok().build();
        }
        ResourceMethod method = selector.findMethod(methods.get(0), context.getRawPath(), accept);
        if (method == null) {
            return null;
        }
        selector.findMethod("OPTIONS", context.getRawPath(), accept);
        if (!allowed(origins)) {
            return ResponseEntity.ok().build();
        }
        request.setAttribute(METHODS, methods.get(0));
        request.setAttribute(HEADERS, String.join(", ", values(context, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, ",")));
        request.setAttribute(ORIGIN, allowOrigins.isEmpty() ? "*" : origins.get(0));
        request.setAttribute(STATE, PREFLIGHT);
        return ResponseEntity.ok().build();
    }

    @Override
    public void filter(HttpServletRequest request, RequestContext context, int status, HttpHeaders headers) {
        Object state = request.getAttribute(STATE);
        if (state == null) {
            return;
        }
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, (String) request.getAttribute(ORIGIN));
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "false");
        if (PREFLIGHT.equals(state)) {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, (String) request.getAttribute(METHODS));
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, (String) request.getAttribute(HEADERS));
        }
    }

    private boolean allowed(List<String> origins) {
        return allowOrigins.isEmpty() || allowOrigins.containsAll(origins);
    }

    private static List<String> values(RequestContext context, String name, String separator) {
        List<String> values = context.getHeaders().get(name);
        if (values == null) {
            return Collections.emptyList();
        }
        List<String> results = new ArrayList<>();
        for (String value : values) {
            for (String item : value.split(separator)) {
                results.add(item.trim());
            }
        }
        return results;
    }
}
