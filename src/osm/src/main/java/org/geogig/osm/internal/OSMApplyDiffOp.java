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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.geogig.osm.internal.coordcache.MappedPointCache;
import org.geogig.osm.internal.coordcache.PointCache;
import org.locationtech.geogig.model.NodeRef;
import org.locationtech.geogig.model.RevFeatureType;
import org.locationtech.geogig.model.impl.RevFeatureBuilder;
import org.locationtech.geogig.model.impl.RevFeatureTypeBuilder;
import org.locationtech.geogig.plumbing.FindTreeChild;
import org.locationtech.geogig.repository.AbstractGeoGigOp;
import org.locationtech.geogig.repository.Context;
import org.locationtech.geogig.repository.FeatureInfo;
import org.locationtech.geogig.repository.Platform;
import org.locationtech.geogig.repository.ProgressListener;
import org.locationtech.geogig.repository.SubProgressListener;
import org.locationtech.geogig.repository.WorkingTree;
import org.locationtech.geogig.repository.impl.FeatureToDelete;
import org.locationtech.geogig.storage.ObjectDatabase;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.opengis.feature.Feature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.FeatureType;
import org.openstreetmap.osmosis.core.OsmosisRuntimeException;
import org.openstreetmap.osmosis.core.container.v0_6.ChangeContainer;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Entity;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;
import org.openstreetmap.osmosis.core.task.common.ChangeAction;
import org.openstreetmap.osmosis.core.task.v0_6.ChangeSink;
import org.openstreetmap.osmosis.core.util.FixedPrecisionCoordinateConvertor;
import org.openstreetmap.osmosis.xml.common.CompressionMethod;
import org.openstreetmap.osmosis.xml.v0_6.XmlChangeReader;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

/**
 * Reads a OSM diff file and apply the changes to the current repo.
 * 
 * Changes are filtered to restrict additions to just those new features within the bbox of the
 * current OSM data in the repo, honoring the filter that might have been used to import that
 * preexistent data
 * 
 */

public class OSMApplyDiffOp extends AbstractGeoGigOp<Optional<OSMReport>> {

    private static final PrecisionModel PRECISION_MODEL = new PrecisionModel(
            1D / FixedPrecisionCoordinateConvertor.convertToDouble(1));

    private static final OSMCoordinateSequenceFactory CSFAC = OSMCoordinateSequenceFactory
            .instance();

    private static final GeometryFactory GEOMF = new GeometryFactory(PRECISION_MODEL, 4326, CSFAC);

    // new PackedCoordinateSequenceFactory());
    /**
     * The file to import
     */
    private File file;

    public OSMApplyDiffOp setDiffFile(File file) {
        this.file = file;
        return this;
    }

    @Override
    protected Optional<OSMReport> _call() {
        checkNotNull(file);
        Preconditions.checkArgument(file.exists(), "File does not exist: " + file);

        ProgressListener progressListener = getProgressListener();
        progressListener.setDescription("Applying OSM diff file to GeoGig repo...");

        OSMReport report = parseDiffFileAndInsert();

        return Optional.fromNullable(report);

    }

