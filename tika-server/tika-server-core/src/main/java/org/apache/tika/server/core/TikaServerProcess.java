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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.context.ConfigurableApplicationContext;
import org.xml.sax.SAXException;

import org.apache.tika.Tika;
import org.apache.tika.config.ServiceLoader;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.parser.DigestingParser;
import org.apache.tika.parser.digestutils.BouncyCastleDigester;
import org.apache.tika.parser.digestutils.CommonsDigester;
import org.apache.tika.pipes.emitter.EmitterManager;
import org.apache.tika.pipes.fetcher.FetcherManager;
import org.apache.tika.server.core.resource.AsyncResource;
import org.apache.tika.server.core.resource.DetectorResource;
import org.apache.tika.server.core.resource.LanguageResource;
import org.apache.tika.server.core.resource.MetadataResource;
import org.apache.tika.server.core.resource.PipesResource;
import org.apache.tika.server.core.resource.RecursiveMetadataResource;
import org.apache.tika.server.core.resource.TikaDetectors;
import org.apache.tika.server.core.resource.TikaMimeTypes;
import org.apache.tika.server.core.resource.TikaParsers;
import org.apache.tika.server.core.resource.TikaResource;
import org.apache.tika.server.core.resource.TikaServerResource;
import org.apache.tika.server.core.resource.TikaServerStatus;
import org.apache.tika.server.core.resource.TikaVersion;
import org.apache.tika.server.core.resource.TikaWelcome;
import org.apache.tika.server.core.resource.TranslateResource;
import org.apache.tika.server.core.resource.UnpackerResource;
import org.apache.tika.server.core.web.CorsFilter;
import org.apache.tika.server.core.web.TikaWebApplication;
import org.apache.tika.server.core.web.TikaWebSettings;
import org.apache.tika.server.core.writer.CSVMessageBodyWriter;
import org.apache.tika.server.core.writer.JSONMessageBodyWriter;
import org.apache.tika.server.core.writer.JSONObjWriter;
import org.apache.tika.server.core.writer.MetadataListMessageBodyWriter;
import org.apache.tika.server.core.writer.TarWriter;
import org.apache.tika.server.core.writer.TextMessageBodyWriter;
import org.apache.tika.server.core.writer.TikaServerWriter;
import org.apache.tika.server.core.writer.ZipWriter;

public class TikaServerProcess {


    public static final Set<String> LOG_LEVELS = new HashSet<>(Arrays.asList("debug", "info"));
    public static final int BIND_EXCEPTION = 42;
    private static final Logger LOG = LoggerFactory.getLogger(TikaServerProcess.class);
    public static int DO_NOT_RESTART_EXIT_VALUE = -100;

    private static Options getOptions() {
        Options options = new Options();
        options.addOption("h", "host", true, "host name, use * for all)");
        options.addOption("p", "port", true, "listen port");
        options.addOption("c", "config", true, "Tika Configuration file to override default config with.");
        options.addOption("i", "id", true, "id to use for server in server status endpoint");
        options.addOption("?", "help", false, "this help message");
        options.addOption("noFork", "noFork", false, "if launched in no fork mode");
        options.addOption("forkedStatusFile", true,
                "Not allowed in -noFork: temporary file used to communicate " + "with forking process -- do not use this! " + "Should only be invoked by forking process.");
        options.addOption("tmpFilePrefix", true, "Not allowed in -noFork: prefix for temp file - for debugging only");
        options.addOption("numRestarts", true, "Not allowed in -noFork: number of times that " + "the forked server has had to be restarted.");
        return options;
    }

    public static void main(String[] args) throws Exception {
        LOG.info("Starting {} server", Tika.getString());
        try {
            Options options = getOptions();
            CommandLineParser cliParser = new DefaultParser();
            CommandLine line = cliParser.parse(options, args);
            TikaServerConfig tikaServerConfig = TikaServerConfig.load(line);
            LOG.debug("forked config: {}", tikaServerConfig);

            ServerDetails serverDetails = initServer(tikaServerConfig);
            startServer(serverDetails, tikaServerConfig);

        } catch (Exception e) {
            LOG.error("Can't start: ", e);
            System.exit(-1);
        }
    }

