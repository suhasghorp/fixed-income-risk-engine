package com.fixedincomerisk.curve;

/** A Curve Source could not supply a usable par curve. */
public class CurveUnavailableException extends RuntimeException {

    public CurveUnavailableException(String message) {
        super(message);
    }

    public CurveUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
