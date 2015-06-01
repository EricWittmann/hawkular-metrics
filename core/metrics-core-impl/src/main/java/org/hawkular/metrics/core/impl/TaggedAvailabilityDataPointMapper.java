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
package org.hawkular.metrics.core.impl;

import static org.hawkular.metrics.core.api.MetricType.AVAILABILITY;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.utils.UUIDs;
import org.hawkular.metrics.core.api.AvailabilityDataPoint;
import org.hawkular.metrics.core.api.AvailabilityType;
import org.hawkular.metrics.core.api.Interval;
import org.hawkular.metrics.core.api.Metric;
import org.hawkular.metrics.core.api.MetricId;

/**
 * @author jsanda
 */
public class TaggedAvailabilityDataPointMapper {

    public static Map<MetricId, Set<AvailabilityDataPoint>> apply(ResultSet resultSet) {
        Map<MetricId, Set<AvailabilityDataPoint>> taggedData = new HashMap<>();
        Metric<AvailabilityDataPoint> metric = null;
        LinkedHashSet<AvailabilityDataPoint> set = new LinkedHashSet<>();
        for (Row row : resultSet) {
            if (metric == null) {
                metric = createMetric(row);
                set.add(createAvailability(row));
            } else {
                Metric<AvailabilityDataPoint> nextMetric = createMetric(row);
                if (metric.equals(nextMetric)) {
                    set.add(createAvailability(row));
                } else {
                    taggedData.put(metric.getId(), set);
                    metric = nextMetric;
                    set = new LinkedHashSet<>();
                    set.add(createAvailability(row));
                }
            }
        }
        if (!(metric == null || set.isEmpty())) {
            taggedData.put(metric.getId(), set);
        }
        return taggedData;
    }

    private static Metric<AvailabilityDataPoint> createMetric(Row row) {
        return new MetricImpl<>(row.getString(0), AVAILABILITY, new MetricId(row.getString(4),
                Interval.parse(row.getString(5))));
    }

    private static AvailabilityDataPoint createAvailability(Row row) {
        return new AvailabilityDataPoint(UUIDs.unixTimestamp(row.getUUID(6)), AvailabilityType.fromBytes(
                row.getBytes(7)));
    }

}
