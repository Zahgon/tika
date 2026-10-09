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
package org.apache.tika.server.standard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.serialization.JsonMetadata;
import org.apache.tika.server.core.TikaServerTestBase;
import org.apache.tika.server.core.client.Response;
import org.apache.tika.server.core.client.WebClient;
import org.apache.tika.server.core.resource.MetadataResource;
import org.apache.tika.server.core.web.TikaWebSettings;
import org.apache.tika.server.core.writer.CSVMessageBodyWriter;
import org.apache.tika.server.core.writer.JSONMessageBodyWriter;
import org.apache.tika.server.core.writer.TextMessageBodyWriter;
import org.apache.tika.server.standard.resource.XMPMetadataResource;
import org.apache.tika.server.standard.writer.XMPMessageBodyWriter;

public class MetadataResourceTest extends TikaServerTestBase {

    private static final String META_PATH = "/meta";

    @Override
    protected void setUpResources(TikaWebSettings sf) {
        sf.getResources().add(new MetadataResource());
        sf.getResources().add(new XMPMetadataResource());
    }

    @Override
    protected void setUpProviders(TikaWebSettings sf) {
        List<Object> providers = new ArrayList<>();
        providers.add(new JSONMessageBodyWriter());
        providers.add(new CSVMessageBodyWriter());
        providers.add(new XMPMessageBodyWriter());
        providers.add(new TextMessageBodyWriter());
        addProviders(sf, providers);
    }

    @Test
    public void testSimpleWord() throws Exception {
        Response response = WebClient
                .create(endPoint + META_PATH)
                .type("application/msword")
                .accept("text/csv")
                .put(ClassLoader.getSystemResourceAsStream(TikaResourceTest.TEST_DOC));

        Reader reader = new InputStreamReader((InputStream) response.getEntity(), UTF_8);

        CSVParser csvReader = CSVParser.builder().setReader(reader).setFormat(CSVFormat.EXCEL).get();

        Map<String, String> metadata = new HashMap<>();

        for (CSVRecord r : csvReader) {
            metadata.put(r.get(0), r.get(1));
        }
        csvReader.close();

        assertNotNull(metadata.get(TikaCoreProperties.CREATOR.getName()));
        assertEquals("Maxim Valyanskiy", metadata.get(TikaCoreProperties.CREATOR.getName()));
        assertEquals("f8be45c34e8919eedba48cc8d207fbf0", metadata.get("X-TIKA:digest:MD5"), "X-TIKA:digest:MD5");
    }

    @Test
    public void testPasswordProtected() throws Exception {
        Response response = WebClient
                .create(endPoint + META_PATH)
                .type("application/vnd.ms-excel")
                .accept("text/csv")
                .put(ClassLoader.getSystemResourceAsStream(TikaResourceTest.TEST_PASSWORD_PROTECTED));

        // Won't work, no password given
        assertEquals(500, response.getStatus());

        // Try again, this time with the wrong password
        response = WebClient
                .create(endPoint + META_PATH)
                .type("application/vnd.ms-excel")
                .accept("text/csv")
                .header("Password", "wrong password")
                .put(ClassLoader.getSystemResourceAsStream(TikaResourceTest.TEST_PASSWORD_PROTECTED));

        assertEquals(500, response.getStatus());

        // Try again, this time with the password
        response = WebClient
                .create(endPoint + META_PATH)
                .type("application/vnd.ms-excel")
                .accept("text/csv")
                .header("Password", "password")
                .put(ClassLoader.getSystemResourceAsStream(TikaResourceTest.TEST_PASSWORD_PROTECTED));

        // Will work
        assertEquals(200, response.getStatus());

        // Check results
        Map<String, String> metadata = new HashMap<>();
        try (Reader reader = new InputStreamReader((InputStream) response.getEntity(), UTF_8);
                CSVParser csvReader = CSVParser.builder().setReader(reader).setFormat(CSVFormat.EXCEL).get()) {
            for (CSVRecord r : csvReader) {
                metadata.put(r.get(0), r.get(1));
            }
        }

        assertNotNull(metadata.get(TikaCoreProperties.CREATOR.getName()));
        assertEquals("pavel", metadata.get(TikaCoreProperties.CREATOR.getName()));
    }

