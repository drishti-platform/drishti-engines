/*
 * Drishti Engines · the first-party platform engines.
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
package com.ash.engines.query.plan;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;

/**
 * The date windows of the time functions ({@code ytd}, {@code qtd}, {@code mtd}, {@code prior_period}, {@code same_period_last_year}),
 * worked out once per request from the as-of date and the document's calendar (weekends, the holidays it lists, the fiscal year's first
 * month): Drishti's {@code bi.engine.TimeWindows}, with the calendar carried in the document instead of read from the host, so the
 * engine stays closed. The windows become ordinary conditions on the date field.
 *
 * @param asOf the as-of date (the end of the document's dates, else the snapshot's date)
 * @param from the start of the document's dates, or null
 * @param holidays the calendar's holidays
 * @param fiscalYearStart the first month of the fiscal year (1: the calendar year)
 */
public record TimeWindows(LocalDate asOf, LocalDate from, Set<LocalDate> holidays, int fiscalYearStart) {

    /** The time functions, by name. */
    public static final Set<String> FUNCTIONS = Set.of("ytd", "qtd", "mtd", "prior_period", "same_period_last_year");

    public TimeWindows {
        holidays = Set.copyOf(new TreeSet<>(holidays == null ? Set.of() : holidays));
        fiscalYearStart = fiscalYearStart < 1 || fiscalYearStart > 12 ? 1 : fiscalYearStart;
    }

    /** An inclusive window. */
    public record Window(LocalDate lo, LocalDate hi) {}

    /** The window of a time function. */
    public Window of(String function) {
        if (asOf == null) {
            throw new IllegalArgumentException(function + " needs an as-of date: the snapshot has no date and the document gives none");
        }
        return switch (function) {
            case "ytd" -> new Window(fiscalStart(asOf, fiscalYearStart), asOf);
            case "qtd" -> {
                LocalDate fy = fiscalStart(asOf, fiscalYearStart);
                long months = java.time.temporal.ChronoUnit.MONTHS.between(fy.withDayOfMonth(1), asOf.withDayOfMonth(1));
                yield new Window(fy.plusMonths(months - months % 3), asOf);
            }
            case "mtd" -> new Window(asOf.withDayOfMonth(1), asOf);
            case "prior_period" -> {
                Window cur = current();
                int days = businessDays(cur.lo(), cur.hi());
                LocalDate hi = previousBusinessDay(cur.lo());
                LocalDate lo = hi;
                for (int i = 1; i < Math.max(1, days); i++) {
                    lo = previousBusinessDay(lo);
                }
                yield new Window(lo, hi);
            }
            case "same_period_last_year" -> {
                Window cur = current();
                LocalDate lo = cur.lo().minusYears(1);
                LocalDate hi = cur.hi().minusYears(1);
                while (!business(lo) && lo.isBefore(hi)) {
                    lo = lo.plusDays(1);
                }
                while (!business(hi) && hi.isAfter(lo)) {
                    hi = hi.minusDays(1);
                }
                yield new Window(lo, hi);
            }
            default -> throw new IllegalArgumentException(function + " is not a time function");
        };
    }

    /** The current window of {@code prior_period} and {@code same_period_last_year}: the dates asked, or the month to date. */
    public Window current() {
        return from == null ? new Window(asOf.withDayOfMonth(1), asOf) : new Window(from, asOf);
    }

    /** The first day of the fiscal year that holds {@code d}. */
    public static LocalDate fiscalStart(LocalDate d, int startMonth) {
        LocalDate s = LocalDate.of(d.getYear(), startMonth, 1);
        return d.isBefore(s) ? s.minusYears(1) : s;
    }

    boolean business(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    private LocalDate previousBusinessDay(LocalDate d) {
        LocalDate x = d.minusDays(1);
        while (!business(x)) {
            x = x.minusDays(1);
        }
        return x;
    }

    private int businessDays(LocalDate lo, LocalDate hi) {
        int n = 0;
        for (LocalDate d = lo; !d.isAfter(hi); d = d.plusDays(1)) {
            if (business(d)) {
                n++;
            }
        }
        return n;
    }
}
