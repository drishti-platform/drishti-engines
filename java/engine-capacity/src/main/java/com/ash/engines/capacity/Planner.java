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
package com.ash.engines.capacity;

import com.ash.engine.text.StrictYaml;
import com.ash.engines.capacity.Expr.Node;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The planner's one file, {@code capacity/planner.yaml} in this JAR, read once: the inputs (the request's parameters), the coefficients, and
 * every figure in the planner's order (results, JVM arguments, the command line, settings, the YAML block, warnings, calibration), each an
 * expression tree. The two derived figures, {@code JvmCommand} and {@code YamlBlock}, are built from the file's own lists, as the planner's
 * other evaluators built them. Nothing here evaluates: that is {@link Evaluator}.
 */
final class Planner {

    static final String RESOURCE = "capacity/planner.yaml";
    private static final Pattern HUMP = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    /** One input: the request's parameter {@code param} (kebab case of the name), its kind ({@code int}, {@code dec} or {@code text}) and default. */
    record Input(String name, String param, String label, Object defaultValue, String unit, String kind, List<Object> choices) {
    }

    /** One computed figure; {@code text} is true when its value at the defaults is text. */
    record Figure(int ordinal, String section, String name, String label, String unit, String why, String expression, Node node, boolean text) {
    }

    private final List<Input> inputs = new ArrayList<>();
    private final Map<String, Object> coefficients = new LinkedHashMap<>();
    private final Map<String, String> coefficientUnits = new LinkedHashMap<>();
    private final List<Figure> figures = new ArrayList<>();
    private final Map<String, Figure> byName = new LinkedHashMap<>();
    private final Map<String, Input> inputByName = new LinkedHashMap<>();