    @Test
    public void testJSON() throws Exception {
        Response response = WebClient
                .create(endPoint + META_PATH)
                .type("application/msword")
                .accept("application/json")
                .put(ClassLoader.getSystemResourceAsStream(TikaResourceTest.TEST_DOC));

        Reader reader = new InputStreamReader((InputStream) response.getEntity(), UTF_8);

        Metadata metadata = JsonMetadata.fromJson(reader);
        assertNotNull(metadata.get(TikaCoreProperties.CREATOR));
        assertEquals("Maxim Valyanskiy", metadata.get(TikaCoreProperties.CREATOR));
    }

    @Test
    public void testXMP() throws Exception {
        Response response = WebClient
                .create(endPoint + META_PATH)
                .type("application/msword")
                .accept("application/rdf+xml")
                .put(ClassLoader.getSystemResourceAsStream(TikaResourceTest.TEST_DOC));

        String result = IOUtils.toString((InputStream) response.getEntity(), UTF_8);
        assertContains("<rdf:li>Maxim Valyanskiy</rdf:li>", result);
    }

    //Now test requesting one field
    @Test
    public void testGetField_XXX_NotFound() throws Exception {
        Response response = WebClient
                .create(endPoint + META_PATH + "/xxx")
                .type("application/msword")
                .accept("application/json")
                .put(ClassLoader.getSystemResourceAsStream(TikaResourceTest.TEST_DOC));
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    public void testGetField_Author_TEXT_Partial_BAD_REQUEST() throws Exception {

        InputStream stream = ClassLoader.getSystemResourceAsStream(TikaResourceTest.TEST_DOC);

        Response response = WebClient
                .create(endPoint + META_PATH + "/Author")
                .type("application/msword")
                .accept("text/plain")
                .put(copy(stream, 8000));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    }

    @Test
    public void testGetField_Author_TEXT_Partial_Found() throws Exception {

        InputStream stream = ClassLoader.getSystemResourceAsStream(TikaResourceTest.TEST_DOC);

        Response response = WebClient
                .create(endPoint + META_PATH + "/" + TikaCoreProperties.CREATOR.getName())
                .type("application/msword")
                .accept("text/plain")
                .put(copy(stream, 12000));
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        String s = IOUtils.toString((InputStream) response.getEntity(), UTF_8);
        assertEquals("Maxim Valyanskiy", s);
    }

    @Test
    public void testGetField_Author_JSON_Partial_Found() throws Exception {

        InputStream stream = ClassLoader.getSystemResourceAsStream(TikaResourceTest.TEST_DOC);

        Response response = WebClient
                .create(endPoint + META_PATH + "/" + TikaCoreProperties.CREATOR.getName())
                .type("application/msword")
                .accept("application/json")
                .put(copy(stream, 12000));
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        Metadata metadata = JsonMetadata.fromJson(new InputStreamReader((InputStream) response.getEntity(), UTF_8));
        assertEquals("Maxim Valyanskiy", metadata.get(TikaCoreProperties.CREATOR));
        assertEquals(1, metadata.names().length);
    }

    @Test
    public void testGetField_Author_XMP_Partial_Found() throws Exception {

        InputStream stream = ClassLoader.getSystemResourceAsStream(TikaResourceTest.TEST_DOC);

        Response response = WebClient
                .create(endPoint + META_PATH + "/dc:creator")
                .type("application/msword")
                .accept("application/rdf+xml")
                .put(copy(stream, 12000));
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        String s = IOUtils.toString((InputStream) response.getEntity(), UTF_8);
        assertContains("<rdf:li>Maxim Valyanskiy</rdf:li>", s);
    }


}