    private static boolean isBindException(Throwable e) {
        if (e == null) {
            return false;
        }
        if (e instanceof BindException || e instanceof PortInUseException) {
            return true;
        }
        return isBindException(e.getCause());
    }

    private static void startServer(ServerDetails serverDetails, TikaServerConfig tikaServerConfig) throws Exception {

        try {
            //start the server
            serverDetails.context = TikaWebApplication.start(serverDetails.settings, serverDetails.properties);
        } catch (RuntimeException e) {
            LOG.warn("exception starting server", e);
            if (isBindException(e)) {
                System.exit(BIND_EXCEPTION);
            }
            System.exit(DO_NOT_RESTART_EXIT_VALUE);
        }

        if (!tikaServerConfig.isNoFork()) {
            //redirect
            InputStream in = System.in;
            System.setIn(new ByteArrayInputStream(new byte[0]));

            String forkedStatusFile = tikaServerConfig.getForkedStatusFile();
            Thread serverThread = new Thread(new ServerStatusWatcher(serverDetails.serverStatus, in, Paths.get(forkedStatusFile), tikaServerConfig));

            serverThread.start();
        }

        LOG.info("Started Apache Tika server {} at {}", serverDetails.serverId, serverDetails.url);
    }

    //This returns the server, configured and ready to be started.
    private static ServerDetails initServer(TikaServerConfig tikaServerConfig) throws Exception {
        String host = tikaServerConfig.getHost();
        int[] ports = tikaServerConfig.getPorts();
        if (ports.length > 1) {
            throw new IllegalArgumentException("there must be only one port here! " + "I see: " + tikaServerConfig.getPort());
        }

        int port = ports[0];
        // The Tika Configuration to use throughout
        TikaConfig tika;

        if (tikaServerConfig.hasConfigFile()) {
            LOG.info("Using custom config: {}", tikaServerConfig.getConfigPath());
            tika = new TikaConfig(tikaServerConfig.getConfigPath());
        } else {
            tika = TikaConfig.getDefaultConfig();
        }

        DigestingParser.Digester digester = null;
        if (!StringUtils.isBlank(tikaServerConfig.getDigest())) {
            try {
                digester = new CommonsDigester(tikaServerConfig.getDigestMarkLimit(), tikaServerConfig.getDigest());
            } catch (IllegalArgumentException commonsException) {
                try {
                    digester = new BouncyCastleDigester(tikaServerConfig.getDigestMarkLimit(), tikaServerConfig.getDigest());
                } catch (IllegalArgumentException bcException) {
                    throw new IllegalArgumentException(
                            "Tried both CommonsDigester (" + commonsException.getMessage() + ") and BouncyCastleDigester (" + bcException.getMessage() + ")", bcException);
                }
            }
        }

        //TODO -- clean this up -- only load as necessary
        FetcherManager fetcherManager = null;
        InputStreamFactory inputStreamFactory = null;
        if (tikaServerConfig.isEnableUnsecureFeatures()) {
            //tolerate configs that enable unsecure features (e.g. for the status endpoint)
            //without declaring any fetchers
            fetcherManager = FetcherManager.loadOrEmpty(tikaServerConfig.getConfigPath());
            inputStreamFactory = new FetcherStreamFactory(fetcherManager);
        } else {
            inputStreamFactory = new DefaultInputStreamFactory();
        }
        //TODO -- figure out how to turn this back on
        //logFetchersAndEmitters(tikaServerConfig.isEnableUnsecureFeatures(), fetcherManager,
        //      emitterManager);

        String serverId = tikaServerConfig.getId();
        LOG.debug("SERVER ID:" + serverId);
        ServerStatus serverStatus;

        if (tikaServerConfig.isNoFork()) {
            serverStatus = new ServerStatus(serverId, 0, true);
        } else {
            serverStatus = new ServerStatus(serverId, tikaServerConfig.getNumRestarts(), false);
            System.setOut(System.err);
        }
        TikaResource.init(tika, tikaServerConfig, digester, inputStreamFactory, serverStatus);

        TikaWebSettings settings = new TikaWebSettings().setComparator(new ProduceTypeResourceComparator());
        loadAllProviders(tikaServerConfig, serverStatus, settings);

        String protocol = tikaServerConfig
                .getTlsConfig()
                .isActive() ? "https" : "http";
        String url = protocol + "://" + host + ":" + port + "/";

        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", Integer.toString(port));
        if (!"*".equals(host)) {
            properties.put("server.address", host);
        }
        if (tikaServerConfig
                .getTlsConfig()
                .isActive()) {
            LOG.warn("The TLS configuration is in BETA and might change " + "dramatically in future releases.");
            properties.putAll(getTlsProperties(tikaServerConfig.getTlsConfig()));
        }
        ServerDetails details = new ServerDetails();
        details.settings = settings;
        details.properties = properties;
        details.url = url;
        details.serverId = serverId;
        details.serverStatus = serverStatus;
        return details;
    }

