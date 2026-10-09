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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import org.apache.tika.server.core.web.ResourceModel.ResourceClass;
import org.apache.tika.server.core.web.ResourceModel.ResourceMethod;

/**
 * Chooses the endpoint for a request, with tika-server's long-standing rules (those of the
 * JAX-RS stack it was first built on, ported from Apache CXF).
 * <ol>
 *     <li>Of the resources whose path template matches the path, only those with the best
 *     template (most literal characters, then most variables) are considered.</li>
 *     <li>Of their endpoints, those whose template matches the rest of the path completely,
 *     whose HTTP method matches (GET also serves HEAD), that consume the request's content type
 *     (not checked for GET) and produce a type the request accepts are candidates.</li>
 *     <li>Candidates are ordered by the {@link ResourceComparator} first, then by template, by
 *     how closely they consume the content type and produce an accepted type.</li>
 * </ol>
 * When nothing is a candidate the request fails with 404, 405, 415 or 406: whichever of path,
 * method, consumed type and produced type matched no endpoint first. 404 and 405 carry an
 * <code>Allow</code> header listing every HTTP method of every resource. OPTIONS without an
 * endpoint of its own is answered 200 with that header.
 */
public final class ResourceSelector {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceSelector.class);

    private final List<ResourceClass> resources;
    private final ResourceComparator comparator;

    public ResourceSelector(List<ResourceClass> resources, ResourceComparator comparator) {
        this.resources = resources;
        this.comparator = comparator;
    }

    public List<ResourceClass> getResources() {
        return resources;
    }

    /**
     * The selected endpoint of a request.
     *
     * @param method        the endpoint
     * @param pathValues    the raw (still percent-encoded) values of the path variables
     * @param responseType  the media type of the response
     * @param acceptTypes   the request's acceptable types, best first
     */
    public record Selection(ResourceMethod method, Map<String, List<String>> pathValues, WebMediaType responseType, List<WebMediaType> acceptTypes) {
    }

    /**
     * @param httpMethod  the request method
     * @param rawPath     the request path, percent-encoded, starting with "/"
     * @param contentType the request's content type header, or null
     * @param accept      the request's accept header, or null
     * @return the selected endpoint, or null for an OPTIONS request without one (see {@link #allowResponseHeaders()})
     * @throws HttpStatusException when no endpoint serves the request
     */
    public Selection select(String httpMethod, String rawPath, String contentType, String accept) {
        String requestContentType = contentType == null ? "*/*" : contentType;
        String acceptTypes = accept == null ? "*/*" : accept;
        List<WebMediaType> acceptList;
        try {
            acceptList = MediaTypes.sortMediaTypes(MediaTypes.parseMediaTypes(acceptTypes), MediaTypes.Q_PARAM);
        } catch (IllegalArgumentException e) {
            throw new HttpStatusException(406, new HttpHeaders());
        }
        Map<ResourceClass, Map<String, List<String>>> matched = selectResourceClass(rawPath);
        if (matched == null) {
            throw new HttpStatusException(404, new HttpHeaders());
        }
        try {
            return findTargetMethod(matched, httpMethod, requestContentType, acceptList, acceptList, true);
        } catch (HttpStatusException e) {
            if (e.getResponse().getStatusCode().value() == 405 && "OPTIONS".equalsIgnoreCase(httpMethod)) {
                return null;
            }
            throw e;
        }
    }

    private Map<ResourceClass, Map<String, List<String>>> selectResourceClass(String path) {
        if (resources.size() == 1) {
            Map<String, List<String>> values = new HashMap<>();
            return resources.get(0).getTemplate().match(path, values) ? Map.of(resources.get(0), values) : null;
        }
        SortedMap<ResourceClass, Map<String, List<String>>> candidates = new TreeMap<>((c1, c2) -> {
            int result = UriTemplate.compareTemplates(c1.getTemplate(), c2.getTemplate());
            return result == 0 ? -1 : result;
        });
        for (ResourceClass rc : resources) {
            Map<String, List<String>> values = new HashMap<>();
            if (rc.getTemplate().match(path, values)) {
                candidates.put(rc, values);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Map<ResourceClass, Map<String, List<String>>> best = new LinkedHashMap<>();
        ResourceClass first = null;
        for (Map.Entry<ResourceClass, Map<String, List<String>>> e : candidates.entrySet()) {
            if (first == null) {
                first = e.getKey();
                best.put(e.getKey(), e.getValue());
            } else if (UriTemplate.compareTemplates(first.getTemplate(), e.getKey().getTemplate()) == 0) {
                best.put(e.getKey(), e.getValue());
            } else {
                break;
            }
        }
        return best;
    }

    /**
     * Looks up the endpoint a request with the given method would reach regardless of its
     * content type and accepted types (as the cross-origin checks do). The comparator still
     * sees the request's own accepted types.
     *
     * @param httpMethod the HTTP method
     * @param rawPath    the request path, percent-encoded
     * @param accept     the request's accept header, or null
     * @return the endpoint, or null when there is none
     */
    public ResourceMethod findMethod(String httpMethod, String rawPath, String accept) {
        List<WebMediaType> requestAccept = MediaTypes.sortMediaTypes(MediaTypes.parseMediaTypes(accept == null ? "*/*" : accept), MediaTypes.Q_PARAM);
        Map<ResourceClass, Map<String, List<String>>> matched = selectResourceClass(rawPath);
        if (matched == null) {
            return null;
        }
        Selection selection = findTargetMethod(matched, httpMethod, "*/*", List.of(WebMediaType.WILDCARD_TYPE), requestAccept, false);
        return selection == null ? null : selection.method();
    }

    private Selection findTargetMethod(Map<ResourceClass, Map<String, List<String>>> matched, String httpMethod, String requestContentType,
                                       List<WebMediaType> acceptTypes, List<WebMediaType> comparatorAcceptTypes, boolean throwException) {
        boolean getMethod = "GET".equals(httpMethod);
        WebMediaType requestType;
        try {
            requestType = MediaTypes.toMediaType(requestContentType);
        } catch (IllegalArgumentException e) {
            throw new HttpStatusException(415, new HttpHeaders());
        }
        WebMediaType contentType = requestType;
        SortedMap<ResourceMethod, Map<String, List<String>>> candidates =
                new TreeMap<>((m1, m2) -> compare(m1, m2, getMethod, httpMethod, contentType, acceptTypes, comparatorAcceptTypes));
        int pathMatched = 0;
        int methodMatched = 0;
        int consumeMatched = 0;
        for (Map.Entry<ResourceClass, Map<String, List<String>>> entry : matched.entrySet()) {
            String path = currentPath(entry.getValue());
            for (ResourceMethod rm : entry.getKey().getMethods()) {
                Map<String, List<String>> values = copy(entry.getValue());
                if (!rm.getTemplate().match(path, values)) {
                    continue;
                }
                String finalGroup = first(values.get(UriTemplate.FINAL_MATCH_GROUP));
                boolean finalPath = finalGroup == null || finalGroup.isEmpty() || "/".equals(finalGroup);
                if (!finalPath) {
                    continue;
                }
                pathMatched++;
                if (!matchHttpMethod(rm.getHttpMethod(), httpMethod)) {
                    continue;
                }
                methodMatched++;
                if (!getMethod && !MediaTypes.intersect(rm.getConsumeTypes(), requestType)) {
                    continue;
                }
                consumeMatched++;
                for (WebMediaType acceptType : acceptTypes) {
                    if (MediaTypes.intersect(rm.getProduceTypes(), acceptType)) {
                        candidates.put(rm, values);
                        break;
                    }
                }
            }
        }
        if (!candidates.isEmpty()) {
            Map.Entry<ResourceMethod, Map<String, List<String>>> firstEntry = candidates.entrySet().iterator().next();
            ResourceMethod rm = firstEntry.getKey();
            LOG.debug("{} selected for {} {}", rm.getName(), httpMethod, currentPath(firstEntry.getValue()));
            WebMediaType responseType = MediaTypes.intersectSortMediaTypes(acceptTypes, rm.getProduceTypes(), false).get(0);
            return new Selection(rm, firstEntry.getValue(), responseType, acceptTypes);
        }
        if (!throwException) {
            return null;
        }
        int status;
        if (pathMatched == 0) {
            status = 404;
        } else if (methodMatched == 0) {
            status = 405;
        } else if (consumeMatched == 0) {
            status = 415;
        } else {
            status = 406;
        }
        HttpHeaders headers = methodMatched == 0 ? allowResponseHeaders() : new HttpHeaders();
        throw new HttpStatusException(status, headers);
    }

    /**
     * @return an <code>Allow</code> header with the HTTP methods of all resources, OPTIONS, and HEAD where GET is allowed
     */
    public HttpHeaders allowResponseHeaders() {
        Set<String> allowed = new HashSet<>();
        for (ResourceClass rc : resources) {
            allowed.addAll(rc.getAllowedMethods());
        }
        List<String> values = new ArrayList<>(allowed);
        if (!allowed.contains("OPTIONS")) {
            values.add("OPTIONS");
        }
        if (!allowed.contains("HEAD") && allowed.contains("GET")) {
            values.add("HEAD");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ALLOW, String.join(",", values));
        return headers;
    }

    private int compare(ResourceMethod e1, ResourceMethod e2, boolean getMethod, String httpMethod, WebMediaType contentType, List<WebMediaType> acceptTypes,
                        List<WebMediaType> comparatorAcceptTypes) {
        if (e1 == e2) {
            return 0;
        }
        if (comparator != null) {
            int result = comparator.compare(e1, e2, comparatorAcceptTypes);
            if (result != 0) {
                return result;
            }
        }
        int result;
        if (!getMethod && "HEAD".equals(httpMethod)) {
            if ("HEAD".equals(e1.getHttpMethod())) {
                return -1;
            } else if ("HEAD".equals(e2.getHttpMethod())) {
                return 1;
            }
        }
        result = UriTemplate.compareTemplates(e1.getTemplate(), e2.getTemplate());
        if (result == 0 && !getMethod) {
            result = MediaTypes.compareSortedConsumesMediaTypes(e1.getConsumeTypes(), e2.getConsumeTypes(), contentType);
        }
        if (result == 0) {
            result = MediaTypes.compareSortedAcceptMediaTypes(e1.getProduceTypes(), e2.getProduceTypes(), acceptTypes);
        }
        if (result == 0) {
            result = compareParameters(e1.getMethod().getParameterTypes(), e2.getMethod().getParameterTypes());
        }
        return result;
    }

    private static int compareParameters(Class<?>[] paraList1, Class<?>[] paraList2) {
        int size1 = paraList1.length;
        int size2 = paraList2.length;
        for (int i = 0; i < size1 && i < size2; i++) {
            if (!paraList1[i].equals(paraList2[i])) {
                if (paraList1[i].isAssignableFrom(paraList2[i])) {
                    return 1;
                } else if (paraList2[i].isAssignableFrom(paraList1[i])) {
                    return -1;
                } else {
                    int result = paraList1[i].getName().compareTo(paraList2[i].getName());
                    if (result != 0) {
                        return result;
                    }
                }
            }
        }
        return Integer.compare(size1, size2);
    }

    private static boolean matchHttpMethod(String expected, String actual) {
        return expected.equalsIgnoreCase(actual) || "HEAD".equalsIgnoreCase(actual) && "GET".equals(expected);
    }

    private static String currentPath(Map<String, List<String>> values) {
        String path = first(values.get(UriTemplate.FINAL_MATCH_GROUP));
        return path == null ? "/" : path;
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static Map<String, List<String>> copy(Map<String, List<String>> values) {
        Map<String, List<String>> copy = new HashMap<>();
        values.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        return copy;
    }
}
