/*
 * Copyright 2014-2016 Red Hat, Inc. and/or its affiliates
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
package org.hawkular.metrics.core.service;

import static java.util.stream.Collectors.toMap;

import static org.hawkular.metrics.core.service.TimeUUIDUtils.getTimeUUID;
import static org.hawkular.metrics.model.MetricType.AVAILABILITY;
import static org.hawkular.metrics.model.MetricType.COUNTER;
import static org.hawkular.metrics.model.MetricType.GAUGE;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.hawkular.metrics.core.service.transformers.BatchStatementTransformer;
import org.hawkular.metrics.model.AvailabilityType;
import org.hawkular.metrics.model.DataPoint;
import org.hawkular.metrics.model.Interval;
import org.hawkular.metrics.model.Metric;
import org.hawkular.metrics.model.MetricId;
import org.hawkular.metrics.model.MetricType;
import org.hawkular.metrics.model.Tenant;
import org.hawkular.rx.cassandra.driver.RxSession;
import org.hawkular.rx.cassandra.driver.RxSessionImpl;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;

import rx.Observable;

/**
 *
 * @author John Sanda
 */
public class DataAccessImpl implements DataAccess {

    public static final long DPART = 0;
    private Session session;

    private RxSession rxSession;

    private PreparedStatement insertTenant;

    private PreparedStatement insertTenantId;

    private PreparedStatement findAllTenantIds;

    private PreparedStatement findAllTenantIdsFromMetricsIdx;

    private PreparedStatement findTenant;

    private PreparedStatement insertIntoMetricsIndex;

    private PreparedStatement findMetric;

    private PreparedStatement getMetricTags;

    private PreparedStatement addDataRetention;

    private PreparedStatement insertGaugeData;

    private PreparedStatement insertCounterData;

    private PreparedStatement findCounterDataExclusive;

    private PreparedStatement findCounterDataExclusiveWithLimit;

    private PreparedStatement findCounterDataExclusiveASC;

    private PreparedStatement findCounterDataExclusiveWithLimitASC;

    private PreparedStatement findGaugeDataByDateRangeExclusive;

    private PreparedStatement findGaugeDataByDateRangeExclusiveWithLimit;

    private PreparedStatement findGaugeDataByDateRangeExclusiveASC;

    private PreparedStatement findGaugeDataByDateRangeExclusiveWithLimitASC;

    private PreparedStatement findGaugeDataWithWriteTimeByDateRangeExclusive;

    private PreparedStatement findGaugeDataWithWriteTimeByDateRangeExclusiveASC;

    private PreparedStatement findGaugeDataWithWriteTimeByDateRangeExclusiveWithLimit;

    private PreparedStatement findGaugeDataWithWriteTimeByDateRangeExclusiveWithLimitASC;

    private PreparedStatement findGaugeDataByDateRangeInclusive;

    private PreparedStatement findGaugeDataWithWriteTimeByDateRangeInclusive;

    private PreparedStatement findAvailabilityByDateRangeInclusive;

    private PreparedStatement deleteGaugeMetric;

    private PreparedStatement insertAvailability;

    private PreparedStatement findAvailabilities;

    private PreparedStatement findAvailabilitiesWithLimit;

    private PreparedStatement findAvailabilitiesASC;

    private PreparedStatement findAvailabilitiesWithLimitASC;

    private PreparedStatement findAvailabilitiesWithWriteTime;

    private PreparedStatement findAvailabilitiesWithWriteTimeWithLimit;

    private PreparedStatement findAvailabilitiesWithWriteTimeASC;

    private PreparedStatement findAvailabilitiesWithWriteTimeWithLimitASC;

    private PreparedStatement updateMetricsIndex;

    private PreparedStatement addTagsToMetricsIndex;

    private PreparedStatement deleteTagsFromMetricsIndex;

    private PreparedStatement readMetricsIndex;

    private PreparedStatement updateRetentionsIndex;

    private PreparedStatement findDataRetentions;

    private PreparedStatement insertMetricsTagsIndex;

