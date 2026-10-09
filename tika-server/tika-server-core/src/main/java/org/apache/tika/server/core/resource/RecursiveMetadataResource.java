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

import static org.apache.tika.server.core.resource.TikaResource.fillMetadata;
import static org.apache.tika.server.core.resource.TikaResource.fillParseContext;
import static org.apache.tika.server.core.resource.TikaResource.getConfig;

import java.io.InputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.listfilter.MetadataListFilter;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.RecursiveParserWrapper;
import org.apache.tika.pipes.HandlerConfig;
import org.apache.tika.sax.BasicContentHandlerFactory;
import org.apache.tika.sax.RecursiveParserWrapperHandler;
import org.apache.tika.server.core.MetadataList;
import org.apache.tika.server.core.TikaServerParseException;
import org.apache.tika.server.core.web.Attachment;
import org.apache.tika.server.core.web.HttpStatusException;
import org.apache.tika.server.core.web.RequestContext;

@RequestMapping("/rmeta")
public class RecursiveMetadataResource {

    protected static final String HANDLER_TYPE_PARAM = "handler";
    protected static final BasicContentHandlerFactory.HANDLER_TYPE DEFAULT_HANDLER_TYPE = BasicContentHandlerFactory.HANDLER_TYPE.XML;
    private static final Logger LOG = LoggerFactory.getLogger(RecursiveMetadataResource.class);

    public static List<Metadata> parseMetadata(InputStream is, Metadata metadata, MultiValueMap<String, String> httpHeaders, RequestContext request, HandlerConfig handlerConfig)
            throws Exception {

        final ParseContext context = new ParseContext();
        Parser parser = TikaResource.createParser();

        RecursiveParserWrapper wrapper = new RecursiveParserWrapper(parser);
        fillMetadata(parser, metadata, httpHeaders);
        fillParseContext(httpHeaders, metadata, context);
        TikaResource.logRequest(LOG, "/rmeta", metadata);

        BasicContentHandlerFactory.HANDLER_TYPE type = handlerConfig.getType();
        RecursiveParserWrapperHandler handler =
                new RecursiveParserWrapperHandler(new BasicContentHandlerFactory(type, handlerConfig.getWriteLimit(), handlerConfig.isThrowOnWriteLimitReached(), context),
                        handlerConfig.getMaxEmbeddedResources(), TikaResource
                        .getConfig()
                        .getMetadataFilter());
        try {
            TikaResource.parse(wrapper, LOG, "/rmeta", is, handler, metadata, context);
        } catch (TikaServerParseException e) {
            //do nothing
            LOG.debug("server parse exception", e);
        } catch (SecurityException | HttpStatusException e) {
            throw e;
        } catch (Exception e) {
            //we shouldn't get here?
            LOG.error("something went seriously wrong", e);
        }
        MetadataListFilter metadataListFilter = context.get(MetadataListFilter.class, getConfig().getMetadataListFilter());
        return metadataListFilter.filter(handler.getMetadataList());
    }

    static HandlerConfig buildHandlerConfig(MultiValueMap<String, String> httpHeaders, String handlerTypeName, HandlerConfig.PARSE_MODE parseMode) {
        int writeLimit = -1;
        if (httpHeaders.containsKey("writeLimit")) {
            writeLimit = Integer.parseInt(httpHeaders.getFirst("writeLimit"));
        }

        int maxEmbeddedResources = -1;
        if (httpHeaders.containsKey("maxEmbeddedResources")) {
            maxEmbeddedResources = Integer.parseInt(httpHeaders.getFirst("maxEmbeddedResources"));
        }
        return new HandlerConfig(BasicContentHandlerFactory.parseHandlerType(handlerTypeName, DEFAULT_HANDLER_TYPE), parseMode, writeLimit, maxEmbeddedResources,
                TikaResource.getThrowOnWriteLimitReached(httpHeaders));
    }

    /**
     * Returns an InputStream that can be deserialized as a list of
     * {@link Metadata} objects.
     * The first in the list represents the main document, and the
     * rest represent metadata for the embedded objects.  This works
     * recursively through all descendants of the main document, not
     * just the immediate children.
     * <p>
     * The extracted text content is stored with the key
     * {@link org.apache.tika.metadata.TikaCoreProperties#TIKA_CONTENT}.
     * <p>
     * Specify the handler for the content (xml, html, text, markdown/md, ignore)
     * in the path:<br/>
     * /rmeta/form (default: xml)<br/>
     * /rmeta/form/xml    (store the content as xml)<br/>
     * /rmeta/form/text   (store the content as text)<br/>
     * /rmeta/form/md     (store the content as markdown)<br/>
     * /rmeta/form/ignore (don't record any content)<br/>
     *
     * @param att             attachment
     * @param request         the request
     * @param handlerTypeName which type of handler to use
     * @return InputStream that can be deserialized as a list of {@link Metadata} objects
     * @throws Exception
     */
    @PostMapping(path = "form{" + HANDLER_TYPE_PARAM + " : (\\w+)?}", consumes = "multipart/form-data", produces = {"application/json"})
    public ResponseEntity<?> getMetadataFromMultipart(Attachment att, RequestContext request, @PathVariable(HANDLER_TYPE_PARAM) String handlerTypeName) throws Exception {
        return ResponseEntity.ok(parseMetadataToMetadataList(att.getInputStream(), new Metadata(), att.getHeaders(), request,
                        buildHandlerConfig(att.getHeaders(), handlerTypeName, HandlerConfig.PARSE_MODE.RMETA)));
    }

    /**
     * Returns an InputStream that can be deserialized as a list of
     * {@link Metadata} objects.
     * The first in the list represents the main document, and the
     * rest represent metadata for the embedded objects.  This works
     * recursively through all descendants of the main document, not
     * just the immediate children.
     * <p>
     * The extracted text content is stored with the key
     * {@link org.apache.tika.metadata.TikaCoreProperties#TIKA_CONTENT}.
     * <p>
     * Specify the handler for the content (xml, html, text, markdown/md, ignore)
     * in the path:<br/>
     * /rmeta (default: xml)<br/>
     * /rmeta/xml    (store the content as xml)<br/>
     * /rmeta/text   (store the content as text)<br/>
     * /rmeta/md     (store the content as markdown)<br/>
     * /rmeta/ignore (don't record any content)<br/>
     *
     * @param request         the request
     * @param handlerTypeName which type of handler to use
     * @return InputStream that can be deserialized as a list of {@link Metadata} objects
     * @throws Exception
     */

    @PutMapping(path = "{" + HANDLER_TYPE_PARAM + " : (\\w+)?}", produces = "application/json")
    public ResponseEntity<?> getMetadata(InputStream is, RequestContext request, @PathVariable(HANDLER_TYPE_PARAM) String handlerTypeName) throws Exception {
        Metadata metadata = new Metadata();
        return ResponseEntity.ok(parseMetadataToMetadataList(TikaResource.getInputStream(is, metadata, request), metadata, request.getHeaders(), request,
                        buildHandlerConfig(request.getHeaders(), handlerTypeName, HandlerConfig.PARSE_MODE.RMETA)));
    }

    private MetadataList parseMetadataToMetadataList(InputStream is, Metadata metadata, MultiValueMap<String, String> httpHeaders, RequestContext request,
                                                     HandlerConfig handlerConfig)
            throws Exception {
        return new MetadataList(parseMetadata(is, metadata, httpHeaders, request, handlerConfig));
    }
}