    public OSMReport parseDiffFileAndInsert() {
        final int queueCapacity = 100 * 1000;
        final int timeout = 1;
        final TimeUnit timeoutUnit = TimeUnit.SECONDS;
        // With this iterator and the osm parsing happening on a separate thread, we follow a
        // producer/consumer approach so that the osm parse thread produces features into the
        // iterator's queue, and WorkingTree.insert consumes them on this thread
        QueueIterator<Feature> target = new QueueIterator<Feature>(queueCapacity, timeout,
                timeoutUnit);

        XmlChangeReader reader = new XmlChangeReader(file, true, resolveCompressionMethod(file));

        ProgressListener progressListener = getProgressListener();
        ConvertAndImportSink sink = new ConvertAndImportSink(target, context, workingTree(),
                platform(), new SubProgressListener(progressListener, 100));
        reader.setChangeSink(sink);

        Thread readerThread = new Thread(reader, "osm-diff-reader-thread");
        readerThread.start();

        // used to set the task status name, but report no progress so it does not interfere
        // with the progress reported by the reader thread
        SubProgressListener noProgressReportingListener = new SubProgressListener(progressListener,
                0) {
            @Override
            public void setProgress(float progress) {
                // no-op
            }
        };

        final Function<Feature, String> parentTreePathResolver = (f) -> f.getType().getName()
                .getLocalPart();
        
        final ObjectDatabase objectDatabase = objectDatabase();
        
    	Map<FeatureType, RevFeatureType> types = new HashMap<>();
        Iterator<FeatureInfo> finfos = Iterators.transform(target, (f) -> {
            FeatureType ft = f.getType();
            RevFeatureType rft = types.get(ft);
            if (rft == null) {
                rft = RevFeatureTypeBuilder.build(ft);
                types.put(ft, rft);
                objectDatabase.put(rft);
            }
            String featurePath = NodeRef.appendChild(parentTreePathResolver.apply(f), f.getIdentifier().getID());
            if (f instanceof FeatureToDelete) {
            	return FeatureInfo.delete(featurePath);
            }
            return FeatureInfo.insert(RevFeatureBuilder.build(f), rft.getId(), featurePath);
        });

        workingTree().insert(finfos, noProgressReportingListener);

        OSMReport report = new OSMReport(sink.getCount(), sink.getNodeCount(), sink.getWayCount(),
                sink.getUnprocessedCount(), sink.getLatestChangeset(), sink.getLatestTimestamp());
        return report;
    }

    private CompressionMethod resolveCompressionMethod(File file) {
        String fileName = file.getName();
        if (fileName.endsWith(".gz")) {
            return CompressionMethod.GZip;
        } else if (fileName.endsWith(".bz2")) {
            return CompressionMethod.BZip2;
        }
        return CompressionMethod.None;
    }

    /**
     * A sink that processes OSM changes and translates the to the repository working tree
     * 
     */
    static class ConvertAndImportSink implements ChangeSink {

        private static final Function<WayNode, Long> NODELIST_TO_ID_LIST = (wn) -> Long
                .valueOf(wn.getNodeId());

        private int count = 0;

        private int nodeCount;

        private int wayCount;

        private int unableToProcessCount = 0;

        private EntityConverter converter = new EntityConverter();

        private long latestChangeset;

        private long latestTimestamp;

        private PointCache pointCache;

        private QueueIterator<Feature> target;

        private ProgressListener progressListener;

        private WorkingTree workTree;

        private Geometry bbox;

        public ConvertAndImportSink(QueueIterator<Feature> target, Context cmdLocator,
                WorkingTree workTree, Platform platform, ProgressListener progressListener) {
            super();
            this.target = target;
            this.workTree = workTree;
            this.progressListener = progressListener;
            this.latestChangeset = 0;
            this.latestTimestamp = 0;
            this.pointCache = new MappedPointCache(platform);
            Optional<NodeRef> waysNodeRef = cmdLocator.command(FindTreeChild.class)
                    .setChildPath(OSMUtils.WAY_TYPE_NAME).setParent(workTree.getTree()).call();
            Optional<NodeRef> nodesNodeRef = cmdLocator.command(FindTreeChild.class)
                    .setChildPath(OSMUtils.NODE_TYPE_NAME).setParent(workTree.getTree()).call();
            checkArgument(waysNodeRef.isPresent() || nodesNodeRef.isPresent(),
                    "There is no OSM data currently in the repository");
            Envelope envelope = new Envelope(-180,180,-90,90);
            if (waysNodeRef.isPresent()) {
                waysNodeRef.get().expand(envelope);
            }
            if (nodesNodeRef.isPresent()) {
                nodesNodeRef.get().expand(envelope);
            }
            bbox = GEOMF.toGeometry(envelope);
        }

        public long getUnprocessedCount() {
            return unableToProcessCount;
        }

        public long getCount() {
            return count;
        }

        public long getNodeCount() {
            return nodeCount;
        }

        public long getWayCount() {
            return wayCount;
        }

