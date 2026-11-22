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

import com.ash.engine.RequestClass;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The waiting requests of the pool, served fairly. Not thread-safe: the pool holds its lock around every call.
 *
 * <ul>
 *   <li><b>By class:</b> interactive first, then event, then batch; batch may hold at most its share of the workers at once
 *       ({@code batchSlots}), so a flood of batch work never takes the pool from people.</li>
 *   <li><b>By user:</b> within a class, users take turns (round robin), and no user runs more than {@code maxInflightPerUser}
 *       requests at once: one user's thousand requests queue behind another user's one.</li>
 *   <li><b>Back-pressure:</b> each class has a bounded queue; a full queue refuses at once ({@link #offer} is false).</li>
 * </ul>
 */
final class FairQueue<J extends FairQueue.Item> {

    /** What the queue needs of a waiting request. */
    interface Item {
        RequestClass cls();

        String user();
    }

    private final Map<RequestClass, LinkedHashMap<String, ArrayDeque<J>>> waiting = new EnumMap<>(RequestClass.class);
    private final Map<RequestClass, Integer> sizes = new EnumMap<>(RequestClass.class);
    private final Map<RequestClass, Integer> caps = new EnumMap<>(RequestClass.class);
    private final Map<String, Integer> inflight = new HashMap<>();
    private final int maxInflightPerUser;
    private int batchSlots;
    private int runningBatch;

    FairQueue(int capInteractive, int capEvent, int capBatch, int maxInflightPerUser, int batchSlots) {
        for (RequestClass c : RequestClass.values()) {
            waiting.put(c, new LinkedHashMap<>());
            sizes.put(c, 0);
        }
        caps.put(RequestClass.INTERACTIVE, capInteractive);
        caps.put(RequestClass.EVENT, capEvent);
        caps.put(RequestClass.BATCH, capBatch);
        this.maxInflightPerUser = maxInflightPerUser;
        this.batchSlots = Math.max(1, batchSlots);
    }

    /** Queues the item; false when its class's queue is full. */
    boolean offer(J item) {
        RequestClass c = item.cls();
        if (sizes.get(c) >= caps.get(c)) {
            return false;
        }
        waiting.get(c).computeIfAbsent(item.user(), u -> new ArrayDeque<>()).addLast(item);
        sizes.merge(c, 1, Integer::sum);
        return true;
    }

    /**
     * The next item to run, or null when nothing may run now (empty, every waiting user at their limit, or only batch work with the batch
     * share taken). The item counts as running until {@link #finished} is called for it.
     */
    J next() {
        for (RequestClass c : RequestClass.values()) {
            if (c == RequestClass.BATCH && runningBatch >= batchSlots) {
                continue;
            }
            LinkedHashMap<String, ArrayDeque<J>> users = waiting.get(c);
            Iterator<Map.Entry<String, ArrayDeque<J>>> it = users.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, ArrayDeque<J>> e = it.next();
                String user = e.getKey();
                if (inflight.getOrDefault(user, 0) >= maxInflightPerUser) {
                    continue;
                }
                J item = e.getValue().pollFirst();
                it.remove();
                if (!e.getValue().isEmpty()) {
                    users.put(user, e.getValue());                         // to the back of the turn order
                }
                sizes.merge(c, -1, Integer::sum);
                inflight.merge(user, 1, Integer::sum);
                if (c == RequestClass.BATCH) {
                    runningBatch++;
                }
                return item;
            }
        }
        return null;
    }

    /** A running item ended (done, failed or cancelled while running). */
    void finished(J item) {
        inflight.computeIfPresent(item.user(), (u, n) -> n <= 1 ? null : n - 1);
        if (item.cls() == RequestClass.BATCH && runningBatch > 0) {
            runningBatch--;
        }
    }

    /** Takes a waiting item out (cancelled or timed out before it ran); false when it is not waiting. */
    boolean remove(J item) {
        LinkedHashMap<String, ArrayDeque<J>> users = waiting.get(item.cls());
        ArrayDeque<J> q = users.get(item.user());
        if (q == null || !q.remove(item)) {
            return false;
        }
        if (q.isEmpty()) {
            users.remove(item.user());
        }
        sizes.merge(item.cls(), -1, Integer::sum);
        return true;
    }

    /** Every waiting item, interactive first. */
    List<J> waiting() {
        List<J> out = new ArrayList<>();
        waiting.values().forEach(users -> users.values().forEach(out::addAll));
        return out;
    }

    int size(RequestClass c) {
        return sizes.get(c);
    }

    int size() {
        return sizes.values().stream().mapToInt(Integer::intValue).sum();
    }

    int running(String user) {
        return inflight.getOrDefault(user, 0);
    }

    int runningBatch() {
        return runningBatch;
    }
}