    /**
     * @return the Spring Boot <code>server.ssl.*</code> properties for a TLS configuration
     */
    private static Map<String, Object> getTlsProperties(TlsConfig tlsConfig) {
        Map<String, Object> ssl = new HashMap<>();
        ssl.put("server.ssl.enabled", "true");
        ssl.put("server.ssl.key-store", Paths.get(tlsConfig.getKeyStoreFile()).toUri().toString());
        ssl.put("server.ssl.key-store-type", tlsConfig.getKeyStoreType());
        ssl.put("server.ssl.key-store-password", tlsConfig.getKeyStorePassword());
        ssl.put("server.ssl.key-password", tlsConfig.getKeyStorePassword());
        if (tlsConfig.hasTrustStore()) {
            ssl.put("server.ssl.trust-store", Paths.get(tlsConfig.getTrustStoreFile()).toUri().toString());
            ssl.put("server.ssl.trust-store-type", tlsConfig.getTrustStoreType());
            ssl.put("server.ssl.trust-store-password", tlsConfig.getTrustStorePassword());
        }
        if (tlsConfig.isClientAuthenticationRequired()) {
            ssl.put("server.ssl.client-auth", "need");
        } else if (tlsConfig.isClientAuthenticationWanted()) {
            ssl.put("server.ssl.client-auth", "want");
        }
        return ssl;
    }

    private static void loadAllProviders(TikaServerConfig tikaServerConfig, ServerStatus serverStatus, TikaWebSettings settings)
            throws TikaException, SAXException, IOException {
        List<Object> tmpCoreProviders = loadCoreProviders(tikaServerConfig, serverStatus);

        settings.getResources().addAll(tmpCoreProviders);
        settings.getResources().add(new TikaWelcome(tmpCoreProviders));

        //for now, just load everything
        //TODO figure out which ones to turn off
        settings.getWriters().add(new TarWriter());
        settings.getWriters().add(new ZipWriter());
        settings.getWriters().add(new CSVMessageBodyWriter());
        settings.getWriters().add(new MetadataListMessageBodyWriter());
        settings.getWriters().add(new JSONMessageBodyWriter());
        settings.getWriters().add(new TextMessageBodyWriter());
        settings.getWriters().addAll(loadWriterServices());
        settings.getExceptionMappers().add(new TikaServerParseExceptionMapper(tikaServerConfig.isReturnStackTrace()));
        settings.getWriters().add(new JSONObjWriter());

        if (!StringUtils.isBlank(tikaServerConfig.getLogLevel())) {
            String logLevel = tikaServerConfig.getLogLevel();
            if (LOG_LEVELS.contains(logLevel)) {
                boolean isInfoLevel = "info".equals(logLevel);
                settings.getRequestFilters().add(new TikaLoggingFilter(isInfoLevel));
            } else {
                LOG.warn("Unsupported request URI log level: {}", logLevel);
            }
        }

        if (!StringUtils.isBlank(tikaServerConfig.getCors())) {
            String url = tikaServerConfig.getCors();
            List<String> origins = new ArrayList<>();
            if (!url.equals("*")) {
                origins.add(url);         // Empty list allows all origins.
            }
            CorsFilter cors = new CorsFilter(origins);
            settings.getPreMatchFilters().add(cors);
            settings.getResponseFilters().add(cors);
        }

    }

