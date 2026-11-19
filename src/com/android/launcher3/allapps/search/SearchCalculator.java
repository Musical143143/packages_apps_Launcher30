/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.allapps.search;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java arithmetic and unit conversion for the app drawer search. Has no Android
 * dependencies so it can be unit tested on the host JVM.
 *
 * <p>Returns {@code null} whenever the query is not a calculation or conversion.
 */
public final class SearchCalculator {

    private static final int MAX_EXPRESSION_LENGTH = 100;
    private static final MathContext MATH = new MathContext(12);
    private static final MathContext DISPLAY = new MathContext(6);

    /** Maps every accepted unit name (canonical or alias) to its canonical name. */
    private static final Map<String, String> CANONICAL = new HashMap<>();
    /** Canonical unit name to its factor relative to the dimension's base unit. */
    private static final Map<String, Double> FACTOR = new HashMap<>();
    /** Canonical unit name to its dimension, e.g. "length". */
    private static final Map<String, String> DIMENSION = new HashMap<>();

    private static final Pattern CONVERSION = Pattern.compile(
            "^\\s*(-?\\d+(?:\\.\\d+)?)\\s*([a-z]+)\\s+(?:to|in)\\s+([a-z]+)\\s*$",
            Pattern.CASE_INSENSITIVE);

    static {
        // Length, base = metre.
        addUnit("m", "length", 1.0, "meter", "meters", "metre", "metres");
        addUnit("km", "length", 1000.0, "kilometer", "kilometers", "kilometre", "kilometres");
        addUnit("cm", "length", 0.01, "centimeter", "centimeters", "centimetre", "centimetres");
        addUnit("mm", "length", 0.001, "millimeter", "millimeters", "millimetre", "millimetres");
        addUnit("mi", "length", 1609.344, "mile", "miles");
        addUnit("ft", "length", 0.3048, "foot", "feet");
        addUnit("in", "length", 0.0254, "inch", "inches");
        addUnit("yd", "length", 0.9144, "yard", "yards");
        // Mass, base = kilogram.
        addUnit("kg", "mass", 1.0, "kilogram", "kilograms", "kgs");
        addUnit("g", "mass", 0.001, "gram", "grams");
        addUnit("lb", "mass", 0.45359237, "lbs", "pound", "pounds");
        addUnit("oz", "mass", 0.028349523125, "ounce", "ounces");
    }

    private SearchCalculator() {
    }

    private static void addUnit(String canonical, String dimension, double factor,
            String... aliases) {
        FACTOR.put(canonical, factor);
        DIMENSION.put(canonical, dimension);
        CANONICAL.put(canonical, canonical);
        for (String alias : aliases) {
            CANONICAL.put(alias, canonical);
        }
    }

    /**
     * Evaluates an arithmetic expression such as {@code "2 + 3 * (4 - 1)"} or {@code "2^10"}.
     *
     * @return the formatted result, or {@code null} if the query is not a valid expression
     */
    public static String evaluate(String query) {
        if (query == null) {
            return null;
        }
        String expression = query.trim();
        if (expression.isEmpty() || expression.length() > MAX_EXPRESSION_LENGTH) {
            return null;
        }
        // Only numbers, operators, parentheses and whitespace are allowed.
        if (!expression.matches("[0-9+\\-*/^%().\\s]+")) {
            return null;
        }
        // Require at least one operator so a bare number is not treated as a calculation.
        if (!expression.matches(".*[+\\-*/^%].*")) {
            return null;
        }
        try {
            Parser parser = new Parser(expression);
            BigDecimal value = parser.parse();
            return format(value);
        } catch (ArithmeticException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Converts a value between two units, e.g. {@code "5 km to mi"}.
     *
     * @return a formatted string such as {@code "5 km = 3.10686 mi"}, or {@code null}
     */
    public static String convert(String query) {
        if (query == null) {
            return null;
        }
        Matcher m = CONVERSION.matcher(query);
        if (!m.matches()) {
            return null;
        }
        String fromCanonical = CANONICAL.get(m.group(2).toLowerCase(Locale.ROOT));
        String toCanonical = CANONICAL.get(m.group(3).toLowerCase(Locale.ROOT));
        if (fromCanonical == null || toCanonical == null) {
            return null;
        }
        if (!DIMENSION.get(fromCanonical).equals(DIMENSION.get(toCanonical))) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        double base = value * FACTOR.get(fromCanonical);
        double result = base / FACTOR.get(toCanonical);
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            return null;
        }
        return m.group(1) + " " + fromCanonical + " = "
                + format(new BigDecimal(result).round(DISPLAY)) + " " + toCanonical;
    }

    private static String format(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            return stripped.toBigIntegerExact().toString();
        }
        return stripped.toPlainString();
    }

