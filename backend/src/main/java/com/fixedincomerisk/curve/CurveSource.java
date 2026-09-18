package com.fixedincomerisk.curve;

/** Port that supplies the real Treasury par curve the session is anchored to. */
public interface CurveSource {

    CurveSnapshot load();
}
