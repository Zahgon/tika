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

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A <code>Content-Disposition</code> header value, parsed the way tika-server always has:
 * the type is only recognised when a ';' comes before the first '='; parameter names are
 * case-insensitive; double quotes are removed from values; <code>filename*</code> in RFC 5987
 * form (UTF-8 or ISO-8859-1) wins over <code>filename</code>, and <code>&amp;#NNNN;</code>
 * code points in <code>filename</code> are decoded.
 */
public final class ContentDisposition {

    private static final Pattern PARAMS = Pattern.compile("[\\w-]++( )?\\*?=( )?((\"[^\"]++\")|([^;]+))");
    private static final Pattern EXT_PARAM = Pattern.compile("(?i)(UTF-8|ISO-8859-1)''((?:%[0-9a-f]{2}|\\S)+)");
    private static final Pattern CODEPOINT = Pattern.compile("&#[0-9]{4};|\\S");
    private static final String FILE_NAME = "filename";

    private final String type;
    private final Map<String, String> params = new LinkedHashMap<>();

    public ContentDisposition(String value) {
        String rest = value;
        String parsedType = null;
        int index = rest.indexOf(';');
        if (index > 0 && rest.indexOf('=') >= index) {
            parsedType = rest.substring(0, index).trim();
            rest = rest.substring(index + 1);
        }
        this.type = parsedType;
        String extendedFilename = null;
        Matcher m = PARAMS.matcher(rest);
        while (m.find()) {
            String group = m.group().trim();
            int eq = group.indexOf('=');
            String name;
            String paramValue = "";
            if (eq > 0) {
                name = group.substring(0, eq).trim();
                if (eq + 1 != group.length()) {
                    paramValue = group.substring(eq + 1).trim().replace("\"", "");
                }
            } else {
                name = group;
            }
            if ("filename*".equalsIgnoreCase(name)) {
                Matcher ext = EXT_PARAM.matcher(paramValue);
                if (ext.matches()) {
                    paramValue = percentDecode(ext.group(2), Charset.forName(ext.group(1)));
                    extendedFilename = paramValue;
                }
            } else if (FILE_NAME.equalsIgnoreCase(name) && paramValue.contains("&#")) {
                Matcher cp = CODEPOINT.matcher(paramValue);
                StringBuilder sb = new StringBuilder();
                while (cp.find()) {
                    String matched = cp.group();
                    if (matched.startsWith("&#")) {
                        sb.append(Character.toChars(Integer.parseInt(matched.substring(2, 6))));
                    } else {
                        sb.append(matched.charAt(0));
                    }
                }
                if (sb.length() > 0) {
                    paramValue = sb.toString();
                }
            }
            params.put(name.toLowerCase(Locale.ROOT), paramValue);
        }
        if (extendedFilename != null) {
            params.put(FILE_NAME, extendedFilename);
        }
    }

    private static String percentDecode(String encoded, Charset charset) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()) {
                bytes.write(Integer.parseInt(encoded.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(charset);
                bytes.write(b, 0, b.length);
            }
        }
        return bytes.toString(charset);
    }

    /**
     * @return the disposition type, or null when the value has none
     */
    public String getType() {
        return type;
    }

    /**
     * @param name a lower-case parameter name
     * @return its value, or null
     */
    public String getParameter(String name) {
        return params.get(name);
    }

    public Map<String, String> getParameters() {
        return Collections.unmodifiableMap(params);
    }
}
