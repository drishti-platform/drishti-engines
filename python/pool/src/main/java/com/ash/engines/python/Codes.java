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

/** The python engine's own error codes (descriptor {@code codes: PYT}); the host's {@code ENG-} codes keep their meaning. */
final class Codes {

    private Codes() {
    }

    /** The named code item is not among the approved items the session holds. */
    static final String NOT_APPROVED = "PYT-1001";
    /** The code table lacks columns the engine needs, or an item is not well formed. */
    static final String BAD_ITEM = "PYT-1002";
    /** The text of an item does not hash to its approved hash. */
    static final String HASH = "PYT-1003";
    /** The author's code failed; its own traceback is in the detail. */
    static final String FAILED = "PYT-2001";
    /** The code is not valid for the interface (does not compile, no entry, a forbidden import). */
    static final String INVALID = "PYT-2002";
    /** A frame is over the engine's row limit. */
    static final String FRAME_LIMIT = "PYT-2010";
    /** A limit of the run was hit: memory, CPU time, the size of the result, groups. */
    static final String LIMIT = "PYT-2072";
    /** The sandbox refused something the code tried (network, files outside the folder, a process, a package not approved). */
    static final String SANDBOX = "PYT-2073";
    /** Python could not be started on this machine or the strict sandbox is not available. */
    static final String NO_PYTHON = "PYT-3001";
    /** The worker's answer broke the transport's rules. */
    static final String PROTOCOL = "PYT-3002";
    /** The code refused the request in words (a typed ComputeError). */
    static final String ENGINE_REFUSED = "PYT-4002";
    /** A request names an output or a parameter the engine does not have, or lacks one it needs. */
    static final String REQUEST = "PYT-4001";
}
