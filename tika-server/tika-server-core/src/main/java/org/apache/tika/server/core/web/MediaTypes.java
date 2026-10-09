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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Media type lists as tika-server negotiates them: parsing of <code>Accept</code>-style
 * lists, quality and specificity ordering, and the intersection of what a client accepts
 * with what an endpoint consumes or produces.
 * <p>
 * Ported from the media type utilities of Apache CXF (JAXRSUtils), which tika-server used
 * before it moved to Spring MVC, so that clients see the same endpoint and response type
 * chosen for the same request.
 */
public final class MediaTypes {

    public static final String Q_PARAM = "q";
    public static final String QS_PARAM = "qs";
    private static final String DISTANCE_PARAM = "d";
    private static final String CHARSET_PARAM = "charset";

    private MediaTypes() {
    }

    /**
     * @param value a single media type, or null for any type
     * @return the parsed type
     */
    public static WebMediaType toMediaType(String value) {
        return value == null ? WebMediaType.WILDCARD_TYPE : WebMediaType.valueOf(value);
    }

    /**
     * @param types a comma-separated list, or null for any type
     * @return the parsed types, in their original order
     */
    public static List<WebMediaType> parseMediaTypes(String types) {
        List<WebMediaType> values = new ArrayList<>();
        if (types != null) {
            int x = 0;
            int y = types.indexOf(',');
            while (y > 0) {
                values.add(toMediaType(types.substring(x, y).trim()));
                x = y + 1;
                y = types.indexOf(',', x);
            }
            String last = types.substring(x).trim();
            if (!last.isEmpty()) {
                values.add(toMediaType(last));
            }
        } else {
            values.add(WebMediaType.WILDCARD_TYPE);
        }
        return values;
    }

    /**
     * @param values the media types of an annotation
     * @return all types those values list
     */
    public static List<WebMediaType> getMediaTypes(String[] values) {
        List<WebMediaType> types = new ArrayList<>(values.length);
        for (String value : values) {
            types.addAll(parseMediaTypes(value));
        }
        return types;
    }

    /**
     * Sorts an <code>Accept</code> list: concrete types before wildcards, then by quality.
     *
     * @param types   the list (sorted in place, stably)
     * @param qParam  the quality parameter to compare
     * @return the list
     */
    public static List<WebMediaType> sortMediaTypes(List<WebMediaType> types, String qParam) {
        if (types.size() > 1) {
            types.sort((a, b) -> compareMediaTypes(a, b, qParam));
        }
        return types;
    }

    public static int compareMediaTypes(WebMediaType mt1, WebMediaType mt2, String qParam) {
        boolean wild1 = mt1.isWildcardType();
        boolean wild2 = mt2.isWildcardType();
        if (wild1 && !wild2) {
            return 1;
        }
        if (!wild1 && wild2) {
            return -1;
        }
        boolean subWild1 = mt1.getSubtype().contains(WebMediaType.WILDCARD);
        boolean subWild2 = mt2.getSubtype().contains(WebMediaType.WILDCARD);
        if (subWild1 && !subWild2) {
            return 1;
        }
        if (!subWild1 && subWild2) {
            return -1;
        }
        return qParam != null ? compareQualityFactors(mt1, mt2, qParam) : 0;
    }

    public static int compareQualityFactors(WebMediaType mt1, WebMediaType mt2, String qParam) {
        float q1 = qualityFactor(mt1.getParameters().get(qParam));
        float q2 = qualityFactor(mt2.getParameters().get(qParam));
        return Float.compare(q1, q2) * -1;
    }

    /**
     * @param q the value of a quality parameter
     * @return its value; 1 when absent or not a number
     */
    public static float qualityFactor(String q) {
        if (q == null) {
            return 1;
        }
        if (q.charAt(0) == '.') {
            q = '0' + q;
        }
        try {
            return Float.parseFloat(q);
        } catch (NumberFormatException ex) {
            // a quality that is not a number counts as the default
            return 1;
        }
    }

