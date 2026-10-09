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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An HTTP media type as tika-server has always parsed and compared them.
 * <p>
 * Type and subtype are lower-cased; parameter names are lower-cased and kept in
 * case-insensitive alphabetical order, which is also the order {@link #toString()}
 * prints them in. Parsing is lenient the way clients of tika-server rely on: a value
 * without a subtype becomes <code>text/plain</code> ("text"),
 * <code>application/xml</code> ("xml") or <code>*&#47;*</code> (anything else).
 */
public final class WebMediaType {

    public static final String WILDCARD = "*";

    public static final WebMediaType WILDCARD_TYPE = new WebMediaType(WILDCARD, WILDCARD);
    public static final WebMediaType TEXT_PLAIN_TYPE = new WebMediaType("text", "plain");
    public static final WebMediaType TEXT_HTML_TYPE = new WebMediaType("text", "html");
    public static final WebMediaType TEXT_XML_TYPE = new WebMediaType("text", "xml");
    public static final WebMediaType APPLICATION_JSON_TYPE = new WebMediaType("application", "json");
    public static final WebMediaType APPLICATION_XML_TYPE = new WebMediaType("application", "xml");
    public static final WebMediaType APPLICATION_OCTET_STREAM_TYPE = new WebMediaType("application", "octet-stream");

    private static final Pattern COMPLEX_PARAMETERS = Pattern.compile("(([\\w-]+=\"[^\"]*\")|([\\w-]+=[\\w-/\\+]+))");

    private final String type;
    private final String subtype;
    private final Map<String, String> parameters;

    public WebMediaType(String type, String subtype) {
        this(type, subtype, Collections.emptyMap());
    }

    public WebMediaType(String type, String subtype, Map<String, String> parameters) {
        this.type = type == null ? WILDCARD : type;
        this.subtype = subtype == null ? WILDCARD : subtype;
        TreeMap<String, String> params = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (parameters != null) {
            params.putAll(parameters);
        }
        this.parameters = Collections.unmodifiableMap(params);
    }

    /**
     * Parses one media type.
     *
     * @param value the header value
     * @return the media type
     * @throws IllegalArgumentException if the value is not a media type
     */
    public static WebMediaType valueOf(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Media type value can not be null");
        }
        int slash = value.indexOf('/');
        if (slash == -1) {
            return withoutSubtype(value.trim());
        } else if (slash == 0) {
            throw new IllegalArgumentException("Invalid media type string: " + value);
        }
        int paramsStart = value.indexOf(';', slash + 1);
        int end = paramsStart == -1 ? value.length() : paramsStart;
        String type = value.substring(0, slash).trim();
        String subtype = value.substring(slash + 1, end).trim();
        if (!isValid(type) || !isValid(subtype)) {
            throw new IllegalArgumentException("Invalid media type string: " + value);
        }
        Map<String, String> parameters = Collections.emptyMap();
        if (paramsStart != -1) {
            parameters = new LinkedHashMap<>();
            String paramString = value.substring(paramsStart + 1);
            if (paramString.contains("\"")) {
                Matcher m = COMPLEX_PARAMETERS.matcher(paramString);
                while (m.find()) {
                    addParameter(parameters, m.group().trim());
                }
            } else {
                StringTokenizer st = new StringTokenizer(paramString, ";");
                while (st.hasMoreTokens()) {
                    addParameter(parameters, st.nextToken());
                }
            }
        }
        return new WebMediaType(type.toLowerCase(Locale.ROOT), subtype.toLowerCase(Locale.ROOT), parameters);
    }

    private static WebMediaType withoutSubtype(String value) {
        if (value.startsWith(WILDCARD)) {
            String rest = value.length() == 1 ? "" : value.substring(1).trim();
            if (rest.isEmpty()) {
                return WILDCARD_TYPE;
            }
            if (rest.startsWith(";")) {
                Map<String, String> parameters = new LinkedHashMap<>();
                StringTokenizer st = new StringTokenizer(value.substring(2).trim(), ";");
                while (st.hasMoreTokens()) {
                    addParameter(parameters, st.nextToken());
                }
                return new WebMediaType(WILDCARD, WILDCARD, parameters);
            }
        }
        if (value.equals(TEXT_PLAIN_TYPE.getType())) {
            return TEXT_PLAIN_TYPE;
        } else if (value.equals(APPLICATION_XML_TYPE.getSubtype())) {
            return APPLICATION_XML_TYPE;
        }
        return WILDCARD_TYPE;
    }

    private static void addParameter(Map<String, String> parameters, String token) {
        int equalSign = token.indexOf('=');
        if (equalSign == -1) {
            throw new IllegalArgumentException("Wrong media type parameter, separator is missing");
        }
        parameters.put(token.substring(0, equalSign).trim().toLowerCase(Locale.ROOT), token.substring(equalSign + 1).trim());
    }

    private static boolean isValid(String str) {
        if (str.isEmpty()) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if ("/\\?:<>;()@,[]=".indexOf(str.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    public String getType() {
        return type;
    }

    public String getSubtype() {
        return subtype;
    }

    /**
     * @return the parameters, keyed case-insensitively, in case-insensitive name order
     */
    public Map<String, String> getParameters() {
        return parameters;
    }

    public boolean isWildcardType() {
        return WILDCARD.equals(type);
    }

    public boolean isWildcardSubtype() {
        return WILDCARD.equals(subtype);
    }

    /**
     * @param other another media type
     * @return true if either side's wildcards cover the other, ignoring parameters
     */
    public boolean isCompatible(WebMediaType other) {
        if (other == null) {
            return false;
        }
        if (isWildcardType() || other.isWildcardType()) {
            return true;
        }
        if (type.equalsIgnoreCase(other.type) && (isWildcardSubtype() || other.isWildcardSubtype())) {
            return true;
        }
        return type.equalsIgnoreCase(other.type) && subtype.equalsIgnoreCase(other.subtype);
    }

    /**
     * @return this type without any parameter
     */
    public WebMediaType withoutParameters() {
        return parameters.isEmpty() ? this : new WebMediaType(type, subtype);
    }

    /**
     * @param ignored parameter names left out
     * @return <code>type/subtype</code> followed by <code>;name=value</code> for every other parameter
     */
    public String toString(String... ignored) {
        StringBuilder sb = new StringBuilder();
        sb.append(type).append('/').append(subtype);
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            boolean skip = false;
            for (String name : ignored) {
                if (name.equals(e.getKey())) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                sb.append(';').append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toString(new String[0]);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof WebMediaType)) {
            return false;
        }
        WebMediaType other = (WebMediaType) obj;
        return type.equalsIgnoreCase(other.type) && subtype.equalsIgnoreCase(other.subtype) && parameters.equals(other.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type.toLowerCase(Locale.ROOT), subtype.toLowerCase(Locale.ROOT), parameters);
    }
}