    private static List<Object> loadCoreProviders(TikaServerConfig tikaServerConfig, ServerStatus serverStatus) throws TikaException, IOException, SAXException {
        List<Object> resourceProviders = new ArrayList<>();
        boolean addAsyncResource = false;
        boolean addPipesResource = false;
        if (tikaServerConfig
                .getEndpoints()
                .size() == 0) {
            resourceProviders.add(new MetadataResource());
            resourceProviders.add(new RecursiveMetadataResource());
            resourceProviders.add(new DetectorResource(serverStatus));
            resourceProviders.add(new LanguageResource());
            resourceProviders.add(new TranslateResource(serverStatus, tikaServerConfig.getTaskTimeoutMillis()));
            resourceProviders.add(new TikaResource());
            resourceProviders.add(new UnpackerResource());
            resourceProviders.add(new TikaMimeTypes());
            resourceProviders.add(new TikaDetectors());
            resourceProviders.add(new TikaParsers());
            resourceProviders.add(new TikaVersion());
            if (tikaServerConfig.isEnableUnsecureFeatures()) {
                //check to make sure there are both fetchers and emitters
                //specified.  It is possible that users may only specify fetchers
                //for legacy endpoints.
                if (tikaServerConfig
                        .getSupportedFetchers()
                        .size() > 0 && tikaServerConfig
                        .getSupportedEmitters()
                        .size() > 0) {
                    addAsyncResource = true;
                    addPipesResource = true;
                }
                resourceProviders.add(new TikaServerStatus(serverStatus));
            }
        } else {
            for (String endPoint : tikaServerConfig.getEndpoints()) {
                if ("meta".equals(endPoint)) {
                    resourceProviders.add(new MetadataResource());
                } else if ("rmeta".equals(endPoint)) {
                    resourceProviders.add(new RecursiveMetadataResource());
                } else if ("detect".equals(endPoint)) {
                    resourceProviders.add(new DetectorResource(serverStatus));
                } else if ("language".equals(endPoint)) {
                    resourceProviders.add(new LanguageResource());
                } else if ("translate".equals(endPoint)) {
                    resourceProviders.add(new TranslateResource(serverStatus, tikaServerConfig.getTaskTimeoutMillis()));
                } else if ("tika".equals(endPoint)) {
                    resourceProviders.add(new TikaResource());
                } else if ("unpack".equals(endPoint)) {
                    resourceProviders.add(new UnpackerResource());
                } else if ("mime".equals(endPoint)) {
                    resourceProviders.add(new TikaMimeTypes());
                } else if ("detectors".equals(endPoint)) {
                    resourceProviders.add(new TikaDetectors());
                } else if ("parsers".equals(endPoint)) {
                    resourceProviders.add(new TikaParsers());
                } else if ("version".equals(endPoint)) {
                    resourceProviders.add(new TikaVersion());
                } else if ("pipes".equals(endPoint)) {
                    addPipesResource = true;
                } else if ("async".equals(endPoint)) {
                    addAsyncResource = true;
                } else if ("status".equals(endPoint)) {
                    resourceProviders.add(new TikaServerStatus(serverStatus));
                }
            }
        }

        if (addAsyncResource) {
            final AsyncResource localAsyncResource = new AsyncResource(tikaServerConfig.getConfigPath(), tikaServerConfig.getSupportedFetchers());
            Runtime
                    .getRuntime()
                    .addShutdownHook(new Thread(() -> {
                        try {
                            localAsyncResource.shutdownNow();
                        } catch (Exception e) {
                            LOG.warn("problem shutting down local async resource", e);
                        }
                    }));
            resourceProviders.add(localAsyncResource);
        }
        if (addPipesResource) {
            final PipesResource localPipesResource = new PipesResource(tikaServerConfig.getConfigPath());
            Runtime
                    .getRuntime()
                    .addShutdownHook(new Thread(() -> {
                        try {
                            localPipesResource.close();
                        } catch (Exception e) {
                            LOG.warn("exception closing local pipes resource", e);
                        }
                    }));
            resourceProviders.add(localPipesResource);
        }
        resourceProviders.addAll(loadResourceServices(serverStatus));
        return resourceProviders;
    }

