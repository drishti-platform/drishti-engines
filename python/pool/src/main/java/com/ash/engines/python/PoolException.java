/*
 * Drishti Engines · first-party engines by contract.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.engines.python;

import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import java.util.Locale;
import java.util.Map;

/**
 * Why a request did not produce a result, in words a person can read. The {@link Code} says what kind of failure it was; an engine's
 * own exception text is never in the message (it goes to the log with the request id), except for a typed refusal the code raised on
 * purpose ({@link Code#ENGINE}). {@link #toEngine()} gives the one typed exception a host shows.
 */
public class PoolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The kinds of failure. */
    public enum Code {
        /** the engine is off or the pool is not started */
        OFF,
        /** refused before any work: unknown engine, a bad request, Python not found */
        REFUSED,
        /** the queue for the request's class is full: try again in a moment */
        BUSY,
        /** the request's wall-time budget passed; the worker was stopped */
        TIMEOUT,
        /** someone cancelled it */
        CANCELLED,
        /** the worker process died */
        CRASHED,
        /** the worker passed its memory or CPU limit, or did something its sandbox refuses */
        LIMIT,
        /** the code refused the request in words (a typed ComputeError) */
        ENGINE,
        /** the code failed; the details are in the log under the request id */
        FAILED,
        /** the worker's answer broke the transport's rules (schema, size, a file outside the scratch folder) */
        INVALID,
        /** no worker could be started */
        UNAVAILABLE;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Code code;
    private final String requestId;

    public PoolException(Code code, String message) {
        this(code, message, null, null);
    }

    public PoolException(Code code, String message, String requestId, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.requestId = requestId;
    }

    public Code code() {
        return code;
    }

    public String requestId() {
        return requestId;
    }

    /** The same failure, naming the request it belongs to. */
    public PoolException forRequest(String id) {
        return requestId != null ? this : new PoolException(code, getMessage(), id, getCause());
    }

    /** The typed exception a host shows ({@code ENG-} codes where the specification has one, {@code PYT-} otherwise). */
    public EngineException toEngine() {
        Map<String, String> detail = requestId == null ? Map.of() : Map.of("request", requestId);
        return switch (code) {
            case BUSY -> new EngineException("ENG-5001", Kind.LIMIT, getMessage(), detail, this);
            case TIMEOUT -> new EngineException("ENG-5011", Kind.TIMED_OUT, getMessage(), detail, this);
            case CANCELLED -> new EngineException("ENG-5010", Kind.CANCELLED, getMessage(), detail, this);
            case CRASHED -> new EngineException("ENG-7001", Kind.UNAVAILABLE, getMessage(), detail, this);
            case LIMIT -> new EngineException(Codes.LIMIT, Kind.LIMIT, getMessage(), detail, this);
            case ENGINE -> new EngineException(Codes.ENGINE_REFUSED, Kind.REFUSED, getMessage(), detail, this);
            case INVALID -> new EngineException(Codes.PROTOCOL, Kind.FAILED, getMessage(), detail, this);
            case OFF, UNAVAILABLE -> new EngineException(Codes.NO_PYTHON, Kind.UNAVAILABLE, getMessage(), detail, this);
            case REFUSED -> new EngineException(Codes.REQUEST, Kind.REFUSED, getMessage(), detail, this);
            case FAILED -> new EngineException("ENG-9000", Kind.FAILED, getMessage(), detail, this);
        };
    }
}
