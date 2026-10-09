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
import java.util.List;

import jakarta.servlet.Filter;
import org.springframework.http.converter.HttpMessageConverter;

/**
 * What a tika-server web application serves: its resources (in registration order), body
 * writers, exception mappers, filters before routing, response filters and servlet request
 * filters, and the endpoint comparator.
 */
public final class TikaWebSettings {

    private final List<Object> resources = new ArrayList<>();
    private final List<HttpMessageConverter<?>> writers = new ArrayList<>();
    private final List<ExceptionMapper<?>> exceptionMappers = new ArrayList<>();
    private final List<PreMatchFilter> preMatchFilters = new ArrayList<>();
    private final List<ResponseFilter> responseFilters = new ArrayList<>();
    private final List<Filter> requestFilters = new ArrayList<>();
    private ResourceComparator comparator;

    public List<Object> getResources() {
        return resources;
    }

    public List<HttpMessageConverter<?>> getWriters() {
        return writers;
    }

    public List<ExceptionMapper<?>> getExceptionMappers() {
        return exceptionMappers;
    }

    public List<PreMatchFilter> getPreMatchFilters() {
        return preMatchFilters;
    }

    public List<ResponseFilter> getResponseFilters() {
        return responseFilters;
    }

    public List<Filter> getRequestFilters() {
        return requestFilters;
    }

    public ResourceComparator getComparator() {
        return comparator;
    }

    public TikaWebSettings setComparator(ResourceComparator comparator) {
        this.comparator = comparator;
        return this;
    }
}
