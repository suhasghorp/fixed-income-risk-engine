package com.fixedincomerisk.curve;

/** A par curve together with its Curve Source. */
public record CurveSnapshot(ParCurve curve, CurveSourceKind source) {
}
