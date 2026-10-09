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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.context.WebApplicationContext;

/**
 * The Spring Boot application of tika-server: an embedded Tomcat whose only servlet is the
 * {@link TikaDispatcherServlet}, routing with a {@link ResourceHandlerMapping}, invoking with a
 * {@link ResourceHandlerAdapter} and answering failures with a {@link ResourceExceptionResolver}.
 * No other Spring MVC defaults (default handler mappings, error pages, multipart or charset
 * handling) take part, so the responses are the ones tika-server defines.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration(ServletWebServerFactoryAutoConfiguration.class)
public class TikaWebApplication {

    private static final String SETTINGS_BEAN = "tikaWebSettings";
    private static final String HEADLESS = "java.awt.headless";

    /**
     * Starts a server.
     *
     * @param settings   what it serves
     * @param properties Spring Boot properties, such as <code>server.port</code> and <code>server.ssl.*</code>
     * @return the running application
     */
    public static ConfigurableApplicationContext start(TikaWebSettings settings, Map<String, Object> properties) {
        // tika-server configures log4j2 itself
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none");
        SpringApplication app = new SpringApplication(TikaWebApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setBannerMode(Banner.Mode.OFF);
        app.setRegisterShutdownHook(false);
        app.setLogStartupInfo(false);
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("spring.main.lazy-initialization", "false");
        defaults.put("server.shutdown", "immediate");
        defaults.putAll(properties);
        app.setDefaultProperties(defaults);
        app.addInitializers(ctx -> ctx.getBeanFactory().registerSingleton(SETTINGS_BEAN, settings));
        // Spring Boot turns on AWT's headless mode; the parsers that use AWT and ImageIO report
        // what they report with the JVM's own setting, so it is restored when there was none
        String headless = System.getProperty(HEADLESS);
        ConfigurableApplicationContext context = app.run();
        if (headless == null) {
            System.clearProperty(HEADLESS);
        }
        return context;
    }

    /**
     * @param context a running application
     * @return the port it listens on
     */
    public static int getPort(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    @Bean
    ResponseWriter tikaResponseWriter(TikaWebSettings settings) {
        return new ResponseWriter(settings.getWriters(), settings.getResponseFilters());
    }

    @Bean
    ResourceHandlerMapping tikaHandlerMapping(TikaWebSettings settings) {
        return new ResourceHandlerMapping(new ResourceSelector(ResourceModel.of(settings.getResources()), settings.getComparator()), settings.getPreMatchFilters());
    }

    @Bean
    ResourceHandlerAdapter tikaHandlerAdapter(ResponseWriter writer) {
        return new ResourceHandlerAdapter(writer);
    }

    @Bean
    ResourceExceptionResolver tikaExceptionResolver(TikaWebSettings settings, ResponseWriter writer) {
        return new ResourceExceptionResolver(settings.getExceptionMappers(), writer);
    }

    @Bean
    ServletRegistrationBean<TikaDispatcherServlet> tikaDispatcherServlet(WebApplicationContext context) {
        TikaDispatcherServlet servlet = new TikaDispatcherServlet(context);
        servlet.setDetectAllHandlerMappings(true);
        ServletRegistrationBean<TikaDispatcherServlet> registration = new ServletRegistrationBean<>(servlet, "/");
        registration.setName("tika");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    FilterRegistrationBean<CompositeRequestFilter> tikaRequestFilters(TikaWebSettings settings) {
        FilterRegistrationBean<CompositeRequestFilter> registration = new FilterRegistrationBean<>(new CompositeRequestFilter(settings.getRequestFilters()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Runs the configured request filters, in order.
     */
    static final class CompositeRequestFilter implements Filter {
        private final List<Filter> filters;

        CompositeRequestFilter(List<Filter> filters) {
            this.filters = List.copyOf(filters);
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            new Chain(filters, chain).doFilter(request, response);
        }

        private static final class Chain implements FilterChain {
            private final List<Filter> filters;
            private final FilterChain last;
            private int next;

            Chain(List<Filter> filters, FilterChain last) {
                this.filters = filters;
                this.last = last;
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response)
                    throws IOException, ServletException {
                if (next < filters.size()) {
                    filters.get(next++).doFilter(request, response, this);
                } else {
                    last.doFilter(request, response);
                }
            }
        }
    }
}
