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
package org.apache.tika.server.core.resource;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.apache.tika.Tika;
import org.apache.tika.server.core.HTMLHelper;

/**
 * <p>Provides a basic welcome to the Apache Tika Server.</p>
 */
@RequestMapping("/")
public class TikaWelcome {
    private static final String DOCS_URL = "https://wiki.apache.org/tika/TikaJAXRS";

    private Tika tika;
    private HTMLHelper html;
    private List<Class<?>> endpoints = new LinkedList<>();

    /**
     * @param coreResources the resources to list
     */
    public TikaWelcome(List<?> coreResources) {
        this.tika = new Tika(TikaResource.getConfig());
        this.html = new HTMLHelper();
        for (Object resource : coreResources) {
            this.endpoints.add(resource.getClass());
        }
    }

    /**
     * Lists the endpoints a resource class itself declares: its own class-level path (not an
     * inherited one) joined with each method's path, the HTTP method and the produced types,
     * sorted by path and then method name.
     */
    protected List<Endpoint> identifyEndpoints() {
        List<Endpoint> found = new ArrayList<>();
        for (Class<?> endpoint : endpoints) {
            RequestMapping p = AnnotatedElementUtils.getMergedAnnotation(endpoint, RequestMapping.class);
            String basePath = null;
            if (p != null && p.path().length > 0) {
                String value = p.path()[0];
                basePath = value.endsWith("/") ? value.substring(0, value.length() - 2) : value;
            }

            for (Method m : endpoint.getMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.getMergedAnnotation(m, RequestMapping.class);
                if (mapping == null || mapping.method().length == 0) {
                    continue;
                }
                String httpMethod = mapping.method()[mapping.method().length - 1].name();
                String methodPath = mapping.path().length == 0 ? null : mapping.path()[0];
                String[] produces = mapping.produces();
                String mPath = basePath;
                if (mPath == null) {
                    mPath = "";
                }
                if (methodPath != null) {
                    if (methodPath.startsWith("/")) {
                        mPath += methodPath;
                    } else {
                        mPath += "/" + methodPath;
                    }
                }
                found.add(new Endpoint(endpoint, m, mPath, httpMethod, produces));
            }
        }
        found.sort(Comparator
                .comparing((Endpoint e) -> e.path)
                .thenComparing(e -> e.methodName));
        return found;
    }

    @GetMapping(produces = "text/html")
    public String getWelcomeHTML() {
        TikaResource.checkIsOperating();

        StringBuffer h = new StringBuffer();
        String tikaVersion = tika.toString();

        html.generateHeader(h, "Welcome to the " + tikaVersion + " Server");

        h.append("<p>For endpoints, please see <a href=\"");
        h.append(DOCS_URL);
        h.append("\">");
        h.append(DOCS_URL);
        h.append("</a>");

        // TIKA-1269 -- Miredot documentation
        // As the SNAPSHOT endpoints are updated, please update the website by running
        // the server tests and doing step 12.6 of https://wiki.apache.org/tika/ReleaseProcess.
        Pattern p = Pattern.compile("\\d+\\.\\d+");
        Matcher m = p.matcher(tikaVersion);
        if (m.find()) {
            String versionNumber = m.group();
            String miredot = "https://tika.apache.org/" + versionNumber + "/miredot/index.html";
            h
                    .append(" and <a href=\"")
                    .append(miredot)
                    .append("\">")
                    .append(miredot)
                    .append("</a>");
        }
        h.append("</p>\n");

        h.append("<ul>\n");
        for (Endpoint e : identifyEndpoints()) {
            h.append("<li><b>");
            h.append(e.httpMethod);
            h.append("</b> <i><a href=\"");
            h.append(e.path);
            h.append("\">");
            h.append(e.path);
            h.append("</a></i><br />");
            h.append("Class: ");
            h.append(e.className);
            h.append("<br />Method: ");
            h.append(e.methodName);
            for (String produces : e.produces) {
                h.append("<br />Produces: ");
                h.append(produces);
            }
            h.append("</li>\n");
        }
        h.append("</ul>\n");

        html.generateFooter(h);
        return h.toString();
    }

    @GetMapping(produces = "text/plain")
    public String getWelcomePlain() {
        TikaResource.checkIsOperating();
        StringBuilder text = new StringBuilder();

        text.append(tika.toString());
        text.append("\n");
        text.append("For endpoints, please see ");
        text.append(DOCS_URL);
        text.append("\n\n");

        for (Endpoint e : identifyEndpoints()) {
            text.append(e.httpMethod);
            text.append(" ");
            text.append(e.path);
            text.append("\n");
            for (String produces : e.produces) {
                text.append(" => ");
                text.append(produces);
                text.append("\n");
            }
        }

        return text.toString();
    }

    protected static class Endpoint {
        public final String className;
        public final String methodName;
        public final String path;
        public final String httpMethod;
        public final List<String> produces;

        protected Endpoint(Class<?> endpoint, Method method, String path, String httpMethod, String[] produces) {
            this.className = endpoint.getCanonicalName();
            this.methodName = method.getName();
            this.path = path;
            this.httpMethod = httpMethod;
            this.produces = Collections.unmodifiableList(Arrays.asList(produces));
        }
    }
}