    /**
     * @return true if any type of <code>required</code> intersects any type of <code>user</code>
     */
    public static boolean intersect(List<WebMediaType> required, List<WebMediaType> user) {
        for (WebMediaType requiredType : required) {
            for (WebMediaType userType : user) {
                if (isCompatible(requiredType, userType) && parametersMatch(requiredType, userType)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean intersect(List<WebMediaType> required, WebMediaType user) {
        return intersect(required, Collections.singletonList(user));
    }

    /**
     * Every concrete type both lists allow, in the order of <code>required</code>.
     *
     * @param required          the first list
     * @param user              the second list
     * @param addRequiredParams copy parameters of the first list's type that the second lacks
     * @param addDistance       record in a <code>d</code> parameter how many wildcards of the first list were filled
     * @return the intersection, without duplicates
     */
    public static List<WebMediaType> intersectMimeTypes(List<WebMediaType> required, List<WebMediaType> user, boolean addRequiredParams,
                                                        boolean addDistance) {
        Set<WebMediaType> result = new LinkedHashSet<>();
        for (WebMediaType requiredType : required) {
            for (WebMediaType userType : user) {
                if (!isCompatible(requiredType, userType) || !parametersMatch(requiredType, userType)) {
                    continue;
                }
                boolean typeWild = requiredType.getType().equals(WebMediaType.WILDCARD);
                boolean subtypeWild = requiredType.getSubtype().contains(WebMediaType.WILDCARD);
                String type = typeWild ? userType.getType() : requiredType.getType();
                String subtype = subtypeWild ? userType.getSubtype() : requiredType.getSubtype();
                Map<String, String> parameters = new LinkedHashMap<>(userType.getParameters());
                if (addRequiredParams) {
                    for (Map.Entry<String, String> e : requiredType.getParameters().entrySet()) {
                        if (!parameters.containsKey(e.getKey())) {
                            parameters.put(e.getKey(), e.getValue());
                        }
                    }
                }
                if (addDistance) {
                    int distance = (typeWild ? 1 : 0) + (subtypeWild ? 1 : 0);
                    parameters.put(DISTANCE_PARAM, Integer.toString(distance));
                }
                result.add(new WebMediaType(type, subtype, parameters));
            }
        }
        return new ArrayList<>(result);
    }

    public static List<WebMediaType> intersectMimeTypes(List<WebMediaType> required, List<WebMediaType> user, boolean addRequiredParams) {
        return intersectMimeTypes(required, user, addRequiredParams, false);
    }

    /**
     * The intersection, best first: concrete before wildcard, then quality, then (optionally) the
     * fewest wildcards filled.
     */
    public static List<WebMediaType> intersectSortMediaTypes(List<WebMediaType> acceptTypes, List<WebMediaType> producesTypes, boolean checkDistance) {
        List<WebMediaType> all = intersectMimeTypes(acceptTypes, producesTypes, true, checkDistance);
        if (all.size() > 1) {
            all.sort((mt1, mt2) -> {
                int result = compareMediaTypes(mt1, mt2, null);
                if (result == 0) {
                    result = compareQualityAndDistance(mt1, mt2, checkDistance);
                }
                return result;
            });
        }
        return all;
    }

    private static int compareQualityAndDistance(WebMediaType mt1, WebMediaType mt2, boolean checkDistance) {
        int result = compareQualityFactors(mt1, mt2, Q_PARAM);
        if (result == 0) {
            result = compareQualityFactors(mt1, mt2, QS_PARAM);
        }
        if (result == 0 && checkDistance) {
            Integer d1 = Integer.valueOf(mt1.getParameters().get(DISTANCE_PARAM));
            Integer d2 = Integer.valueOf(mt2.getParameters().get(DISTANCE_PARAM));
            result = d1.compareTo(d2);
        }
        return result;
    }

    public static int compareSortedConsumesMediaTypes(List<WebMediaType> mts1, List<WebMediaType> mts2, WebMediaType contentType) {
        return compareSortedMediaTypes(compatibleTypes(mts1, contentType), compatibleTypes(mts2, contentType), null);
    }

    public static int compareSortedAcceptMediaTypes(List<WebMediaType> mts1, List<WebMediaType> mts2, List<WebMediaType> acceptTypes) {
        List<WebMediaType> actual1 = intersectSortMediaTypes(mts1, acceptTypes, true);
        List<WebMediaType> actual2 = intersectSortMediaTypes(mts2, acceptTypes, true);
        int size1 = actual1.size();
        int size2 = actual2.size();
        for (int i = 0; i < size1 && i < size2; i++) {
            int result = compareMediaTypes(actual1.get(i), actual2.get(i), null);
            if (result == 0) {
                result = compareQualityAndDistance(actual1.get(i), actual2.get(i), true);
            }
            if (result != 0) {
                return result;
            }
        }
        return size1 == size2 ? 0 : size1 < size2 ? -1 : 1;
    }

    public static int compareSortedMediaTypes(List<WebMediaType> mts1, List<WebMediaType> mts2, String qParam) {
        int size1 = mts1.size();
        int size2 = mts2.size();
        for (int i = 0; i < size1 && i < size2; i++) {
            int result = compareMediaTypes(mts1.get(i), mts2.get(i), qParam);
            if (result != 0) {
                return result;
            }
        }
        return size1 == size2 ? 0 : size1 < size2 ? -1 : 1;
    }

    private static List<WebMediaType> compatibleTypes(List<WebMediaType> mts, WebMediaType ct) {
        if (mts.size() == 1) {
            return mts;
        }
        List<WebMediaType> actual = new ArrayList<>();
        for (WebMediaType mt : mts) {
            if (isCompatible(mt, ct)) {
                actual.add(mt);
            }
        }
        return actual;
    }

    private static boolean parametersMatch(WebMediaType requiredType, WebMediaType userType) {
        for (Map.Entry<String, String> entry : userType.getParameters().entrySet()) {
            String value = requiredType.getParameters().get(entry.getKey());
            if (value != null && entry.getValue() != null && !stripQuotes(value).equals(stripQuotes(entry.getValue()))) {
                if (CHARSET_PARAM.equals(entry.getKey()) && value.equalsIgnoreCase(entry.getValue())) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Wildcard compatibility, extended to structured suffixes: <code>application/*+xml</code>
     * matches <code>application/atom+xml</code>, and <code>application/vnd.a+*</code> matches
     * <code>application/vnd.a+json</code>.
     */
    public static boolean isCompatible(WebMediaType requiredType, WebMediaType userType) {
        boolean compatible = requiredType.isCompatible(userType);
        if (!compatible && requiredType.getType().equalsIgnoreCase(userType.getType())) {
            compatible = compareCompositeSubtypes(requiredType, userType);
        }
        return compatible;
    }

    private static boolean compareCompositeSubtypes(WebMediaType requiredType, WebMediaType userType) {
        String subType1 = requiredType.getSubtype();
        String subType2 = userType.getSubtype();
        String after1 = splitSubtype(subType1, true);
        String after2 = splitSubtype(subType2, true);
        if (after1 == null || after2 == null) {
            return false;
        }
        boolean compatible = after1.equalsIgnoreCase(after2) && (subType1.charAt(0) == '*' || subType2.charAt(0) == '*');
        if (!compatible) {
            String before1 = splitSubtype(subType1, false);
            String before2 = splitSubtype(subType2, false);
            compatible = before1 != null && before1.equalsIgnoreCase(before2) &&
                    (subType1.charAt(subType1.length() - 1) == '*' || subType2.charAt(subType2.length() - 1) == '*');
        }
        return compatible;
    }

    private static String splitSubtype(String type, boolean after) {
        int index = type.indexOf('+');
        return index == -1 ? null : after ? type.substring(index + 1) : type.substring(0, index);
    }
}
