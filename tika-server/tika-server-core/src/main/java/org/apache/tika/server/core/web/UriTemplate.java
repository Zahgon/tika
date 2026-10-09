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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A path template of a tika-server endpoint, such as <code>/rmeta/{handler : (\w+)?}</code>.
 * <p>
 * Literal text matches itself, <code>{name}</code> matches one path segment and
 * <code>{name : regex}</code> matches the regular expression. Every template also matches
 * any further path below it; that remainder (or "/") is the template's final match group.
 * Templates are ordered by their number of literal characters, then of variables, then of
 * regular-expression variables, as the endpoint selection of tika-server always has
 * (ported from Apache CXF's URITemplate).
 */
public final class UriTemplate {

    public static final String FINAL_MATCH_GROUP = "FINAL_MATCH_GROUP";

    private static final String LIMITED_REGEX_SUFFIX = "(/.*)?";
    private static final String DEFAULT_PATH_VARIABLE_REGEX = "([^/]+?)";
    private static final String CHARACTERS_TO_ESCAPE = ".*+$()";
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("(\\w[-\\w\\.]*[ ]*)(\\:(.+))?");

    private final String template;
    private final List<String> variables = new ArrayList<>();
    private final List<String> customVariables = new ArrayList<>();
    private final Pattern templateRegexPattern;
    private final String literals;

    private UriTemplate(String theTemplate) {
        template = theTemplate;
        StringBuilder literalChars = new StringBuilder();
        StringBuilder patternBuilder = new StringBuilder();
        for (String part : tokenize(template)) {
            String name = null;
            String pattern = null;
            boolean variable = false;
            if (part.charAt(0) == '{' && part.charAt(part.length() - 1) == '}') {
                Matcher matcher = VARIABLE_PATTERN.matcher(part.substring(1, part.length() - 1).trim());
                if (matcher.matches()) {
                    variable = true;
                    name = matcher.group(1).trim();
                    if (matcher.group(2) != null && matcher.group(3) != null) {
                        pattern = Pattern.compile(matcher.group(3).trim()).pattern();
                    }
                }
            }
            if (!variable) {
                String escaped = escapeCharacters(part);
                literalChars.append(escaped);
                patternBuilder.append(escaped);
            } else {
                variables.add(name);
                if (pattern != null) {
                    customVariables.add(name);
                    if (pattern.startsWith("(") && pattern.endsWith(")") && !pattern.startsWith("(?")) {
                        patternBuilder.append(pattern);
                    } else {
                        patternBuilder.append('(').append(pattern).append(')');
                    }
                } else {
                    patternBuilder.append(DEFAULT_PATH_VARIABLE_REGEX);
                }
            }
        }
        literals = literalChars.toString();
        int endPos = patternBuilder.length() - 1;
        if (endPos >= 0 && patternBuilder.charAt(endPos) == '/') {
            patternBuilder.deleteCharAt(endPos);
        }
        patternBuilder.append(LIMITED_REGEX_SUFFIX);
        templateRegexPattern = Pattern.compile(patternBuilder.toString());
    }

    /**
     * @param path a path annotation value; null means "/" and a missing leading slash is added
     * @return the template
     */
    public static UriTemplate create(String path) {
        if (path == null) {
            path = "/";
        } else if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return new UriTemplate(path);
    }

    private static List<String> tokenize(String string) {
        List<String> tokens = new ArrayList<>();
        boolean outside = true;
        int level = 0;
        int lastIdx = 0;
        int idx;
        for (idx = 0; idx < string.length(); idx++) {
            char c = string.charAt(idx);
            if (c == '{') {
                if (outside) {
                    if (lastIdx < idx) {
                        tokens.add(string.substring(lastIdx, idx));
                    }
                    lastIdx = idx;
                    outside = false;
                } else {
                    level++;
                }
            } else if (c == '}' && !outside) {
                if (level > 0) {
                    level--;
                } else {
                    if (lastIdx < idx) {
                        tokens.add(string.substring(lastIdx, idx + 1));
                    }
                    lastIdx = idx + 1;
                    outside = true;
                }
            }
        }
        if (lastIdx < idx) {
            tokens.add(string.substring(lastIdx, idx));
        }
        return tokens;
    }

    private static String escapeCharacters(String expression) {
        StringBuilder sb = new StringBuilder(expression.length() + 8);
        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if (CHARACTERS_TO_ESCAPE.indexOf(ch) != -1) {
                sb.append('\\');
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * Ordering of templates: more literal characters first, then more variables, then more
     * regular-expression variables, then by pattern text.
     */
    public static int compareTemplates(UriTemplate t1, UriTemplate t2) {
        int l1 = t1.literals.length();
        int l2 = t2.literals.length();
        int result = Integer.compare(l2, l1);
        if (result == 0) {
            result = Integer.compare(t2.variables.size(), t1.variables.size());
            if (result == 0) {
                result = Integer.compare(t2.customVariables.size(), t1.customVariables.size());
                if (result == 0) {
                    result = t1.getPatternValue().compareTo(t2.getPatternValue());
                }
            }
        }
        return result;
    }

    /**
     * Matches a path and records the variables' values and the final match group.
     *
     * @param uri    the (encoded) path to match
     * @param values receives the variable values (by name, in order) and {@link #FINAL_MATCH_GROUP}
     * @return true on a match
     */
    public boolean match(String uri, Map<String, List<String>> values) {
        if (uri == null) {
            return false;
        }
        Matcher m = templateRegexPattern.matcher(uri);
        if (!m.matches() || template.equals("/") && uri.startsWith("/;")) {
            return false;
        }
        int groupCount = m.groupCount();
        int i = 1;
        for (String name : variables) {
            while (i <= groupCount) {
                String value = m.group(i++);
                if ((value == null || value.isEmpty() && i < groupCount) && variables.size() + 1 < groupCount) {
                    continue;
                }
                values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
                break;
            }
        }
        String finalGroup = i > groupCount ? "/" : m.group(groupCount);
        if (finalGroup == null || finalGroup.startsWith("/;")) {
            finalGroup = "/";
        }
        values.put(FINAL_MATCH_GROUP, new ArrayList<>(Collections.singletonList(finalGroup)));
        return true;
    }

    /**
     * @return the template as written
     */
    public String getValue() {
        return template;
    }

    public String getPatternValue() {
        return templateRegexPattern.toString();
    }

    public List<String> getVariables() {
        return Collections.unmodifiableList(variables);
    }
}
