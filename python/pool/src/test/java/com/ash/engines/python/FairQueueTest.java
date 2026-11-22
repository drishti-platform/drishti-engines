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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.engine.RequestClass;
import org.junit.jupiter.api.Test;

class FairQueueTest {

    record Job(RequestClass cls, String user) implements FairQueue.Item {}

    @Test
    void peopleBeforeEventsBeforeBatch() {
        FairQueue<Job> q = new FairQueue<>(10, 10, 10, 4, 1);
        Job b = new Job(RequestClass.BATCH, "u"), e = new Job(RequestClass.EVENT, "u"), i = new Job(RequestClass.INTERACTIVE, "u");
        q.offer(b);
        q.offer(e);
        q.offer(i);
        assertThat(q.next()).isSameAs(i);
        assertThat(q.next()).isSameAs(e);
        assertThat(q.next()).isSameAs(b);
    }

    @Test
    void usersTakeTurnsAndOneUserIsCapped() {
        FairQueue<Job> q = new FairQueue<>(10, 10, 10, 1, 1);
        Job a1 = new Job(RequestClass.INTERACTIVE, "a"), a2 = new Job(RequestClass.INTERACTIVE, "a"), b1 = new Job(RequestClass.INTERACTIVE, "b");
        q.offer(a1);
        q.offer(a2);
        q.offer(b1);
        assertThat(q.next()).isSameAs(a1);
        assertThat(q.next()).isSameAs(b1);
        assertThat(q.next()).isNull();                       // a is at its limit
        q.finished(a1);
        assertThat(q.next()).isSameAs(a2);
    }

    @Test
    void aFullQueueRefusesAndBatchKeepsToItsShare() {
        FairQueue<Job> q = new FairQueue<>(1, 1, 5, 4, 1);
        assertThat(q.offer(new Job(RequestClass.INTERACTIVE, "a"))).isTrue();
        assertThat(q.offer(new Job(RequestClass.INTERACTIVE, "a"))).isFalse();
        Job b1 = new Job(RequestClass.BATCH, "x"), b2 = new Job(RequestClass.BATCH, "y");
        q.offer(b1);
        q.offer(b2);
        q.next();
        assertThat(q.next()).isSameAs(b1);
        assertThat(q.next()).isNull();                       // one batch slot
    }
}