        @Override
        public void complete() {
            try {
                progressListener.setProgress(count);
                progressListener.complete();
            } finally {
                try {
                    target.noMoreInput();
                } finally {
                    pointCache.dispose();
                }
            }
        }

        @Override
        public void release() {
            pointCache.dispose();
        }

        @Override
        public void process(ChangeContainer container) {
            if (progressListener.isCanceled()) {
                target.cancel();
                throw new OsmosisRuntimeException("Cancelled by user");
            }
            final EntityContainer entityContainer = container.getEntityContainer();
            final Entity entity = entityContainer.getEntity();
            final ChangeAction changeAction = container.getAction();
            if (changeAction.equals(ChangeAction.Delete)) {
                SimpleFeatureType ft = entity instanceof Node ? OSMUtils.nodeType() : OSMUtils
                        .wayType();
                String id = Long.toString(entity.getId());
                target.put(new FeatureToDelete(ft, id));
                return;
            }
            if (changeAction.equals(ChangeAction.Modify)) {
                // Check that the feature to modify exist. If so, we will just treat it as an
                // addition, overwriting the previous feature
                SimpleFeatureType ft = entity instanceof Node ? OSMUtils.nodeType() : OSMUtils
                        .wayType();
                String path = ft.getName().getLocalPart();
                Optional<org.locationtech.geogig.model.Node> opt = workTree.findUnstaged(path);
                if (!opt.isPresent()) {
                    return;
                }
            }

            if (++count % 10 == 0) {
                progressListener.setProgress(count);
            }
            latestChangeset = Math.max(latestChangeset, entity.getChangesetId());
            latestTimestamp = Math.max(latestTimestamp, entity.getTimestamp().getTime());
            Geometry geom = null;
            switch (entity.getType()) {
            case Node:
                nodeCount++;
                geom = parsePoint((Node) entity);
                break;
            case Way:
                wayCount++;
                geom = parseLine((Way) entity);
                break;
            default:
                return;
            }
            if (geom != null) {
            	boolean within = geom.within(bbox);
            	if (!within) {
            		System.err.printf("%s within %s? %s\n", geom, bbox, geom.within(bbox));
            	}
                if (changeAction.equals(ChangeAction.Create) && within
                        || changeAction.equals(ChangeAction.Modify)) {
                    Feature feature = converter.toFeature(entity, geom);
                    target.put(feature);
                }
            }
        }

        /**
         * returns the latest timestamp of all the entities processed so far
         * 
         * @return
         */
        public long getLatestTimestamp() {
            return latestTimestamp;
        }

        /**
         * returns the id of the latest changeset of all the entities processed so far
         * 
         * @return
         */
        public long getLatestChangeset() {
            return latestChangeset;
        }

        public boolean hasProcessedEntities() {
            return latestChangeset != 0;
        }

        @Override
        public void initialize(Map<String, Object> map) {
        }

        protected Geometry parsePoint(Node node) {
            double longitude = node.getLongitude();
            double latitude = node.getLatitude();
            OSMCoordinateSequenceFactory csf = CSFAC;
            OSMCoordinateSequence cs = csf.create(1, 2);
            cs.setOrdinate(0, 0, longitude);
            cs.setOrdinate(0, 1, latitude);
            Point pt = GEOMF.createPoint(cs);
            pointCache.put(Long.valueOf(node.getId()), cs);
            return pt;
        }

        /**
         * @return {@code null} if the way nodes cannot be found, or its list of nodes is too short,
         *         the parsed {@link LineString} otherwise
         */
        @Nullable
        protected Geometry parseLine(Way way) {
            final List<WayNode> nodes = way.getWayNodes();

            if (nodes.size() < 2) {
                unableToProcessCount++;
                return null;
            }

            final List<Long> ids = Lists.transform(nodes, NODELIST_TO_ID_LIST);

            try {
                CoordinateSequence coordinates = pointCache.get(ids);
                return GEOMF.createLineString(coordinates);
            } catch (IllegalArgumentException e) {
                unableToProcessCount++;
                return null;
            }

        }
    }

}
