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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Answers every exception of a request: with the response of the most specific
 * {@link ExceptionMapper}, else the response an {@link HttpStatusException} carries, else
 * 500 with no body.
 */
public class ResourceExceptionResolver implements HandlerExceptionResolver, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceExceptionResolver.class);

    private final List<ExceptionMapper<?>> mappers;
    private final ResponseWriter writer;

    public ResourceExceptionResolver(List<ExceptionMapper<?>> mappers, ResponseWriter writer) {
        this.mappers = new ArrayList<>(mappers);
        this.writer = writer;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        ResourceSelector.Selection selection = (ResourceSelector.Selection) request.getAttribute(ResourceHandlerMapping.SELECTION_ATTRIBUTE);
        Throwable failure = ex instanceof PartialOutputException ? ex.getCause() : ex;
        ResponseEntity<?> entity = toResponse(failure);
        if (entity == null) {
            LOG.warn("request {} {} failed", request.getMethod(), request.getRequestURI(), failure);
            entity = ResponseEntity.internalServerError().build();
        }
        if (ex instanceof PartialOutputException partial) {
            entity = new PartialResponse<>(entity, partial.getOutput());
        }
        try {
            if (response.isCommitted()) {
                LOG.warn("response already committed; cannot report", ex);
            } else {
                response.reset();
                writer.write(request, response, entity, selection);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not write the error response", e);
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(500);
            }
        }
        return new ModelAndView();
    }

    private ResponseEntity<?> toResponse(Throwable ex) {
        ExceptionMapper<?> best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ExceptionMapper<?> mapper : mappers) {
            int distance = distance(ex.getClass(), mapper.getExceptionType());
            if (distance >= 0 && distance < bestDistance) {
                best = mapper;
                bestDistance = distance;
            }
        }
        if (best != null) {
            return map(best, ex);
        }
        if (ex instanceof HttpStatusException) {
            return ((HttpStatusException) ex).getResponse();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> ResponseEntity<?> map(ExceptionMapper<E> mapper, Throwable ex) {
        return mapper.toResponse((E) ex);
    }

    private static int distance(Class<?> type, Class<?> target) {
        int d = 0;
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c == target) {
                return d;
            }
            d++;
        }
        return -1;
    }
}
