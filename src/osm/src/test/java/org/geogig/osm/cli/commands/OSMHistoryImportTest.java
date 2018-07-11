/* Copyright (c) 2013-2016 Boundless and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/edl-v10.html
 *
 * Contributors:
 * Victor Olaya (Boundless) - initial implementation
 */
package org.geogig.osm.cli.commands;

import java.io.File;
import java.util.List;

import org.geogig.osm.internal.history.HistoryDownloader;
import org.geotools.referencing.CRS;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.locationtech.geogig.cli.CLIContextBuilder;
import org.locationtech.geogig.cli.Console;
import org.locationtech.geogig.cli.GeogigCLI;
import org.locationtech.geogig.model.DiffEntry;
import org.locationtech.geogig.model.DiffEntry.ChangeType;
import org.locationtech.geogig.model.RevFeature;
import org.locationtech.geogig.model.RevFeatureType;
import org.locationtech.geogig.plumbing.RevObjectParse;
import org.locationtech.geogig.porcelain.DiffOp;
import org.locationtech.geogig.repository.impl.GeoGIG;
import org.locationtech.geogig.repository.impl.GlobalContextBuilder;
import org.locationtech.geogig.test.TestPlatform;
import org.opengis.feature.type.FeatureType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

/**
 *
 */
public class OSMHistoryImportTest extends Assert {

    private GeogigCLI cli;

    private String fakeOsmApiUrl;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    public static @BeforeClass void beforeClass() {
        GlobalContextBuilder.builder(new CLIContextBuilder());
        HistoryDownloader.alwaysResolveRemoteDownloader = true;
    }

    public static @AfterClass void afterClass() {
        HistoryDownloader.alwaysResolveRemoteDownloader = false;
    }

    @Before
    public void setUp() throws Exception {
        Console consoleReader = new Console().disableAnsi();
        cli = new GeogigCLI(consoleReader).disableProgressListener();
        fakeOsmApiUrl = getClass().getResource("../../internal/history/01_10").toExternalForm();

        File workingDirectory = tempFolder.getRoot();
        TestPlatform platform = new TestPlatform(workingDirectory);
        cli.setPlatform(platform);
        cli.execute("init");
        assertTrue(new File(workingDirectory, ".geogig").exists());
    }

    @After
    public void tearDown() {
        if (cli != null) {
            cli.close();
        }
    }

    @Test
    public void test() throws Exception {
        cli.execute("config", "user.name", "Gabriel Roldan");
        cli.execute("config", "user.email", "groldan@boundlessgeo.com");
        cli.execute("osm", "import-history", fakeOsmApiUrl, "--to", "10");

        GeoGIG geogig = cli.getGeogig();
        List<DiffEntry> changes = ImmutableList.copyOf(geogig.command(DiffOp.class)
                .setOldVersion("HEAD~2").setNewVersion("HEAD~1").call());
        assertEquals(1, changes.size());
        DiffEntry entry = changes.get(0);
        assertEquals(ChangeType.MODIFIED, entry.changeType());
        assertEquals("node/20", entry.getOldObject().path());
        assertEquals("node/20", entry.getNewObject().path());

        Optional<RevFeature> oldRevFeature = geogig.command(RevObjectParse.class)
                .setObjectId(entry.getOldObject().getObjectId()).call(RevFeature.class);
        Optional<RevFeature> newRevFeature = geogig.command(RevObjectParse.class)
                .setObjectId(entry.getNewObject().getObjectId()).call(RevFeature.class);
        assertTrue(oldRevFeature.isPresent());
        assertTrue(newRevFeature.isPresent());

        Optional<RevFeatureType> type = geogig.command(RevObjectParse.class)
                .setObjectId(entry.getOldObject().getMetadataId()).call(RevFeatureType.class);
        assertTrue(type.isPresent());

        FeatureType featureType = type.get().type();

        CoordinateReferenceSystem expected = CRS.decode("EPSG:4326", true);
        CoordinateReferenceSystem actual = featureType.getCoordinateReferenceSystem();

        assertTrue(actual.toString(), CRS.equalsIgnoreMetadata(expected, actual));
    }

}
