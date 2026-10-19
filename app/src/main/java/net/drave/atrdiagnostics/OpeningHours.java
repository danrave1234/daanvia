package net.drave.atrdiagnostics;

import java.util.*;

/**
 * Reads the common forms of the OpenStreetMap opening_hours tag ("24/7", "Mo-Fr 08:00-20:00; Sa,Su 10:00-22:00",
 * "08:00-12:00,13:00-17:00", "Su off", ranges past midnight). Later rules override earlier ones for the days they
 * name, as in OSM. Anything it cannot read is reported as unknown rather than guessed.
 */
public final class OpeningHours {
    public enum State { OPEN, CLOSED, UNKNOWN }
    public static final class Status {
        public final State state; public final String label;
        Status(State s, String l) { state = s; label = l; }
    }
    private static final String[] DAYS = {"mo", "tu", "we", "th", "fr", "sa", "su"};
    private OpeningHours() {}

    /** @param day 0 = Monday … 6 = Sunday; minute = minutes since midnight. */
    public static Status at(String tag, int day, int minute) {
        if (tag == null || tag.trim().isEmpty()) return new Status(State.UNKNOWN, "Hours not listed");
        String t = tag.trim().toLowerCase(Locale.ROOT);
        if (t.equals("24/7") || t.equals("mo-su 00:00-24:00") || t.equals("00:00-24:00")) return new Status(State.OPEN, "Open 24 hours");
        List<int[]>[] week = parse(t);
        if (week == null) return new Status(State.UNKNOWN, "Hours: " + tag.trim());
        // open now, including a range carried over from yesterday past midnight
        for (int[] r : week[day]) if (minute >= r[0] && minute < Math.min(r[1], 1440)) return new Status(State.OPEN, r[1] >= 1440 && r[0] == 0 ? "Open 24 hours" : "Open until " + clock(r[1] % 1440));
        for (int[] r : week[(day + 6) % 7]) if (r[1] > 1440 && minute < r[1] - 1440) return new Status(State.OPEN, "Open until " + clock(r[1] - 1440));
        for (int ahead = 0; ahead < 7; ahead++) {
            int d = (day + ahead) % 7; int best = Integer.MAX_VALUE;
            for (int[] r : week[d]) if (ahead > 0 || r[0] > minute) best = Math.min(best, r[0]);
            if (best != Integer.MAX_VALUE) return new Status(State.CLOSED, "Closed · opens " + (ahead == 0 ? "" : ahead == 1 ? "tomorrow " : cap(DAYS[d]) + " ") + clock(best));
        }
        return new Status(State.CLOSED, "Closed");
    }
    public static Status now(String tag) {
        Calendar c = Calendar.getInstance();
        return at(tag, (c.get(Calendar.DAY_OF_WEEK) + 5) % 7, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE));
    }

    @SuppressWarnings("unchecked")
    static List<int[]>[] parse(String t) {
        List<int[]>[] week = new List[7]; for (int i = 0; i < 7; i++) week[i] = new ArrayList<>();
        for (String rule : t.split(";")) {
            rule = rule.trim(); if (rule.isEmpty()) continue;
            if (rule.startsWith("ph") || rule.startsWith("sh")) continue;             // holidays: not modelled, ignore
            String days = null, times = rule;
            int sp = rule.indexOf(' ');
            if (Character.isLetter(rule.charAt(0))) { if (sp < 0) { days = rule; times = ""; } else { days = rule.substring(0, sp); times = rule.substring(sp + 1).trim(); } }
            boolean[] on = days == null ? all() : days(days);
            if (on == null) return null;
            List<int[]> ranges = new ArrayList<>();
            if (!(times.equals("off") || times.equals("closed"))) {
                if (times.isEmpty()) return null;
                for (String r : times.split(",")) { int[] range = range(r.trim()); if (range == null) return null; ranges.add(range); }
            }
            for (int d = 0; d < 7; d++) if (on[d]) week[d] = new ArrayList<>(ranges);
        }
        return week;
    }
    private static boolean[] all() { boolean[] b = new boolean[7]; Arrays.fill(b, true); return b; }
    private static boolean[] days(String s) {
        boolean[] on = new boolean[7];
        for (String part : s.split(",")) {
            part = part.trim(); if (part.startsWith("ph")) continue;
            String[] ab = part.split("-");
            int a = day(ab[0]), b = ab.length > 1 ? day(ab[1]) : a;
            if (a < 0 || b < 0) return null;
            for (int d = a; ; d = (d + 1) % 7) { on[d] = true; if (d == b) break; }
        }
        return on;
    }
    private static int day(String s) { for (int i = 0; i < 7; i++) if (DAYS[i].equals(s.trim())) return i; return -1; }
    private static int[] range(String r) {
        String[] ab = r.split("-"); if (ab.length != 2) return null;
        int a = minutes(ab[0]), b = minutes(ab[1].replace("+", ""));
        if (a < 0 || b < 0) return null;
        if (b <= a) b += 1440;                                                       // runs past midnight
        return new int[]{a, b};
    }
    private static int minutes(String s) {
        String[] hm = s.trim().split(":"); if (hm.length != 2) return -1;
        try { int h = Integer.parseInt(hm[0]), m = Integer.parseInt(hm[1]); return h >= 0 && h <= 24 && m >= 0 && m < 60 ? h * 60 + m : -1; } catch (NumberFormatException e) { return -1; }
    }
    static String clock(int minute) {
        int h = (minute / 60) % 24, m = minute % 60;
        String ap = h < 12 ? "AM" : "PM"; int h12 = h % 12 == 0 ? 12 : h % 12;
        return m == 0 ? h12 + " " + ap : String.format(Locale.US, "%d:%02d %s", h12, m, ap);
    }
    private static String cap(String d) { return Character.toUpperCase(d.charAt(0)) + d.substring(1); }
}
