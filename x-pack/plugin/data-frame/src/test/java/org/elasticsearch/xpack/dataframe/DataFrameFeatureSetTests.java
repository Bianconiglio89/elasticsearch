/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.dataframe;

import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.XPackFeatureSet;
import org.elasticsearch.xpack.core.XPackFeatureSet.Usage;
import org.elasticsearch.xpack.core.dataframe.DataFrameFeatureSetUsage;
import org.elasticsearch.xpack.core.dataframe.transforms.DataFrameIndexerTransformStats;
import org.elasticsearch.xpack.core.dataframe.transforms.DataFrameTransformConfig;
import org.elasticsearch.xpack.core.dataframe.transforms.DataFrameTransformConfigTests;
import org.elasticsearch.xpack.core.dataframe.transforms.DataFrameTransformStateAndStats;
import org.elasticsearch.xpack.core.dataframe.transforms.DataFrameTransformStateAndStatsTests;
import org.elasticsearch.xpack.core.indexing.IndexerState;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static java.lang.Math.toIntExact;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DataFrameFeatureSetTests extends ESTestCase {
    private XPackLicenseState licenseState;

    @Before
    public void init() {
        licenseState = mock(XPackLicenseState.class);
    }

    public void testAvailable() {
        DataFrameFeatureSet featureSet = new DataFrameFeatureSet(Settings.EMPTY, mock(Client.class), licenseState);
        boolean available = randomBoolean();
        when(licenseState.isDataFrameAllowed()).thenReturn(available);
        assertThat(featureSet.available(), is(available));
    }

    public void testEnabledSetting() {
        boolean enabled = randomBoolean();
        Settings.Builder settings = Settings.builder();
        settings.put("xpack.data_frame.enabled", enabled);
        DataFrameFeatureSet featureSet = new DataFrameFeatureSet(settings.build(), mock(Client.class), licenseState);
        assertThat(featureSet.enabled(), is(enabled));
    }

    public void testEnabledDefault() {
        DataFrameFeatureSet featureSet = new DataFrameFeatureSet(Settings.EMPTY, mock(Client.class), licenseState);
        assertTrue(featureSet.enabled());
    }

    public void testUsage() throws IOException {
        List<DataFrameTransformStateAndStats> transformsStateAndStats = new ArrayList<>();
        int count = randomIntBetween(0, 10);
        int uniqueId = 0;
        for (int i = 0; i < count; ++i) {
            transformsStateAndStats.add(
                    DataFrameTransformStateAndStatsTests.randomDataFrameTransformStateAndStats("df-" + Integer.toString(uniqueId++)));
        }

        count = randomIntBetween(0, 10);
        List<DataFrameTransformConfig> transformConfigWithoutTasks = new ArrayList<>();
        for (int i = 0; i < count; ++i) {
            transformConfigWithoutTasks.add(
                    DataFrameTransformConfigTests.randomDataFrameTransformConfig("df-" + Integer.toString(uniqueId++)));
        }

        List<DataFrameTransformConfig> transformConfigWithTasks = new ArrayList<>(transformsStateAndStats.size());
        transformsStateAndStats.forEach(stats ->
            transformConfigWithTasks.add(DataFrameTransformConfigTests.randomDataFrameTransformConfig(stats.getId())));

        List<DataFrameTransformConfig> allConfigs = new ArrayList<>(transformConfigWithoutTasks.size() + transformConfigWithTasks.size());
        allConfigs.addAll(transformConfigWithoutTasks);
        allConfigs.addAll(transformConfigWithTasks);

        boolean enabled = randomBoolean();
        boolean available = randomBoolean();
        DataFrameFeatureSetUsage usage = DataFrameFeatureSet.createUsage(available, enabled, allConfigs, transformsStateAndStats);

        assertEquals(enabled, usage.enabled());
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            usage.toXContent(builder, ToXContent.EMPTY_PARAMS);

            XContentParser parser = createParser(builder);
            Map<String, Object> usageAsMap = parser.map();
            assertEquals(available, (boolean) XContentMapValues.extractValue("available", usageAsMap));

            if (transformsStateAndStats.isEmpty() && transformConfigWithoutTasks.isEmpty()) {
                // no transforms, no stats
                assertEquals(null, XContentMapValues.extractValue("transforms", usageAsMap));
                assertEquals(null, XContentMapValues.extractValue("stats", usageAsMap));
            } else {
                assertEquals(transformsStateAndStats.size() + transformConfigWithoutTasks.size(),
                    XContentMapValues.extractValue("transforms._all", usageAsMap));

                Map<String, Integer> stateCounts = new HashMap<>();
                transformsStateAndStats.stream().map(x -> x.getTransformState().getIndexerState().value())
                        .forEach(x -> stateCounts.merge(x, 1, Integer::sum));
                transformConfigWithoutTasks.forEach(ignored -> stateCounts.merge(IndexerState.STOPPED.value(), 1, Integer::sum));
                stateCounts.forEach((k, v) -> assertEquals(v, XContentMapValues.extractValue("transforms." + k, usageAsMap)));

                // use default constructed stats object for assertions if transformsStateAndStats is empty
                DataFrameIndexerTransformStats combinedStats = new DataFrameIndexerTransformStats();
                if (transformsStateAndStats.isEmpty() == false) {
                    combinedStats = transformsStateAndStats.stream().map(x -> x.getTransformStats()).reduce((l, r) -> l.merge(r)).get();
                }

                assertEquals(toIntExact(combinedStats.getIndexFailures()),
                        XContentMapValues.extractValue("stats.index_failures", usageAsMap));
                assertEquals(toIntExact(combinedStats.getIndexTotal()),
                        XContentMapValues.extractValue("stats.index_total", usageAsMap));
                assertEquals(toIntExact(combinedStats.getSearchTime()),
                        XContentMapValues.extractValue("stats.search_time_in_ms", usageAsMap));
                assertEquals(toIntExact(combinedStats.getNumDocuments()),
                        XContentMapValues.extractValue("stats.documents_processed", usageAsMap));
            }
        }
    }

    public void testUsageDisabled() throws IOException, InterruptedException, ExecutionException {
        when(licenseState.isDataFrameAllowed()).thenReturn(true);
        Settings.Builder settings = Settings.builder();
        settings.put("xpack.data_frame.enabled", false);
        DataFrameFeatureSet featureSet = new DataFrameFeatureSet(settings.build(), mock(Client.class), licenseState);
        PlainActionFuture<Usage> future = new PlainActionFuture<>();
        featureSet.usage(future);
        XPackFeatureSet.Usage usage = future.get();

        assertFalse(usage.enabled());
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            usage.toXContent(builder, ToXContent.EMPTY_PARAMS);

            XContentParser parser = createParser(builder);
            Map<String, Object> usageAsMap = parser.map();
            assertTrue((boolean) XContentMapValues.extractValue("available", usageAsMap));
            assertFalse((boolean) XContentMapValues.extractValue("enabled", usageAsMap));
            // not enabled -> no transforms, no stats
            assertEquals(null, XContentMapValues.extractValue("transforms", usageAsMap));
            assertEquals(null, XContentMapValues.extractValue("stats", usageAsMap));
        }
    }
}
