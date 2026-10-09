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

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs the URI of every request, before it is matched to an endpoint.
 */
public class TikaLoggingFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(TikaLoggingFilter.class);
    private final boolean infoLevel;

    public TikaLoggingFilter(boolean infoLevel) {
        this.infoLevel = infoLevel;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        StringBuffer url = request.getRequestURL();
        if (request.getQueryString() != null) {
            url.append('?').append(request.getQueryString());
        }
        String requestUri = url.toString();
        if (infoLevel) {
            LOG.info("Request URI: {}", requestUri);
        } else {
            LOG.debug("Request URI: {}", requestUri);
        }
        chain.doFilter(request, response);
    }
}