    private PreparedStatement deleteMetricsTagsIndex;

    private PreparedStatement findMetricsByTagName;

    private PreparedStatement findMetricsByTagNameValue;

    public DataAccessImpl(Session session) {
        this.session = session;
        rxSession = new RxSessionImpl(session);
        initPreparedStatements();
    }

    protected void initPreparedStatements() {
        insertTenantId = session.prepare("INSERT INTO tenants (id) VALUES (?)");

        insertTenant = session.prepare(
            "INSERT INTO tenants (id, retentions) VALUES (?, ?) IF NOT EXISTS");

        findAllTenantIds = session.prepare("SELECT DISTINCT id FROM tenants");

        findAllTenantIdsFromMetricsIdx = session.prepare("SELECT DISTINCT tenant_id, type FROM metrics_idx");

        findTenant = session.prepare("SELECT id, retentions FROM tenants WHERE id = ?");

        findMetric = session.prepare(
            "SELECT metric, tags, data_retention " +
            "FROM metrics_idx " +
            "WHERE tenant_id = ? AND type = ? AND metric = ?");

        getMetricTags = session.prepare(
            "SELECT tags " +
            "FROM metrics_idx " +
            "WHERE tenant_id = ? AND type = ? AND metric = ?");

        // TODO I am not sure if we want the data_retention columns in the data table
        // Everything else in a partition will have a TTL set on it, so I fear that these columns
        // might cause problems with compaction.
        addDataRetention = session.prepare(
            "UPDATE data " +
            "SET data_retention = ? " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ?");

        insertIntoMetricsIndex = session.prepare(
            "INSERT INTO metrics_idx (tenant_id, type, metric, data_retention, tags) " +
            "VALUES (?, ?, ?, ?, ?) " +
            "IF NOT EXISTS");

        updateMetricsIndex = session.prepare(
            "INSERT INTO metrics_idx (tenant_id, type, metric) VALUES (?, ?, ?)");

        addTagsToMetricsIndex = session.prepare(
            "UPDATE metrics_idx " +
            "SET tags = tags + ? " +
            "WHERE tenant_id = ? AND type = ? AND metric = ?");

        deleteTagsFromMetricsIndex = session.prepare(
            "UPDATE metrics_idx " +
            "SET tags = tags - ?" +
            "WHERE tenant_id = ? AND type = ? AND metric = ?");

        readMetricsIndex = session.prepare(
            "SELECT metric, tags, data_retention " +
            "FROM metrics_idx " +
            "WHERE tenant_id = ? AND type = ?");

        insertGaugeData = session.prepare(
            "UPDATE data " +
            "USING TTL ?" +
            "SET n_value = ? " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time = ? ");

        insertCounterData = session.prepare(
            "UPDATE data " +
            "USING TTL ?" +
            "SET l_value = ? " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time = ? ");

        findGaugeDataByDateRangeExclusive = session.prepare(
            "SELECT time, data_retention, n_value FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ?");

        findGaugeDataByDateRangeExclusiveWithLimit = session.prepare(
            "SELECT time, data_retention, n_value FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ?" +
            " LIMIT ?");

        findGaugeDataByDateRangeExclusiveASC = session.prepare(
            "SELECT time, data_retention, n_value FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ?" +
            " AND time < ? ORDER BY time ASC");

        findGaugeDataByDateRangeExclusiveWithLimitASC = session.prepare(
            "SELECT time, data_retention, n_value FROM data" +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ?" +
            " AND time < ? ORDER BY time ASC" +
            " LIMIT ?");

        findCounterDataExclusive = session.prepare(
            "SELECT time, data_retention, l_value FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ? ");

        findCounterDataExclusiveWithLimit = session.prepare(
            "SELECT time, data_retention, l_value FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ? " +
            " LIMIT ?");

        findCounterDataExclusiveASC = session.prepare(
            "SELECT time, data_retention, l_value FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ? " +
            "ORDER BY time ASC");

        findCounterDataExclusiveWithLimitASC = session.prepare(
            "SELECT time, data_retention, l_value FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ? " +
            " ORDER BY time ASC" +
            " LIMIT ?");

        findGaugeDataWithWriteTimeByDateRangeExclusive = session.prepare(
            "SELECT time, data_retention, n_value, WRITETIME(n_value) FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ?");

        findGaugeDataWithWriteTimeByDateRangeExclusiveASC = session.prepare(
            "SELECT time, data_retention, n_value, WRITETIME(n_value) FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ?" +
            " ORDER BY time ASC");

        findGaugeDataWithWriteTimeByDateRangeExclusiveWithLimit = session.prepare(
            "SELECT time, data_retention, n_value, WRITETIME(n_value) FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ?" +
            " LIMIT ?");

        findGaugeDataWithWriteTimeByDateRangeExclusiveWithLimitASC = session.prepare(
            "SELECT time, data_retention, n_value, WRITETIME(n_value) FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ?" +
            " ORDER BY time ASC" +
            " LIMIT ?");

        findGaugeDataByDateRangeInclusive = session.prepare(
            "SELECT tenant_id, metric, dpart, time, data_retention, n_value " +
            "FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time <= ?");

        findGaugeDataWithWriteTimeByDateRangeInclusive = session.prepare(
            "SELECT time, data_retention, n_value, WRITETIME(n_value) FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time <= ?");

        findAvailabilityByDateRangeInclusive = session.prepare(
            "SELECT time, data_retention, availability, WRITETIME(availability) FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time <= ?");

        deleteGaugeMetric = session.prepare(
            "DELETE FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ?");

        insertAvailability = session.prepare(
            "UPDATE data " +
            "USING TTL ? " +
            "SET availability = ? " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time = ?");

        findAvailabilities = session.prepare(
            "SELECT time, data_retention, availability " +
            " FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ? ");

        findAvailabilitiesWithLimit = session.prepare(
            "SELECT time, data_retention, availability " +
            " FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ? " +
            " LIMIT ?");

        findAvailabilitiesASC = session.prepare(
            "SELECT time, data_retention, availability " +
            " FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ? " +
            " ORDER BY time ASC");

        findAvailabilitiesWithLimitASC = session.prepare(
            "SELECT time, data_retention, availability " +
            " FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ? " +
            " ORDER BY time ASC" +
            " LIMIT ?");

        findAvailabilitiesWithWriteTime = session.prepare(
            "SELECT time, data_retention, availability, WRITETIME(availability) " +
            " FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ?");

        findAvailabilitiesWithWriteTimeWithLimit = session.prepare(
            "SELECT time, data_retention, availability, WRITETIME(availability) " +
            " FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ?" +
            " LIMIT ?");

        findAvailabilitiesWithWriteTimeASC = session.prepare(
            "SELECT time, data_retention, availability, WRITETIME(availability) " +
            " FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ?" +
            " ORDER BY time ASC");

        findAvailabilitiesWithWriteTimeWithLimitASC = session.prepare(
            "SELECT time, data_retention, availability, WRITETIME(availability) " +
            " FROM data " +
            " WHERE tenant_id = ? AND type = ? AND metric = ? AND dpart = ? AND time >= ? AND time < ?" +
            " ORDER BY time ASC" +
            " LIMIT ?");

        updateRetentionsIndex = session.prepare(
            "INSERT INTO retentions_idx (tenant_id, type, metric, retention) VALUES (?, ?, ?, ?)");

        findDataRetentions = session.prepare(
            "SELECT tenant_id, type, metric, retention " +
            "FROM retentions_idx " +
            "WHERE tenant_id = ? AND type = ?");

        insertMetricsTagsIndex = session.prepare(
            "INSERT INTO metrics_tags_idx (tenant_id, tname, tvalue, type, metric) VALUES (?, ?, ?, ?, ?)");

        deleteMetricsTagsIndex = session.prepare(
            "DELETE FROM metrics_tags_idx " +
            "WHERE tenant_id = ? AND tname = ? AND tvalue = ? AND type = ? AND metric = ?");

        findMetricsByTagName = session.prepare(
            "SELECT type, metric, tvalue " +
            "FROM metrics_tags_idx " +
            "WHERE tenant_id = ? AND tname = ?");

        findMetricsByTagNameValue = session.prepare(
                "SELECT tenant_id, type, metric " +
                "FROM metrics_tags_idx " +
                "WHERE tenant_id = ? AND tname = ? AND tvalue = ?");
    }

    @Override public Observable<ResultSet> insertTenant(String tenantId) {
        return rxSession.execute(insertTenantId.bind(tenantId));
    }

    @Override
    public Observable<ResultSet> insertTenant(Tenant tenant) {
        Map<String, Integer> retentions = tenant.getRetentionSettings().entrySet().stream()
                .collect(toMap(entry -> entry.getKey().getText(), Map.Entry::getValue));
        return rxSession.execute(insertTenant.bind(tenant.getId(), retentions));
    }

    @Override
    public Observable<ResultSet> findAllTenantIds() {
        return rxSession.execute(findAllTenantIds.bind())
                .concatWith(rxSession.execute(findAllTenantIdsFromMetricsIdx.bind()));
    }

    @Override
    public Observable<ResultSet> findTenant(String id) {
        return rxSession.execute(findTenant.bind(id));
    }

    @Override
    public <T> ResultSetFuture insertMetricInMetricsIndex(Metric<T> metric) {
        MetricId<T> metricId = metric.getMetricId();
        return session.executeAsync(insertIntoMetricsIndex.bind(metricId.getTenantId(), metricId.getType().getCode(),
                metricId.getName(), metric.getDataRetention(), metric.getTags()));
    }

    @Override
    public <T> Observable<ResultSet> findMetric(MetricId<T> id) {
        return rxSession.execute(findMetric.bind(id.getTenantId(), id.getType().getCode(), id.getName()));
    }

    @Override
    public <T> Observable<ResultSet> getMetricTags(MetricId<T> id) {
        return rxSession.execute(getMetricTags.bind(id.getTenantId(), id.getType().getCode(), id.getName()));
    }

    // This method updates the metric tags and data retention in the data table. In the
    // long term after we add support for bucketing/date partitioning I am not sure that we
    // will store metric tags and data retention in the data table. We would have to
    // determine when we start writing data to a new partition, e.g., the start of the next
    // day, and then add the tags and retention to the new partition.
    @Override
    public <T> Observable<ResultSet> addDataRetention(Metric<T> metric) {
        MetricId<T> metricId = metric.getMetricId();
        return rxSession.execute(addDataRetention.bind(metric.getDataRetention(),
                metricId.getTenantId(), metricId.getType().getCode(), metricId.getName(), DPART));
    }

    @Override
    public <T> Observable<ResultSet> addTags(Metric<T> metric, Map<String, String> tags) {
        MetricId<T> metricId = metric.getMetricId();
        BoundStatement stmt = addTagsToMetricsIndex.bind(tags, metricId.getTenantId(), metricId.getType().getCode(),
                metricId.getName());
        return rxSession.execute(stmt);
    }

    @Override
    public <T> Observable<ResultSet> deleteTags(Metric<T> metric, Set<String> tags) {
        MetricId<T> metricId = metric.getMetricId();
        BoundStatement stmt = deleteTagsFromMetricsIndex.bind(tags, metricId.getTenantId(),
                metricId.getType().getCode(), metricId.getName());
        return rxSession.execute(stmt);
    }

    @Override
    public <T> Observable<Integer> updateMetricsIndex(Observable<Metric<T>> metrics) {
        return metrics.map(Metric::getMetricId)
                .map(id -> updateMetricsIndex.bind(id.getTenantId(), id.getType().getCode(), id.getName()))
                .compose(new BatchStatementTransformer())
                .flatMap(batch -> rxSession.execute(batch).map(resultSet -> batch.size()));
    }

    @Override
    public <T> Observable<ResultSet> findMetricsInMetricsIndex(String tenantId, MetricType<T> type) {
        return rxSession.execute(readMetricsIndex.bind(tenantId, type.getCode()));
    }

    @Override
    public Observable<Integer> insertGaugeData(Metric<Double> gauge, int ttl) {
        return Observable.from(gauge.getDataPoints())
                .map(dataPoint -> bindDataPoint(insertGaugeData, gauge, dataPoint.getValue(),
                        dataPoint.getTimestamp(), ttl))
                .compose(new BatchStatementTransformer())
                .flatMap(batch -> rxSession.execute(batch).map(resultSet -> batch.size()));
    }

    @Override
    public Observable<Integer> insertCounterData(Metric<Long> counter, int ttl) {
        return Observable.from(counter.getDataPoints())
                .map(dataPoint -> bindDataPoint(insertCounterData, counter, dataPoint.getValue(),
                        dataPoint.getTimestamp(), ttl))
                .compose(new BatchStatementTransformer())
                .flatMap(batch -> rxSession.execute(batch).map(resultSet -> batch.size()));
    }

    private BoundStatement bindDataPoint(
            PreparedStatement statement, Metric<?> metric, Object value, long timestamp, int ttl
    ) {
        MetricId<?> metricId = metric.getMetricId();
        return statement.bind(ttl, value, metricId.getTenantId(), metricId.getType().getCode(), metricId.getName(),
                DPART, getTimeUUID(timestamp));
    }


    @Override
    public Observable<ResultSet> findCounterData(MetricId<Long> id, long startTime, long endTime, int limit,
            Order order) {
        if (order == Order.ASC) {
            if (limit <= 0) {
                return rxSession
                        .execute(findCounterDataExclusiveASC.bind(id.getTenantId(), COUNTER.getCode(), id.getName(),
                                DPART, getTimeUUID(startTime), getTimeUUID(endTime)));
            } else {
                return rxSession
                        .execute(findCounterDataExclusiveWithLimitASC.bind(id.getTenantId(), COUNTER.getCode(),
                                id.getName(), DPART, getTimeUUID(startTime), getTimeUUID(endTime), limit));
            }
        } else {
            if (limit <= 0) {
                return rxSession
                        .execute(findCounterDataExclusive.bind(id.getTenantId(), COUNTER.getCode(), id.getName(),
                                DPART, getTimeUUID(startTime), getTimeUUID(endTime)));
            } else {
                return rxSession
                        .execute(findCounterDataExclusiveWithLimit.bind(id.getTenantId(), COUNTER.getCode(),
                                id.getName(), DPART, getTimeUUID(startTime), getTimeUUID(endTime), limit));
            }
        }
    }

    @Override
    public Observable<ResultSet> findGaugeData(MetricId<Double> id, long startTime, long endTime, int limit,
            Order order, boolean includeWriteTime) {
        if (includeWriteTime) {
            if (order == Order.ASC) {
                if (limit <= 0) {
                    return rxSession.execute(findGaugeDataWithWriteTimeByDateRangeExclusiveASC.bind(id.getTenantId(),
                            id.getType().getCode(), id.getName(), DPART, getTimeUUID(startTime),
                            getTimeUUID(endTime)));
                } else {
                    return rxSession.execute(findGaugeDataWithWriteTimeByDateRangeExclusiveWithLimitASC.bind(
                            id.getTenantId(), id.getType().getCode(), id.getName(), DPART, getTimeUUID(startTime),
                            getTimeUUID(endTime), limit));
                }
            } else {
                if (limit <= 0) {
                    return rxSession.execute(findGaugeDataWithWriteTimeByDateRangeExclusive.bind(id.getTenantId(),
                            id.getType().getCode(), id.getName(), DPART, getTimeUUID(startTime),
                            getTimeUUID(endTime)));
                } else {
                    return rxSession.execute(findGaugeDataWithWriteTimeByDateRangeExclusiveWithLimit.bind(
                            id.getTenantId(), id.getType().getCode(), id.getName(), DPART, getTimeUUID(startTime),
                            getTimeUUID(endTime), limit));
                }
            }
        } else {
            if (order == Order.ASC) {
                if (limit <= 0) {
                    return rxSession.execute(findGaugeDataByDateRangeExclusiveASC.bind(id.getTenantId(),
                            GAUGE.getCode(), id.getName(), DPART, getTimeUUID(startTime), getTimeUUID(endTime)));
                } else {
                    return rxSession.execute(findGaugeDataByDateRangeExclusiveWithLimitASC.bind(id.getTenantId(),
                            GAUGE.getCode(), id.getName(), DPART, getTimeUUID(startTime), getTimeUUID(endTime),
                            limit));
                }
            } else {
                if (limit <= 0) {
                    return rxSession.execute(findGaugeDataByDateRangeExclusive.bind(id.getTenantId(),
                        GAUGE.getCode(), id.getName(), DPART, getTimeUUID(startTime), getTimeUUID(endTime)));
                } else {
                    return rxSession.execute(findGaugeDataByDateRangeExclusiveWithLimit.bind(id.getTenantId(),
                            GAUGE.getCode(), id.getName(), DPART, getTimeUUID(startTime), getTimeUUID(endTime),
                            limit));
                }
            }
        }
    }

    @Override
    public Observable<ResultSet> findGaugeData(MetricId<Double> metricId, long timestamp, boolean includeWriteTime) {
        if (includeWriteTime) {
            return rxSession.execute(findGaugeDataWithWriteTimeByDateRangeInclusive.bind(metricId.getTenantId(),
                    metricId.getType().getCode(), metricId.getName(), DPART, UUIDs.startOf(timestamp),
                    UUIDs.endOf(timestamp)));
        } else {
            return rxSession.execute(findGaugeDataByDateRangeInclusive.bind(metricId.getTenantId(),
                    metricId.getType().getCode(), metricId.getName(), DPART, UUIDs.startOf(timestamp),
                    UUIDs.endOf(timestamp)));
        }
    }

    @Override
    public Observable<ResultSet> findAvailabilityData(MetricId<AvailabilityType> id, long startTime, long endTime,
            int limit, Order order, boolean includeWriteTime) {
        if (includeWriteTime) {
            if (order == Order.ASC) {
                if (limit <= 0) {
                    return rxSession.execute(findAvailabilitiesWithWriteTimeASC.bind(id.getTenantId(),
                            AVAILABILITY.getCode(), id.getName(), DPART, getTimeUUID(startTime),
                            getTimeUUID(endTime)));
                } else {
                    return rxSession.execute(findAvailabilitiesWithWriteTimeWithLimitASC.bind(id.getTenantId(),
                            AVAILABILITY.getCode(), id.getName(), DPART, getTimeUUID(startTime),
                            getTimeUUID(endTime), limit));
                }
            } else {
                if (limit <= 0) {
                    return rxSession.execute(findAvailabilitiesWithWriteTime.bind(id.getTenantId(),
                            AVAILABILITY.getCode(), id.getName(), DPART, getTimeUUID(startTime),
                            getTimeUUID(endTime)));
                } else {
                    return rxSession.execute(findAvailabilitiesWithWriteTimeWithLimit.bind(id.getTenantId(),
                            AVAILABILITY.getCode(), id.getName(), DPART, getTimeUUID(startTime),
                            getTimeUUID(endTime), limit));
                }
            }
        } else {
            if (order == Order.ASC) {
                if (limit <= 0) {
                    return rxSession.execute(findAvailabilitiesASC.bind(id.getTenantId(), AVAILABILITY.getCode(),
                            id.getName(), DPART, getTimeUUID(startTime), getTimeUUID(endTime)));
                } else {
                    return rxSession
                            .execute(findAvailabilitiesWithLimitASC.bind(id.getTenantId(), AVAILABILITY.getCode(),
                                    id.getName(), DPART, getTimeUUID(startTime), getTimeUUID(endTime), limit));
                }
            } else {
                if (limit <= 0) {
                    return rxSession.execute(findAvailabilities.bind(id.getTenantId(), AVAILABILITY.getCode(),
                    id.getName(), DPART, getTimeUUID(startTime), getTimeUUID(endTime)));
                } else {
                    return rxSession.execute(findAvailabilitiesWithLimit.bind(id.getTenantId(), AVAILABILITY.getCode(),
                            id.getName(), DPART, getTimeUUID(startTime), getTimeUUID(endTime), limit));
                }
            }
        }
    }

    @Override
    public Observable<ResultSet> findAvailabilityData(MetricId<AvailabilityType> id, long timestamp) {
        return rxSession.execute(findAvailabilityByDateRangeInclusive.bind(id.getTenantId(),
                AVAILABILITY.getCode(), id.getName(), DPART, UUIDs.startOf(timestamp), UUIDs.endOf(timestamp)));
    }

    @Override
    public Observable<ResultSet> deleteGaugeMetric(String tenantId, String metric, Interval interval, long dpart) {
        return rxSession.execute(deleteGaugeMetric.bind(tenantId, GAUGE.getCode(), metric,
                interval.toString(), dpart));
    }

    @Override
    public Observable<Integer> insertAvailabilityData(Metric<AvailabilityType> metric, int ttl) {
        return Observable.from(metric.getDataPoints())
                .map(dataPoint -> bindDataPoint(insertAvailability, metric, getBytes(dataPoint),
                        dataPoint.getTimestamp(), ttl))
                .compose(new BatchStatementTransformer())
                .flatMap(batch -> rxSession.execute(batch).map(resultSet -> batch.size()));
    }

    private ByteBuffer getBytes(DataPoint<AvailabilityType> dataPoint) {
        return ByteBuffer.wrap(new byte[]{dataPoint.getValue().getCode()});
    }

    @Override
    public <T> ResultSetFuture findDataRetentions(String tenantId, MetricType<T> type) {
        return session.executeAsync(findDataRetentions.bind(tenantId, type.getCode()));
    }

    @Override
    public <T> Observable<ResultSet> updateRetentionsIndex(String tenantId, MetricType<T> type,
                                                       Map<String, Integer> retentions) {
        return Observable.from(retentions.entrySet())
                .map(entry -> updateRetentionsIndex.bind(tenantId, type.getCode(), entry.getKey(), entry.getValue()))
                .compose(new BatchStatementTransformer())
                .flatMap(rxSession::execute);
    }

    @Override
    public <T> Observable<ResultSet> insertIntoMetricsTagsIndex(Metric<T> metric, Map<String, String> tags) {
        MetricId<T> metricId = metric.getMetricId();
        return tagsUpdates(tags, (name, value) -> insertMetricsTagsIndex.bind(metricId.getTenantId(), name, value,
                metricId.getType().getCode(), metricId.getName()));
    }

    @Override
    public <T> Observable<ResultSet> deleteFromMetricsTagsIndex(Metric<T> metric, Map<String, String> tags) {
        MetricId<T> metricId = metric.getMetricId();
        return tagsUpdates(tags, (name, value) -> deleteMetricsTagsIndex.bind(metricId.getTenantId(), name, value,
                metricId.getType().getCode(), metricId.getName()));
    }

    private Observable<ResultSet> tagsUpdates(Map<String, String> tags,
                                              BiFunction<String, String, BoundStatement> bindVars) {
        return Observable.from(tags.entrySet())
                .map(entry -> bindVars.apply(entry.getKey(), entry.getValue()))
                .flatMap(rxSession::execute);
    }

    @Override
    public Observable<ResultSet> findMetricsByTagName(String tenantId, String tag) {
        return rxSession.execute(findMetricsByTagName.bind(tenantId, tag));
    }

    @Override
    public Observable<ResultSet> findMetricsByTagNameValue(String tenantId, String tag, String tvalue) {
        return rxSession.execute(findMetricsByTagNameValue.bind(tenantId, tag, tvalue));
    }

    @Override
    public <T> ResultSetFuture updateRetentionsIndex(Metric<T> metric) {
        return session.executeAsync(updateRetentionsIndex.bind(metric.getMetricId().getTenantId(),
                metric.getMetricId().getType().getCode(), metric.getMetricId().getName(), metric.getDataRetention()));
    }
}