    /** The planner in this JAR. */
    static Planner builtIn(Class<?> owner) {
        try (InputStream in = owner.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the engine has no " + RESOURCE);
            }
            return new Planner(StrictYaml.readMap(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The request parameter of an input: TradesPerFungible is trades-per-fungible. */
    static String paramName(String inputName) {
        return HUMP.matcher(inputName).replaceAll("-").toLowerCase(java.util.Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    Planner(Map<String, Object> plan) {
        for (Map<String, Object> in : (List<Map<String, Object>>) plan.get("inputs")) {
            String name = (String) in.get("name");
            Input i = new Input(name, paramName(name), (String) in.get("label"), in.get("default"), (String) in.get("unit"), (String) in.get("kind"),
                    (List<Object>) in.getOrDefault("choices", List.of()));
            inputs.add(i);
            inputByName.put(name, i);
        }
        for (Map<String, Object> c : (List<Map<String, Object>>) plan.get("coefficients")) {
            coefficients.put((String) c.get("name"), c.get("value") instanceof Number n ? (Object) n.doubleValue() : c.get("value"));
            coefficientUnits.put((String) c.get("name"), (String) c.get("unit"));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        ((List<Map<String, Object>>) plan.get("results")).forEach(band -> results.addAll((List<Map<String, Object>>) band.get("rows")));
        List<Map<String, Object>> jvm = (List<Map<String, Object>>) plan.get("jvm");
        List<Map<String, Object>> settings = (List<Map<String, Object>>) plan.get("settings");
        results.forEach(r -> add("results", r, (String) r.get("expression")));
        jvm.forEach(j -> add("jvm", j, (String) j.get("expression")));
        derived("command", "JvmCommand", "Command line", "The whole command line of the server.", commandFormula(jvm));
        settings.forEach(s -> add("settings", s, (String) s.get("expression")));
        derived("yaml", "YamlBlock", "Settings as one YAML block", "Every setting, nested, as the settings file takes it.", yamlBlockFormula(settings));
        ((List<Map<String, Object>>) plan.get("warnings")).forEach(w -> add("warnings", w, (String) w.get("expression")));
        ((List<Map<String, Object>>) plan.getOrDefault("calibration", List.of())).forEach(c -> add("calibration", c, (String) c.get("expression")));
        classify();
    }

    private void add(String section, Map<String, Object> row, String expression) {
        String label = row.get("label") != null ? (String) row.get("label") : row.get("key") != null ? (String) row.get("key") : row.get("flag") != null ? (String) row.get("flag") : (String) row.get("name");
        put(new Figure(figures.size() + 1, section, (String) row.get("name"), label, row.get("unit") == null ? "" : (String) row.get("unit"),
                row.get("why") == null ? "" : (String) row.get("why"), expression, Expr.parse(expression), false));
    }

    private void derived(String section, String name, String label, String why, String expression) {
        put(new Figure(figures.size() + 1, section, name, label, "text", why, expression, Expr.parse(expression), false));
    }

    private void put(Figure f) {
        if (byName.containsKey(f.name()) || inputByName.containsKey(f.name()) || coefficients.containsKey(f.name())) {
            throw new IllegalStateException("the planner names " + f.name() + " twice");
        }
        figures.add(f);
        byName.put(f.name(), f);
    }

    /** Works every figure out once at the defaults: a figure whose value is text goes to the text lines, the others to the figures. */
    private void classify() {
        Evaluator ev = new Evaluator(cells(Map.of()));
        for (int i = 0; i < figures.size(); i++) {
            Figure f = figures.get(i);
            boolean text = !(ev.value(f.name()) instanceof Double);
            Figure g = new Figure(f.ordinal(), f.section(), f.name(), f.label(), f.unit(), f.why(), f.expression(), f.node(), text);
            figures.set(i, g);
            byName.put(g.name(), g);
        }
    }

    List<Input> inputs() {
        return List.copyOf(inputs);
    }

    Input input(String name) {
        return inputByName.get(name);
    }

    Map<String, Object> coefficients() {
        return Map.copyOf(coefficients);
    }

    /** A coefficient's name, value and unit, in the file's order. */
    List<String> coefficientNames() {
        return List.copyOf(coefficients.keySet());
    }

    String coefficientUnit(String name) {
        return coefficientUnits.get(name);
    }

    List<Figure> figures() {
        return List.copyOf(figures);
    }

    Figure figure(String name) {
        return byName.get(name);
    }

    /** Every named cell for an evaluation: the inputs at their defaults with {@code overrides} in place, the coefficients, every figure. */
    Map<String, Object> cells(Map<String, Object> overrides) {
        Map<String, Object> c = new LinkedHashMap<>();
        for (Input i : inputs) {
            Object d = i.defaultValue();
            c.put(i.name(), d instanceof Number n ? (Object) n.doubleValue() : d);
        }
        c.putAll(overrides);
        c.putAll(coefficients);
        for (Figure f : figures) {
            c.put(f.name(), f.node());
        }
        return c;
    }

    /** The figures a figure reads, transitively, in the order they must be worked out (the figure last). */
    List<Figure> closure(String name) {
        Set<String> seen = new LinkedHashSet<>();
        List<Figure> out = new ArrayList<>();
        walk(name, seen, out);
        return out;
    }

    private void walk(String name, Set<String> seen, List<Figure> out) {
        Figure f = byName.get(name);
        if (f == null || !seen.add(name)) {
            return;
        }
        for (String r : Expr.refs(f.node())) {
            walk(r, seen, out);
        }
        out.add(f);
    }

    // ---------------------------------------------------------------------------------------------------- the derived formulas

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** The whole command line: the arenas variable, then each JVM argument that is set, then the jar. */
    private static String commandFormula(List<Map<String, Object>> jvm) {
        List<String> parts = new ArrayList<>();
        parts.add("JvmArenas&\" java\"");
        for (Map<String, Object> j : jvm) {
            String n = (String) j.get("name");
            if (!n.equals("JvmArenas")) {
                parts.add("IF(" + n + "=\"\",\"\",\" \"&" + n + ")");
            }
        }
        return String.join("&", parts) + "&\" -jar \"&ServerJar";
    }

    /** A node of the nested settings: children by key, or a leaf (the setting's name and its condition). */
    private static final class Tree {
        final Map<String, Tree> children = new LinkedHashMap<>();
        String leafName;
        String leafCondition;
        boolean leaf;
    }

    /** One nested YAML block of every setting; a key's line shows only when the condition of its settings holds. */
    @SuppressWarnings("unchecked")
    private static String yamlBlockFormula(List<Map<String, Object>> settings) {
        Tree root = new Tree();
        for (Map<String, Object> s : settings) {
            String[] parts = ((String) s.get("key")).split("\\.");
            Tree node = root;
            for (int i = 0; i < parts.length - 1; i++) {
                node = node.children.computeIfAbsent(parts[i], k -> new Tree());
            }
            Tree leaf = new Tree();
            leaf.leaf = true;
            leaf.leafName = (String) s.get("name");
            leaf.leafCondition = (String) s.get("condition");
            node.children.put(parts[parts.length - 1], leaf);
        }
        List<String[]> lines = new ArrayList<>();                        // {text expression, condition or null}
        emit(root, 0, null, lines);
        StringBuilder out = new StringBuilder(lines.get(0)[0]);
        for (int i = 1; i < lines.size(); i++) {
            String[] l = lines.get(i);
            out.append('&').append(l[1] != null ? "IF(" + l[1] + ",CHAR(10)&" + l[0] + ",\"\")" : "CHAR(10)&" + l[0]);
        }
        return out.toString();
    }

    private static List<String> conditions(Tree node) {
        List<String> out = new ArrayList<>();
        for (Tree v : node.children.values()) {
            List<String> c = v.leaf ? java.util.Collections.singletonList(v.leafCondition) : conditions(v);
            for (String x : c) {
                if (!out.contains(x)) {
                    out.add(x);
                }
            }
        }
        return out;
    }

    private static void emit(Tree node, int depth, String outer, List<String[]> lines) {
        String pad = "  ".repeat(depth);
        for (Map.Entry<String, Tree> e : node.children.entrySet()) {
            Tree v = e.getValue();
            if (!v.leaf) {
                List<String> c = conditions(v);
                String cond = c.size() == 1 ? c.get(0) : null;
                String eff = cond != null ? cond : outer;
                lines.add(new String[] {q(pad + e.getKey() + ":"), eff});
                emit(v, depth + 1, eff, lines);
            } else {
                lines.add(new String[] {q(pad + e.getKey() + ": ") + "&" + v.leafName, v.leafCondition != null ? v.leafCondition : outer});
            }
        }
    }
}
