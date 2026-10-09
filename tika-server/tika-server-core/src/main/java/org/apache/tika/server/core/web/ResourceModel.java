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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * The endpoints of the tika-server resources, read from their Spring
 * {@link RequestMapping} annotations.
 * <p>
 * A resource is any object whose class (or a superclass) carries a class-level
 * {@link RequestMapping} path, or whose methods carry mappings. Paths are tika-server
 * path templates ({@link UriTemplate}), not Spring path patterns, and every mapped method
 * names exactly one HTTP method. A method without <code>consumes</code> or
 * <code>produces</code> takes them from the class, and from <code>*&#47;*</code> when the
 * class has none either.
 */
public final class ResourceModel {

    private ResourceModel() {
    }

    /**
     * @param resources the resource objects, in registration order
     * @return their models, in the same order
     */
    public static List<ResourceClass> of(List<?> resources) {
        List<ResourceClass> classes = new ArrayList<>(resources.size());
        for (Object resource : resources) {
            classes.add(new ResourceClass(resource));
        }
        return Collections.unmodifiableList(classes);
    }

    /**
     * One resource object and its endpoints.
     */
    public static final class ResourceClass {
        private final Object resource;
        private final UriTemplate template;
        private final List<ResourceMethod> methods = new ArrayList<>();
        private final Set<String> allowedMethods = new HashSet<>();

        ResourceClass(Object resource) {
            this.resource = resource;
            Class<?> type = resource.getClass();
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String path = classMapping == null || classMapping.path().length == 0 ? null : classMapping.path()[0];
            this.template = UriTemplate.create(path);
            String[] classConsumes = classMapping == null ? new String[0] : classMapping.consumes();
            String[] classProduces = classMapping == null ? new String[0] : classMapping.produces();
            for (Method m : type.getMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mapping == null || mapping.method().length == 0) {
                    continue;
                }
                RequestMethod httpMethod = mapping.method()[0];
                String methodPath = mapping.path().length == 0 ? null : mapping.path()[0];
                String[] consumes = mapping.consumes().length > 0 ? mapping.consumes() : classConsumes;
                String[] produces = mapping.produces().length > 0 ? mapping.produces() : classProduces;
                methods.add(new ResourceMethod(this, m, httpMethod.name(), methodPath, consumes, produces));
                allowedMethods.add(httpMethod.name());
            }
        }

        public Object getResource() {
            return resource;
        }

        public Class<?> getResourceType() {
            return resource.getClass();
        }

        public UriTemplate getTemplate() {
            return template;
        }

        public List<ResourceMethod> getMethods() {
            return methods;
        }

        /**
         * @return the HTTP methods of this resource's endpoints, in hash order
         */
        public Set<String> getAllowedMethods() {
            return allowedMethods;
        }
    }

    /**
     * One endpoint: a Java method serving one HTTP method on one path template.
     */
    public static final class ResourceMethod {
        private static final List<WebMediaType> ALL = Collections.singletonList(WebMediaType.WILDCARD_TYPE);

        private final ResourceClass resourceClass;
        private final Method method;
        private final String httpMethod;
        private final UriTemplate template;
        private final List<WebMediaType> consumeTypes;
        private final List<WebMediaType> produceTypes;

        ResourceMethod(ResourceClass resourceClass, Method method, String httpMethod, String path, String[] consumes, String[] produces) {
            this.resourceClass = resourceClass;
            this.method = method;
            this.httpMethod = httpMethod;
            this.template = UriTemplate.create(path);
            this.consumeTypes = consumes.length == 0 ? ALL : Collections.unmodifiableList(MediaTypes.getMediaTypes(consumes));
            this.produceTypes = produces.length == 0 ? ALL : Collections.unmodifiableList(MediaTypes.getMediaTypes(produces));
        }

        public ResourceClass getResourceClass() {
            return resourceClass;
        }

        public Method getMethod() {
            return method;
        }

        public String getHttpMethod() {
            return httpMethod;
        }

        public UriTemplate getTemplate() {
            return template;
        }

        public List<WebMediaType> getConsumeTypes() {
            return consumeTypes;
        }

        public List<WebMediaType> getProduceTypes() {
            return produceTypes;
        }

        /**
         * @return <code>ClassName#methodName</code>, for logging
         */
        public String getName() {
            return resourceClass.getResourceType().getName() + "#" + method.getName();
        }
    }
}
