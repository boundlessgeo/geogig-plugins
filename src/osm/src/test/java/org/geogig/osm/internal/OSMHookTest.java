/* Copyright (c) 2013-2016 Boundless and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/edl-v10.html
 *
 * Contributors:
 * Victor Olaya (Boundless) - initial implementation
 */
package org.geogig.osm.internal;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.locationtech.geogig.model.FieldType;
import org.locationtech.geogig.model.NodeRef;
import org.locationtech.geogig.model.RevFeature;
import org.locationtech.geogig.model.RevFeatureType;
import org.locationtech.geogig.model.impl.RevFeatureBuilder;
import org.locationtech.geogig.plumbing.ResolveFeatureType;
import org.locationtech.geogig.plumbing.ResolveGeogigDir;
import org.locationtech.geogig.plumbing.RevObjectParse;
import org.locationtech.geogig.porcelain.AddOp;
import org.locationtech.geogig.porcelain.CommitOp;
import org.locationtech.geogig.repository.FeatureInfo;
import org.locationtech.geogig.test.integration.RepositoryTestCase;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;

public class OSMHookTest extends RepositoryTestCase {
    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Override
    protected void setUpInternal() throws Exception {
        repo.configDatabase().put("user.name", "groldan");
        repo.configDatabase().put("user.email", "groldan@boundlessgeo.com");
    }

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testOSMHook() throws Exception {

        // set the hook that will trigger an unmapping when something is imported to the busstops
        // tree
        CharSequence commitPreHookCode = "var diffs = geogig.getFeaturesToCommit(\"busstops\", false);\n"
                + "if (diffs.length > 0){\n" + "\tvar params = {\"path\" : \"busstops\"};\n"
                + "\tgeogig.run(\"org.geogig.osm.internal.OSMUnmapOp\", params)\n}";
        File hooksFolder = new File(new ResolveGeogigDir(geogig.getPlatform()).getFile().get(),
                "hooks");
        File commitPreHookFile = new File(hooksFolder, "pre_commit.js");

        Files.write(commitPreHookCode, commitPreHookFile, Charsets.UTF_8);

        // Import
        String filename = OSMImportOp.class.getResource("nodes.xml").getFile();
        File file = new File(filename);
        geogig.command(OSMImportOp.class).setDataSource(file.getAbsolutePath()).call();

        // Map
        Map<String, AttributeDefinition> fields = Maps.newHashMap();
        Map<String, List<String>> mappings = Maps.newHashMap();
        mappings.put("highway", Lists.newArrayList("bus_stop"));
        fields.put("geom", new AttributeDefinition("geom", FieldType.POINT));
        fields.put("name", new AttributeDefinition("name", FieldType.STRING));
        MappingRule mappingRule = new MappingRule("busstops", mappings, null, fields, null);
        List<MappingRule> mappingRules = Lists.newArrayList();
        mappingRules.add(mappingRule);
        Mapping mapping = new Mapping(mappingRules);
        geogig.command(AddOp.class).call();
        geogig.command(CommitOp.class).setMessage("msg").call();
        geogig.command(OSMMapOp.class).setMapping(mapping).call();

        Optional<RevFeature> revFeature = geogig.command(RevObjectParse.class)
                .setRefSpec("HEAD:busstops/507464799").call(RevFeature.class);
        assertTrue(revFeature.isPresent());
        Optional<RevFeatureType> featureType = geogig.command(ResolveFeatureType.class)
                .setRefSpec("HEAD:busstops/507464799").call();
        assertTrue(featureType.isPresent());
        ImmutableList<Optional<Object>> values = revFeature.get().getValues();
        assertEquals(3, values.size());
        String wkt = "POINT (7.1959361 50.739397)";
        assertEquals(wkt, values.get(2).get().toString());
        assertEquals(507464799l, values.get(0).get());

        // Modify a node
        GeometryFactory gf = new GeometryFactory();
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(
                (SimpleFeatureType) featureType.get().type());
        fb.set("geom", gf.createPoint(new Coordinate(0, 1)));
        fb.set("name", "newname");
        fb.set("id", 507464799l);
        SimpleFeature newFeature = fb.buildFeature("507464799");
        String path = NodeRef.appendChild("busstops", newFeature.getIdentifier().getID());
        FeatureInfo fi = FeatureInfo.insert(RevFeatureBuilder.build(newFeature), featureType.get().getId(), path);
        geogig.getRepository().workingTree().insert(fi);
        geogig.command(AddOp.class).call();
        geogig.command(CommitOp.class).setMessage("msg").call(); // this should trigger the hook

        // check that the unmapping has been triggered and the unmapped node has the changes we
        // introduced
        Optional<RevFeature> unmapped = geogig.command(RevObjectParse.class)
                .setRefSpec("WORK_HEAD:node/507464799").call(RevFeature.class);
        assertTrue(unmapped.isPresent());
        values = unmapped.get().getValues();
        assertEquals("POINT (0 1)", values.get(6).get().toString());

        Map<String, String> expected = asMap("VRS:gemeinde", "BONN", "VRS:ortsteil", "Hoholz",
                "VRS:ref", "68566", "bus", "yes", "highway", "bus_stop", "name", "newname",
                "public_transport", "platform");
        assertEquals(expected, values.get(3).get());
        // check that unchanged nodes keep their attributes
        Optional<RevFeature> unchanged = geogig.command(RevObjectParse.class)
                .setRefSpec("WORK_HEAD:node/1633594723").call(RevFeature.class);
        values = unchanged.get().getValues();
        assertEquals("14220478", values.get(4).get().toString());
        assertEquals("1355097351000", values.get(2).get().toString());
        assertEquals("2", values.get(1).get().toString());

    }
}
