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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.tika.server.core.resource.RecursiveMetadataResource.DEFAULT_HANDLER_TYPE;
import static org.apache.tika.server.core.resource.RecursiveMetadataResource.HANDLER_TYPE_PARAM;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.Tika;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.config.TikaTaskTimeout;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.DigestingParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.pipes.HandlerConfig;
import org.apache.tika.sax.BasicContentHandlerFactory;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.ExpandedTitleContentHandler;
import org.apache.tika.sax.RichTextContentHandler;
import org.apache.tika.sax.ToMarkdownContentHandler;
import org.apache.tika.sax.boilerpipe.BoilerpipeContentHandler;
import org.apache.tika.server.core.CompositeParseContextConfig;
import org.apache.tika.server.core.InputStreamFactory;
import org.apache.tika.server.core.ParseContextConfig;
import org.apache.tika.server.core.ServerStatus;
import org.apache.tika.server.core.TikaServerConfig;
import org.apache.tika.server.core.TikaServerParseException;
import org.apache.tika.server.core.web.Attachment;
import org.apache.tika.server.core.web.ContentDisposition;
import org.apache.tika.server.core.web.HttpStatusException;
import org.apache.tika.server.core.web.RequestContext;
import org.apache.tika.server.core.web.StreamingOutput;
import org.apache.tika.server.core.web.WebMediaType;
import org.apache.tika.utils.ExceptionUtils;
import org.apache.tika.utils.XMLReaderUtils;

@RequestMapping("/tika")
public class TikaResource {

    public static final String GREETING = "This is Tika Server (" + Tika.getString() + "). Please PUT\n";
    private static final String META_PREFIX = "meta_";
    private static final Logger LOG = LoggerFactory.getLogger(TikaResource.class);
    private static Pattern ALLOWABLE_HEADER_CHARS = Pattern.compile("(?i)^[-/_+\\.A-Z0-9 ]+$");
    private static TikaConfig TIKA_CONFIG;
    private static TikaServerConfig TIKA_SERVER_CONFIG;
    private static DigestingParser.Digester DIGESTER = null;
    private static InputStreamFactory INPUTSTREAM_FACTORY = null;
    private static ServerStatus SERVER_STATUS = null;

    private static ParseContextConfig PARSE_CONTEXT_CONFIG = new CompositeParseContextConfig();


    public static void init(TikaConfig config, TikaServerConfig tikaServerConfg, DigestingParser.Digester digester, InputStreamFactory inputStreamFactory,
                            ServerStatus serverStatus) {
        TIKA_CONFIG = config;
        TIKA_SERVER_CONFIG = tikaServerConfg;
        DIGESTER = digester;
        INPUTSTREAM_FACTORY = inputStreamFactory;
        SERVER_STATUS = serverStatus;
    }


    @SuppressWarnings("serial")
    public static Parser createParser() {
        final Parser parser = new AutoDetectParser(TIKA_CONFIG);

        if (DIGESTER != null) {
            boolean skipContainer = false;
            if (TIKA_CONFIG
                    .getAutoDetectParserConfig()
                    .getDigesterFactory() != null && TIKA_CONFIG
                    .getAutoDetectParserConfig()
                    .getDigesterFactory()
                    .isSkipContainerDocument()) {
                skipContainer = true;
            }
            return new DigestingParser(parser, DIGESTER, skipContainer);
        }
        return parser;
    }

    public static TikaConfig getConfig() {
        return TIKA_CONFIG;
    }

    public static String detectFilename(MultiValueMap<String, String> httpHeaders) {

        String disposition = httpHeaders.getFirst("Content-Disposition");
        if (disposition != null) {
            ContentDisposition c = new ContentDisposition(disposition);

            // only support "attachment" dispositions
            if ("attachment".equals(c.getType())) {
                String fn = c.getParameter("filename");
                if (fn != null) {
                    return fn;
                }
            }
        }

        // this really should not be used, since it's not an official field
        return httpHeaders.getFirst("File-Name");
    }

    public static void fillParseContext(MultiValueMap<String, String> httpHeaders, Metadata metadata, ParseContext parseContext) {
        PARSE_CONTEXT_CONFIG.configure(httpHeaders, metadata, parseContext);
    }

