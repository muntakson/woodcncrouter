package org.dnaboy.grblsender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FluidNC's YAML config ($CD dump or config.yaml) as lines, read and edited by key path.
 * Paths are lower-case keys joined with '/', e.g. "axes/x/homing/positive_direction"; '*' matches any one key
 * ("axes/x/motor0/*\/direction_pin" finds the pin whatever the driver type is). Lines keep their indent and order,
 * so the edited text is the same file with only the changed values replaced.
 */
final class FluidConfig {

    private final List<String> lines = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();

    FluidConfig(List<String> raw) {
        List<int[]> stack = new ArrayList<>();          // {indent}, parallel to keys
        List<String> keys = new ArrayList<>();
        for (String l : raw) {
            lines.add(l);
            String t = l.trim();
            int indent = l.length() - l.replaceAll("^\\s+", "").length();
            int colon = t.indexOf(':');
            if (t.isEmpty() || t.startsWith("#") || colon <= 0) { paths.add(null); continue; }
            while (!stack.isEmpty() && stack.get(stack.size() - 1)[0] >= indent) {
                stack.remove(stack.size() - 1);
                keys.remove(keys.size() - 1);
            }
            String key = t.substring(0, colon).trim().toLowerCase(Locale.US);
            stack.add(new int[]{indent});
            keys.add(key);
            paths.add(String.join("/", keys));
        }
    }

    private int find(String path) {
        String[] want = path.toLowerCase(Locale.US).split("/");
        for (int i = 0; i < paths.size(); i++) {
            String p = paths.get(i);
            if (p == null) continue;
            String[] have = p.split("/");
            if (have.length != want.length) continue;
            boolean ok = true;
            for (int k = 0; k < want.length && ok; k++) ok = want[k].equals("*") || want[k].equals(have[k]);
            if (ok) return i;
        }
        return -1;
    }

    /** Value at path without quotes, or null when the key is missing. */
    String get(String path) {
        int i = find(path);
        if (i < 0) return null;
        String t = lines.get(i).trim();
        String v = t.substring(t.indexOf(':') + 1).trim();
        int hash = v.indexOf(" #");
        if (hash >= 0) v = v.substring(0, hash).trim();
        if (v.length() >= 2 && (v.startsWith("'") && v.endsWith("'") || v.startsWith("\"") && v.endsWith("\"")))
            v = v.substring(1, v.length() - 1);
        return v;
    }

    double num(String path, double def) {
        try { return Double.parseDouble(get(path)); } catch (Exception e) { return def; }
    }

    /** Replaces the value at path; false when the key is missing. */
    boolean set(String path, String value) {
        int i = find(path);
        if (i < 0) return false;
        String l = lines.get(i);
        int colon = l.indexOf(':');
        lines.set(i, l.substring(0, colon + 1) + " " + (value.contains(":") ? "'" + value + "'" : value));
        return true;
    }

    /** True when a pin spec ("I2SO.1:low", "gpio.33:low:pu") is active low, i.e. inverted. */
    static boolean pinLow(String pin) {
        return pin != null && (":" + pin.toLowerCase(Locale.US) + ":").contains(":low:");
    }

    /** The pin spec with ":low" added or removed; other attributes (":pu") stay. */
    static String pinWithLow(String pin, boolean low) {
        StringBuilder b = new StringBuilder();
        for (String part : pin.split(":")) {
            String p = part.toLowerCase(Locale.US);
            if (b.length() > 0 && (p.equals("low") || p.equals("high"))) continue;
            if (b.length() > 0) b.append(':');
            b.append(part);
            if (b.indexOf(":") < 0 && low) b.append(":low");
        }
        return b.toString();
    }

    String text() {
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        return b.toString();
    }
}
