/* Copyright (c) 2013-2016 Boundless and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/edl-v10.html
 *
 * Contributors:
 * Victor Olaya (Boundless) - initial implementation
 */
package org.geogig.osm.internal.history;

import org.locationtech.jts.geom.Point;

import com.google.common.base.Optional;

/**
 *
 */
public class Node extends Primitive {

    /** WGS84 location, lon/lat axis order */
    private Point location;

    /** WGS84 location, lon/lat axis order */
    public Optional<Point> getLocation() {
        return Optional.fromNullable(location);
    }

    /**
     * @param location point location, in lon/lat ordinate order
     */
    void setLocation(Point location) {
        this.location = location;
    }

    @Override
    public String toString() {
        return new StringBuilder(super.toString()).append(",location:").append(location)
                .append(']').toString();
    }
}
