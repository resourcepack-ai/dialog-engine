package ai.resourcepack.dialogs.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code if:} expressions. Small on purpose:
 *
 * <pre>
 *   {muted}                      true unless empty, false, 0, no, off, or unknown
 *   !{muted}
 *   {warnings} >= 3              numbers compare as numbers, anything else as text
 *   {rank} == admin && perm:server.staff
 *   {page} > 1 || {mode} != "quiet mode"
 * </pre>
 *
 * Operands are split BEFORE placeholders are filled, so a value with spaces in
 * it is still one operand.
 */
final class Conditions {

    private Conditions() {}

    static boolean test(String expression, Scope scope) {
        if (expression == null || expression.isBlank()) return true;
        try {
            Parser p = new Parser(tokenize(expression), scope);
            boolean result = p.or();
            return result;
        } catch (RuntimeException e) {
            scope.engine.warnOnce("Bad condition '" + expression + "' in " + scope.dialogId + ": " + e.getMessage());
            return false;
        }
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (s.startsWith("&&", i) || s.startsWith("||", i) || s.startsWith("==", i) || s.startsWith("!=", i)
                    || s.startsWith(">=", i) || s.startsWith("<=", i)) {
                out.add(s.substring(i, i + 2));
                i += 2;
            } else if (c == '>' || c == '<' || c == '!' || c == '(' || c == ')') {
                out.add(String.valueOf(c));
                i++;
            } else if (c == '"' || c == '\'') {
                int end = s.indexOf(c, i + 1);
                if (end < 0) end = s.length();
                out.add("\u0000" + s.substring(i + 1, end));
                i = end + 1;
            } else {
                int start = i;
                int depth = 0;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (d == '{') depth++;
                    else if (d == '}') depth = Math.max(0, depth - 1);
                    else if (depth == 0 && (Character.isWhitespace(d) || "&|=!<>()".indexOf(d) >= 0)) break;
                    i++;
                }
                out.add(s.substring(start, i));
            }
        }
        return out;
    }

    private static final class Parser {
        private final List<String> tokens;
        private final Scope scope;
        private int pos;

        Parser(List<String> tokens, Scope scope) {
            this.tokens = tokens;
            this.scope = scope;
        }

        boolean or() {
            boolean v = and();
            while (peek("||")) {
                pos++;
                boolean r = and();
                v = v || r;
            }
            return v;
        }

        boolean and() {
            boolean v = unary();
            while (peek("&&")) {
                pos++;
                boolean r = unary();
                v = v && r;
            }
            return v;
        }

        boolean unary() {
            if (peek("!")) {
                pos++;
                return !unary();
            }
            if (peek("(")) {
                pos++;
                boolean v = or();
                if (peek(")")) pos++;
                return v;
            }
            String left = operand();
            if (pos < tokens.size()) {
                String op = tokens.get(pos);
                if (op.equals("==") || op.equals("!=") || op.equals(">") || op.equals("<") || op.equals(">=") || op.equals("<=")) {
                    pos++;
                    String right = operand();
                    return compare(left, op, right);
                }
            }
            return truthy(left);
        }

        private String operand() {
            if (pos >= tokens.size()) throw new IllegalArgumentException("ends too early");
            String t = tokens.get(pos++);
            if (t.startsWith("\u0000")) return Text.fill(t.substring(1), scope, Text.Mode.RAW);
            if (t.startsWith("perm:")) return String.valueOf(scope.viewer.hasPermission(t.substring(5)));
            return Text.fill(t, scope, Text.Mode.RAW);
        }

        private boolean peek(String t) {
            return pos < tokens.size() && tokens.get(pos).equals(t);
        }
    }

    static boolean truthy(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off") || s.equals("null")) return false;
        // A placeholder nothing could fill is not "true".
        return !(s.startsWith("{") && s.endsWith("}"));
    }

    private static boolean compare(String a, String op, String b) {
        Double x = number(a);
        Double y = number(b);
        int cmp;
        if (x != null && y != null) cmp = Double.compare(x, y);
        else cmp = a.trim().compareToIgnoreCase(b.trim());
        return switch (op) {
            case "==" -> cmp == 0;
            case "!=" -> cmp != 0;
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case ">=" -> cmp >= 0;
            default -> cmp <= 0;
        };
    }

    private static Double number(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
