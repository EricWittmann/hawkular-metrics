/*
 * Copyright 2014-2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.metrics.core.api;

/**
 * An enumeration of the supported metric types which currently includes,
 *
 * <ul>
 *   <li>guage</li>
 *   <li>availability</li>
 *   <li>log events</li>
 * </ul>
 *
 * @author John Sanda
 */
public enum MetricType {

    GAUGE(0, "gauge"),

    AVAILABILITY(1, "availability"),

    COUNTER(2, "counter"),

    COUNTER_RATE(3, "counter_rate");

    private int code;

    private String text;

    MetricType(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public int getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }

    public static MetricType fromCode(int code) {
        switch (code) {
            case 0 : return GAUGE;
            case 1 : return AVAILABILITY;
            case 2 : return COUNTER;
            case 3 : return COUNTER_RATE;
            default: throw new IllegalArgumentException(code + " is not a recognized metric type");
        }
    }

    public static MetricType fromTextCode(String textCode) {
        switch (textCode) {
            case "gauge": return GAUGE;
            case "availability": return AVAILABILITY;
            case "counter": return COUNTER;
            case "counter_rate": return COUNTER_RATE;
            default: throw new IllegalArgumentException(textCode + " is not a recognized metric type code");
        }
    }

}