    public static InputStream getInputStream(InputStream is, Metadata metadata, RequestContext request) {
        try {
            return INPUTSTREAM_FACTORY.getInputStream(is, metadata, request);
        } catch (IOException e) {
            throw new TikaServerParseException(e);
        }
    }

    /**
     * Utility method to set a property on a class via reflection.
     *
     * @param object the <code>Object</code> to set the property on.
     * @param key    the key of the HTTP Header.
     * @param val    the value of HTTP header.
     * @param prefix the name of the HTTP Header prefix used to find property.
     * @throws WebApplicationException thrown when field cannot be found.
     */
    public static void processHeaderConfig(Object object, String key, String val, String prefix) {
        try {
            String property = Strings.CI.removeStart(key, prefix);
            Field field = null;
            try {
                field = object
                        .getClass()
                        .getDeclaredField(StringUtils.uncapitalize(property));
            } catch (NoSuchFieldException e) {
                // try to match field case-insensitive way
                for (Field aField : object
                        .getClass()
                        .getDeclaredFields()) {
                    if (aField
                            .getName()
                            .equalsIgnoreCase(property)) {
                        field = aField;
                        break;
                    }
                }
            }
            String setter = field != null ? field.getName() : property;
            setter = "set" + setter
                    .substring(0, 1)
                    .toUpperCase(Locale.US) + setter.substring(1);
            //default assume string class
            //if there's a more specific type, e.g. double, int, boolean
            //try that.
            Class clazz = String.class;
            if (field != null) {
                if (field.getType() == int.class || field.getType() == Integer.class) {
                    clazz = int.class;
                } else if (field.getType() == double.class) {
                    clazz = double.class;
                } else if (field.getType() == Double.class) {
                    clazz = Double.class;
                } else if (field.getType() == float.class) {
                    clazz = float.class;
                } else if (field.getType() == Float.class) {
                    clazz = Float.class;
                } else if (field.getType() == boolean.class) {
                    clazz = boolean.class;
                } else if (field.getType() == Boolean.class) {
                    clazz = Boolean.class;
                } else if (field.getType() == long.class) {
                    clazz = long.class;
                } else if (field.getType() == Long.class) {
                    clazz = Long.class;
                }
            }

            Method m = tryToGetMethod(object, setter, clazz);
            //if you couldn't find more specific setter, back off
            //to string setter and try that.
            if (m == null && clazz != String.class) {
                m = tryToGetMethod(object, setter, String.class);
            }

            if (m != null) {
                if (clazz == String.class) {
                    checkTrustWorthy(setter, val);
                    m.invoke(object, val);
                } else if (clazz == int.class || clazz == Integer.class) {
                    m.invoke(object, Integer.parseInt(val));
                } else if (clazz == double.class || clazz == Double.class) {
                    m.invoke(object, Double.parseDouble(val));
                } else if (clazz == boolean.class || clazz == Boolean.class) {
                    m.invoke(object, Boolean.parseBoolean(val));
                } else if (clazz == float.class || clazz == Float.class) {
                    m.invoke(object, Float.parseFloat(val));
                } else if (clazz == long.class || clazz == Long.class) {
                    m.invoke(object, Long.parseLong(val));
                } else {
                    throw new IllegalArgumentException("setter must be String, int, float, double or boolean...for now");
                }
            } else {
                throw new NoSuchMethodException("Couldn't find: " + setter);
            }

        } catch (Throwable ex) {
            // TIKA-3345
            String error = (!(ex.getCause() instanceof IllegalArgumentException)) ? String.format(Locale.ROOT, "%s is an invalid %s header", key, prefix) :
                    String.format(Locale.ROOT, "%s is an invalid %s header value", val, key);
            throw new HttpStatusException(error, HttpStatus.BAD_REQUEST);
        }
    }

    private static void checkTrustWorthy(String setter, String val) {
        if (setter == null || val == null) {
            throw new IllegalArgumentException("setter and val must not be null");
        }
        if (setter
                .toLowerCase(Locale.US)
                .contains("trusted")) {
            throw new IllegalArgumentException("Can't call a trusted method via tika-server headers");
        }
        Matcher m = ALLOWABLE_HEADER_CHARS.matcher(val);
        if (!m.find()) {
            throw new IllegalArgumentException("Header val: " + val + " contains illegal characters. " + "Must contain: TikaResource.ALLOWABLE_HEADER_CHARS");
        }
    }

