package com.amazon.situp.plugins.source;

import com.amazon.situp.model.configuration.PluginSetting;
import com.amazon.situp.model.source.Source;
import com.amazon.situp.plugins.PluginException;
import org.junit.Test;

import java.util.HashMap;

import static com.amazon.situp.plugins.PluginFactoryTests.NON_EXISTENT_EMPTY_CONFIGURATION;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

@SuppressWarnings("rawtypes")
public class SourceFactoryTests {
    private static String TEST_PIPELINE = "test-pipeline";
    /**
     * Tests if SourceFactory is able to retrieve default Source plugins by name
     */
    @Test
    public void testSourceClassByName() {
        final PluginSetting stdInSourceConfiguration = new PluginSetting("stdin", new HashMap<>());
        stdInSourceConfiguration.setPipelineName(TEST_PIPELINE);
        final Source actualSource = SourceFactory.newSource(stdInSourceConfiguration);
        final Source expectedSource = new StdInSource(500,TEST_PIPELINE);
        assertThat(actualSource, notNullValue());
        assertThat(actualSource.getClass().getSimpleName(), is(equalTo(expectedSource.getClass().getSimpleName())));
    }

    /**
     * Tests if SourceFactory fails with correct Exception when queried for a non-existent plugin
     */
    @Test
    public void testNonExistentSourceRetrieval() {
        assertThrows(PluginException.class, () -> SourceFactory.newSource(NON_EXISTENT_EMPTY_CONFIGURATION));
    }
}