    /** Recursive-descent parser: expr := term (('+'|'-') term)*, etc. */
    private static final class Parser {
        private final String mInput;
        private int mPos;

        Parser(String input) {
            mInput = input.replaceAll("\\s+", "");
        }

        BigDecimal parse() {
            BigDecimal v = parseExpression();
            if (mPos != mInput.length()) {
                throw new IllegalArgumentException("trailing input");
            }
            return v;
        }

        private BigDecimal parseExpression() {
            BigDecimal v = parseTerm();
            while (mPos < mInput.length()) {
                char c = mInput.charAt(mPos);
                if (c == '+') {
                    mPos++;
                    v = v.add(parseTerm(), MATH);
                } else if (c == '-') {
                    mPos++;
                    v = v.subtract(parseTerm(), MATH);
                } else {
                    break;
                }
            }
            return v;
        }

        private BigDecimal parseTerm() {
            BigDecimal v = parseFactor();
            while (mPos < mInput.length()) {
                char c = mInput.charAt(mPos);
                if (c == '*') {
                    mPos++;
                    v = v.multiply(parseFactor(), MATH);
                } else if (c == '/') {
                    mPos++;
                    BigDecimal d = parseFactor();
                    if (d.signum() == 0) {
                        throw new ArithmeticException("divide by zero");
                    }
                    v = v.divide(d, MATH);
                } else if (c == '%') {
                    mPos++;
                    BigDecimal d = parseFactor();
                    if (d.signum() == 0) {
                        throw new ArithmeticException("modulo by zero");
                    }
                    v = v.remainder(d, MATH);
                } else {
                    break;
                }
            }
            return v;
        }

        private BigDecimal parseFactor() {
            BigDecimal base = parseUnary();
            if (mPos < mInput.length() && mInput.charAt(mPos) == '^') {
                mPos++;
                BigDecimal exponent = parseFactor();
                // Only integer exponents are supported, to keep the result exact enough.
                int n = exponent.intValueExact();
                if (n < 0) {
                    throw new IllegalArgumentException("negative exponent");
                }
                return base.pow(n, MATH);
            }
            return base;
        }

        private BigDecimal parseUnary() {
            if (mPos < mInput.length() && mInput.charAt(mPos) == '-') {
                mPos++;
                return parseUnary().negate();
            }
            if (mPos < mInput.length() && mInput.charAt(mPos) == '+') {
                mPos++;
                return parseUnary();
            }
            return parsePrimary();
        }

        private BigDecimal parsePrimary() {
            if (mPos < mInput.length() && mInput.charAt(mPos) == '(') {
                mPos++;
                BigDecimal v = parseExpression();
                if (mPos >= mInput.length() || mInput.charAt(mPos) != ')') {
                    throw new IllegalArgumentException("missing )");
                }
                mPos++;
                return v;
            }
            int start = mPos;
            while (mPos < mInput.length()
                    && (Character.isDigit(mInput.charAt(mPos)) || mInput.charAt(mPos) == '.')) {
                mPos++;
            }
            if (start == mPos) {
                throw new IllegalArgumentException("expected number");
            }
            return new BigDecimal(mInput.substring(start, mPos));
        }
    }
}
