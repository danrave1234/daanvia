package net.drave.atrdiagnostics;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.function.IntConsumer;
import static net.drave.atrdiagnostics.Gabi.Role.*;

/** The route picker owns its UI; HomeActivity only supplies state and selection actions. */
final class RouteChoicesView {
    final LinearLayout view;
    private final Gabi.Kit kit;
    private final IntConsumer choose;
    private List<RoutePlan> shown;
    private int selected = -1;
    private long paceKey;

    private final Gabi.Themer rows; private final Gabi.Kit rowKit;
    RouteChoicesView(Gabi.Kit kit, IntConsumer choose) {
        this.kit = kit; this.choose = choose; view = kit.column(0);
        // Rows are rebuilt for every new route; their colours follow a theme of their own, cleared each time, so the
        // app's theme list doesn't grow with every route preview.
        rows = new Gabi.Themer(kit.t.palette); kit.t.add(p -> rows.set(p)); rowKit = new Gabi.Kit(kit.c, rows);
        view.setPadding(0, kit.dp(8), 0, 0);
    }

    void render(List<RoutePlan> plans, int index, double pace, boolean busy) {
        view.setVisibility(plans.isEmpty() || busy ? View.GONE : View.VISIBLE);
        if (plans == shown && index == selected && paceKey == Math.round(pace * 100)) return;
        shown = plans; selected = index; paceKey = Math.round(pace * 100);
        view.removeAllViews(); rows.clear();
        if (plans.isEmpty()) return;
        TextView heading = rowKit.text(plans.size() == 1 ? "One route found" : "Choose your route", 13, 650, INK_SOFT);
        heading.setPadding(0, 0, 0, kit.dp(6)); view.addView(heading);
        double fastest = Double.MAX_VALUE;
        for (RoutePlan plan : plans) fastest = Math.min(fastest, plan.seconds);
        for (int i = 0; i < plans.size(); i++) {
            final int option = i; RoutePlan plan = plans.get(i);
            boolean picked = i == index;
            String title = (picked ? "✓  " : "") + "≈ " + Gabi.minutes(plan.seconds * pace) + " · " + Gabi.distance(plan.meters);
            String detail = (plan.via.isEmpty() ? "Route " + (i + 1) : "Via " + plan.via)
                + (plan.seconds <= fastest + 1 && plans.size() > 1 ? " · quickest estimate" : "")
                + (plan.ferry ? " · ferry" : "") + (plan.toll ? " · toll" : "");
            LinearLayout row = rowKit.column(0); row.setPadding(kit.dp(12), kit.dp(9), kit.dp(12), kit.dp(9));
            row.setMinimumHeight(kit.dp(56)); rowKit.fill(row, 12, picked ? ACCENT_SOFT : CHIP);
            row.addView(rowKit.text(title, 15, 700, picked ? ACCENT : INK));
            row.addView(rowKit.text(detail, 12, 500, INK_SOFT));
            row.setContentDescription((picked ? "Selected. " : "Choose ") + title.replace("✓", "") + ". " + detail);
            row.setSelected(picked); Gabi.press(row, () -> choose.accept(option));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = kit.dp(6);
            view.addView(row, lp);
        }
    }
}