    private static void logFetchersAndEmitters(boolean enableUnsecureFeatures, FetcherManager fetcherManager, EmitterManager emitterManager) {
        if (enableUnsecureFeatures) {
            StringBuilder sb = new StringBuilder();
            Set<String> supportedFetchers = fetcherManager.getSupported();
            sb.append("enableSecureFeatures has been selected.\n");
            if (supportedFetchers.size() == 0) {
                sb.append("There are no fetchers specified in the TikaConfig");
            } else {
                sb.append("The following fetchers are available to whomever has " + "access to this server:\n");
                for (String p : supportedFetchers) {
                    sb
                            .append(p)
                            .append("\n");
                }
            }
            Set<String> emitters = emitterManager.getSupported();
            if (supportedFetchers.size() == 0) {
                sb.append("There are no emitters specified in the TikaConfig");
            } else {
                sb.append("The following emitters are available to whomever has " + "access to this server:\n");
                for (String e : emitters) {
                    sb
                            .append(e)
                            .append("\n");
                }
            }
            LOG.info(sb.toString());
        } else {
            if (emitterManager
                    .getSupported()
                    .size() > 0) {
                String warn = "enableUnsecureFeatures has not been set to 'true' in the server " + "config file.\n" + "The " + emitterManager
                        .getSupported()
                        .size() + " emitter(s) that you've\n" + "specified in TikaConfig will not be available on the /emit " + "or /async endpoints.\n" +
                        "To enable your emitters, start tika-server with " + "<enableUnsecureFeatures>true</enableUnsecureFeatures> " + "parameter in " + "the TikaConfig\n\n";
                LOG.warn(warn);
            }
            if (emitterManager
                    .getSupported()
                    .size() > 0) {
                String warn = "enableUnsecureFeatures has not been set to 'true' in the server " + "config file.\n" + "The " + emitterManager
                        .getSupported()
                        .size() + " fetcher(s) that you've\n" + "specified in TikaConfig will not be available on the /emit " + "or /async endpoints.\n" +
                        "To enable your emitters, start tika-server with " + "<enableUnsecureFeatures>true</enableUnsecureFeatures> " + "parameter in " + "the TikaConfig\n\n";
                LOG.warn(warn);
            }
        }
    }

    private static List<Object> loadResourceServices(ServerStatus serverStatus) {
        List<TikaServerResource> resources = new ServiceLoader(TikaServerProcess.class.getClassLoader()).loadServiceProviders(TikaServerResource.class);
        List<Object> providers = new ArrayList<>();

        for (TikaServerResource r : resources) {
            LOG.info("loading resource from SPI: " + r.getClass());
            if (r instanceof ServerStatusResource) {
                ((ServerStatusResource) r).setServerStatus(serverStatus);
            }
            providers.add(r);
        }
        return providers;
    }

    private static List<TikaServerWriter<?>> loadWriterServices() {
        List<TikaServerWriter<?>> writers = new ArrayList<>();
        for (TikaServerWriter<?> w : new ServiceLoader(TikaServerProcess.class.getClassLoader()).loadServiceProviders(TikaServerWriter.class)) {
            writers.add(w);
        }
        return writers;
    }

    private static class ServerDetails {
        TikaWebSettings settings;
        Map<String, Object> properties;
        ConfigurableApplicationContext context;
        String serverId;
        String url;
        ServerStatus serverStatus;
    }
}
