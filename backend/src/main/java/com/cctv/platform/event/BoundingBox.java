package com.cctv.platform.event;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Normalized 0..1 relative to the frame, as in the API contract. */
public record BoundingBox(double x, double y, double width, double height) {

    @JsonIgnore // a check, not a property: keep it out of stored and returned JSON
    public boolean isValid() {
        return x >= 0 && y >= 0 && width >= 0 && height >= 0 && x + width <= 1.0001 && y + height <= 1.0001;
    }
}