    /**
     * Tries to get method. Silently swallows NoMethodException and returns
     * <code>null</code> if not found.
     *
     * @param object the object to get method from.
     * @param method the name of the method to get.
     * @param clazz  the parameter type of the method to get.
     * @return the found method instance
     */
    private static Method tryToGetMethod(Object object, String method, Class clazz) {
        try {
            return object
                    .getClass()
                    .getMethod(method, clazz);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @SuppressWarnings("serial")
    public static void fillMetadata(Parser parser, Metadata metadata, MultiValueMap<String, String> httpHeaders) {
        String fileName = detectFilename(httpHeaders);
        if (fileName != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        }

        String contentTypeHeader = httpHeaders.getFirst("Content-Type");
        WebMediaType mediaType = (contentTypeHeader == null || "*/*".equals(contentTypeHeader)) ? null : WebMediaType.valueOf(contentTypeHeader);
        if (mediaType != null && "xml".equals(mediaType.getSubtype())) {
            mediaType = null;
        }

        if (mediaType != null && mediaType.equals(WebMediaType.APPLICATION_OCTET_STREAM_TYPE)) {
            mediaType = null;
        }

        if (mediaType != null) {
            metadata.add(Metadata.CONTENT_TYPE, mediaType.toString());
            metadata.add(TikaCoreProperties.CONTENT_TYPE_USER_OVERRIDE, mediaType.toString());
        }

        if (httpHeaders.containsKey("Content-Length")) {
            metadata.set(Metadata.CONTENT_LENGTH, httpHeaders.getFirst("Content-Length"));
        }

        for (Map.Entry<String, List<String>> e : httpHeaders.entrySet()) {
            if (e
                    .getKey()
                    .startsWith(META_PREFIX)) {
                String tikaKey = e
                        .getKey()
                        .substring(META_PREFIX.length());
                for (String value : e.getValue()) {
                    metadata.add(tikaKey, value);
                }
            }
        }
    }

    /**
     * Use this to call a parser and unify exception handling.
     * NOTE: This call to parse closes the InputStream. DO NOT surround
     * the call in an auto-close block.
     *
     * @param parser       parser to use
     * @param logger       logger to use
     * @param path         file path
     * @param inputStream  inputStream (which is closed by this call!)
     * @param handler      handler to use
     * @param metadata     metadata
     * @param parseContext parse context
     * @throws IOException wrapper for all exceptions
     */
    public static void parse(Parser parser, Logger logger, String path, InputStream inputStream, ContentHandler handler, Metadata metadata, ParseContext parseContext)
            throws IOException {

        checkIsOperating();
        String fileName = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
        long timeoutMillis = getTaskTimeout(parseContext);

        long taskId = SERVER_STATUS.start(ServerStatus.TASK.PARSE, fileName, timeoutMillis);
        try {
            parser.parse(inputStream, handler, metadata, parseContext);
        } catch (SAXException e) {
            throw new TikaServerParseException(e);
        } catch (EncryptedDocumentException e) {
            logger.warn("{}: Encrypted document ({})", path, fileName, e);
            throw new TikaServerParseException(e);
        } catch (Exception e) {
            if (!WriteLimitReachedException.isWriteLimitReached(e)) {
                logger.warn("{}: Text extraction failed ({})", path, fileName, e);
            }
            throw new TikaServerParseException(e);
        } catch (OutOfMemoryError e) {
            logger.warn("{}: OOM ({})", path, fileName, e);
            SERVER_STATUS.setStatus(ServerStatus.STATUS.ERROR);
            throw e;
        } finally {
            SERVER_STATUS.complete(taskId);
            inputStream.close();
        }
    }

    protected static long getTaskTimeout(ParseContext parseContext) {

        TikaTaskTimeout tikaTaskTimeout = parseContext.get(TikaTaskTimeout.class);
        long timeoutMillis = TIKA_SERVER_CONFIG.getTaskTimeoutMillis();

        if (tikaTaskTimeout != null) {
            if (tikaTaskTimeout.getTimeoutMillis() > TIKA_SERVER_CONFIG.getTaskTimeoutMillis()) {
                throw new IllegalArgumentException(
                        "Can't request a timeout ( " + tikaTaskTimeout.getTimeoutMillis() + "ms) greater than the taskTimeoutMillis set in the server config (" +
                                TIKA_SERVER_CONFIG.getTaskTimeoutMillis() + "ms)");
            }
            timeoutMillis = tikaTaskTimeout.getTimeoutMillis();
            if (timeoutMillis < TIKA_SERVER_CONFIG.getMinimumTimeoutMillis()) {
                throw new HttpStatusException(new IllegalArgumentException(
                        "taskTimeoutMillis must be > " + "minimumTimeoutMillis, currently set to (" + TIKA_SERVER_CONFIG.getMinimumTimeoutMillis() + "ms)"),
                        HttpStatus.BAD_REQUEST);
            }
        }
        return timeoutMillis;
    }

    public static void checkIsOperating() {
        //check that server is not in shutdown mode
        if (!SERVER_STATUS.isOperating()) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public static void logRequest(Logger logger, String endpoint, Metadata metadata) {

        if (metadata.get(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE) == null) {
            logger.info("{} (autodetecting type)", endpoint);
        } else {
            logger.info("{} ({})", endpoint, metadata.get(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE));
        }
    }

    public static boolean getThrowOnWriteLimitReached(MultiValueMap<String, String> httpHeaders) {
        if (httpHeaders.containsKey("throwOnWriteLimitReached")) {
            String val = httpHeaders.getFirst("throwOnWriteLimitReached");
            if ("true".equalsIgnoreCase(val)) {
                return true;
            } else if ("false".equalsIgnoreCase(val)) {
                return false;
            } else {
                throw new IllegalArgumentException("'throwOnWriteLimitReached' must be either 'true' or 'false'");
            }
        }
        return HandlerConfig.DEFAULT_HANDLER_CONFIG.isThrowOnWriteLimitReached();
    }

    @GetMapping(produces = "text/plain")
    public String getMessage() {
        checkIsOperating();
        return GREETING;
    }

    @PostMapping(path = "form", consumes = "multipart/form-data", produces = "text/plain")
    public StreamingOutput getTextFromMultipart(Attachment att, RequestContext request) {
        return produceText(att.getInputStream(), new Metadata(), preparePostHeaderMap(att, request), request);
    }

    //this is equivalent to text-main in tika-app
    @PutMapping(path = "main", consumes = "*/*", produces = "text/plain")
    public StreamingOutput getTextMain(final InputStream is, RequestContext request) {
        return produceTextMain(is, request.getHeaders(), request);
    }

    //this is equivalent to text-main (Boilerpipe handler) in tika-app
    @PostMapping(path = "form/main", consumes = "multipart/form-data", produces = "text/plain")
    public StreamingOutput getTextMainFromMultipart(final Attachment att, RequestContext request) {
        return produceTextMain(att.getInputStream(), preparePostHeaderMap(att, request), request);
    }

    public StreamingOutput produceTextMain(final InputStream is, MultiValueMap<String, String> httpHeaders, final RequestContext request) {
        final Parser parser = createParser();
        final Metadata metadata = new Metadata();
        final ParseContext context = new ParseContext();

        fillMetadata(parser, metadata, httpHeaders);
        fillParseContext(httpHeaders, metadata, context);

        logRequest(LOG, "/tika", metadata);

        return outputStream -> {
            Writer writer = new OutputStreamWriter(outputStream, UTF_8);

            ContentHandler handler = new BoilerpipeContentHandler(writer);

            parse(parser, LOG, request.getPath(), is, handler, metadata, context);
        };
    }

    @PutMapping(consumes = "*/*", produces = "text/plain")
    public StreamingOutput getText(final InputStream is, RequestContext request) {
        final Metadata metadata = new Metadata();
        return produceText(getInputStream(is, metadata, request), metadata, request.getHeaders(), request);
    }

    public StreamingOutput produceText(final InputStream is, final Metadata metadata, MultiValueMap<String, String> httpHeaders, final RequestContext request) {
        final Parser parser = createParser();
        final ParseContext context = new ParseContext();

        fillMetadata(parser, metadata, httpHeaders);
        fillParseContext(httpHeaders, metadata, context);

        logRequest(LOG, "/tika", metadata);

        return outputStream -> {
            Writer writer = new OutputStreamWriter(outputStream, UTF_8);

            BodyContentHandler body = new BodyContentHandler(new RichTextContentHandler(writer));

            parse(parser, LOG, request.getPath(), is, body, metadata, context);
        };
    }

    @PutMapping(path = "md", consumes = "*/*", produces = "text/plain")
    public StreamingOutput getMarkdown(final InputStream is, RequestContext request) {
        final Metadata metadata = new Metadata();
        return produceMarkdown(getInputStream(is, metadata, request), metadata,
                request.getHeaders(), request);
    }

    @PostMapping(path = "form/md", consumes = "multipart/form-data", produces = "text/plain")
    public StreamingOutput getMarkdownFromMultipart(Attachment att,
                                                    RequestContext request) {
        return produceMarkdown(att.getInputStream(), new Metadata(),
                preparePostHeaderMap(att, request), request);
    }

    public StreamingOutput produceMarkdown(final InputStream is, final Metadata metadata,
                                           MultiValueMap<String, String> httpHeaders,
                                           final RequestContext request) {
        final Parser parser = createParser();
        final ParseContext context = new ParseContext();

        fillMetadata(parser, metadata, httpHeaders);
        fillParseContext(httpHeaders, metadata, context);

        logRequest(LOG, "/tika", metadata);

        return outputStream -> {
            Writer writer = new OutputStreamWriter(outputStream, UTF_8);

            ContentHandler handler = new ToMarkdownContentHandler(writer);

            parse(parser, LOG, request.getPath(), is, handler, metadata, context);
        };
    }

    @PostMapping(path = "form", consumes = "multipart/form-data", produces = "text/html")
    public StreamingOutput getHTMLFromMultipart(Attachment att, RequestContext request) {
        return produceOutput(att.getInputStream(), new Metadata(), preparePostHeaderMap(att, request), request, "html");
    }

    @PutMapping(consumes = "*/*", produces = "text/html")
    public StreamingOutput getHTML(final InputStream is, RequestContext request) {
        Metadata metadata = new Metadata();
        return produceOutput(getInputStream(is, metadata, request), metadata, request.getHeaders(), request, "html");
    }

    @PostMapping(path = "form", consumes = "multipart/form-data", produces = "text/xml")
    public StreamingOutput getXMLFromMultipart(Attachment att, RequestContext request) {
        return produceOutput(att.getInputStream(), new Metadata(), preparePostHeaderMap(att, request), request, "xml");
    }

    @PutMapping(consumes = "*/*", produces = "text/xml")
    public StreamingOutput getXML(final InputStream is, RequestContext request) {
        Metadata metadata = new Metadata();
        return produceOutput(getInputStream(is, metadata, request), metadata, request.getHeaders(), request, "xml");
    }

    @PostMapping(path = "form{" + HANDLER_TYPE_PARAM + " : (\\w+)?}", consumes = "multipart/form-data", produces = "application/json")
    public Metadata getJsonFromMultipart(Attachment att, RequestContext request, @PathVariable(HANDLER_TYPE_PARAM) String handlerTypeName)
            throws IOException, TikaException {
        Metadata metadata = new Metadata();
        parseToMetadata(getInputStream(att.getInputStream(), metadata, request), metadata, preparePostHeaderMap(att, request), request, handlerTypeName);
        TikaResource
                .getConfig()
                .getMetadataFilter()
                .filter(metadata);
        return metadata;
    }

    @PutMapping(path = "{" + HANDLER_TYPE_PARAM + " : (\\w+)?}", consumes = "*/*", produces = "application/json")
    public Metadata getJson(final InputStream is, RequestContext request, @PathVariable(HANDLER_TYPE_PARAM) String handlerTypeName)
            throws IOException, TikaException {
        Metadata metadata = new Metadata();
        parseToMetadata(getInputStream(is, metadata, request), metadata, request.getHeaders(), request, handlerTypeName);
        TikaResource
                .getConfig()
                .getMetadataFilter()
                .filter(metadata);
        return metadata;
    }

    private void parseToMetadata(InputStream inputStream, Metadata metadata, MultiValueMap<String, String> httpHeaders, RequestContext request, String handlerTypeName)
            throws IOException {
        final Parser parser = createParser();
        final ParseContext context = new ParseContext();

        fillMetadata(parser, metadata, httpHeaders);
        fillParseContext(httpHeaders, metadata, context);

        logRequest(LOG, "/tika", metadata);
        int writeLimit = -1;
        boolean throwOnWriteLimitReached = getThrowOnWriteLimitReached(httpHeaders);
        if (httpHeaders.containsKey("writeLimit")) {
            writeLimit = Integer.parseInt(httpHeaders.getFirst("writeLimit"));
        }

        BasicContentHandlerFactory.HANDLER_TYPE type = BasicContentHandlerFactory.parseHandlerType(handlerTypeName, DEFAULT_HANDLER_TYPE);
        BasicContentHandlerFactory fact = new BasicContentHandlerFactory(type, writeLimit, throwOnWriteLimitReached, context);
        ContentHandler contentHandler = fact.getNewContentHandler();

        try {
            parse(parser, LOG, request.getPath(), inputStream, contentHandler, metadata, context);
        } catch (TikaServerParseException e) {
            Throwable cause = e.getCause();
            boolean writeLimitReached = false;
            if (WriteLimitReachedException.isWriteLimitReached(cause)) {
                metadata.set(TikaCoreProperties.WRITE_LIMIT_REACHED, "true");
                writeLimitReached = true;
            }
            if (TIKA_SERVER_CONFIG.isReturnStackTrace()) {
                if (cause != null) {
                    metadata.add(TikaCoreProperties.CONTAINER_EXCEPTION, ExceptionUtils.getStackTrace(cause));
                } else {
                    metadata.add(TikaCoreProperties.CONTAINER_EXCEPTION, ExceptionUtils.getStackTrace(e));
                }
            } else if (!writeLimitReached) {
                throw e;
            }
        } catch (OutOfMemoryError e) {
            if (TIKA_SERVER_CONFIG.isReturnStackTrace()) {
                metadata.add(TikaCoreProperties.CONTAINER_EXCEPTION, ExceptionUtils.getStackTrace(e));
            } else {
                throw e;
            }
        } finally {
            metadata.add(TikaCoreProperties.TIKA_CONTENT, contentHandler.toString());
        }
    }

    private StreamingOutput produceOutput(final InputStream is, Metadata metadata, final MultiValueMap<String, String> httpHeaders, final RequestContext request,
                                          final String format) {
        final Parser parser = createParser();
        final ParseContext context = new ParseContext();

        fillMetadata(parser, metadata, httpHeaders);
        fillParseContext(httpHeaders, metadata, context);


        logRequest(LOG, "/tika", metadata);

        return outputStream -> {
            Writer writer = new OutputStreamWriter(outputStream, UTF_8);
            ContentHandler content;

            try {
                SAXTransformerFactory factory = XMLReaderUtils.getSAXTransformerFactory();
                TransformerHandler handler = factory.newTransformerHandler();
                handler
                        .getTransformer()
                        .setOutputProperty(OutputKeys.METHOD, format);
                handler
                        .getTransformer()
                        .setOutputProperty(OutputKeys.INDENT, "yes");
                handler
                        .getTransformer()
                        .setOutputProperty(OutputKeys.ENCODING, UTF_8.name());
                handler
                        .getTransformer()
                        .setOutputProperty(OutputKeys.VERSION, "1.1");
                handler.setResult(new StreamResult(writer));
                content = new ExpandedTitleContentHandler(handler);
            } catch (TransformerConfigurationException | TikaException e) {
                throw new HttpStatusException(e);
            }

            parse(parser, LOG, request.getPath(), is, content, metadata, context);
        };
    }

    /**
     * Prepares a multivalued map, combining attachment headers and request headers.
     * Gives priority to attachment headers.
     *
     * @param att     the attachment.
     * @param request the request.
     * @return the case insensitive map containing combined headers.
     */
    private MultiValueMap<String, String> preparePostHeaderMap(Attachment att, RequestContext request) {
        if (att == null && request == null) {
            return null;
        }
        MultiValueMap<String, String> finalHeaders = CollectionUtils.toMultiValueMap(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
        if (request != null && request.getHeaders() != null) {
            finalHeaders.putAll(request.getHeaders());
        }
        if (att != null && att.getHeaders() != null) {
            finalHeaders.putAll(att.getHeaders());
        }
        return finalHeaders;
    }
}
