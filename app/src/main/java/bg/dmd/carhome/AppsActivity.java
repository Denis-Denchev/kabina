package bg.dmd.carhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Plain grid of every launchable app — the "hidden" menu behind the logo. */
public class AppsActivity extends Activity {
    private List<ResolveInfo> apps;
    private PackageManager pm;

    private void askExitKiosk() {
        final android.content.SharedPreferences sp = getSharedPreferences("carhome", MODE_PRIVATE);
        final boolean service = sp.getBoolean("serviceMode", false);
        new android.app.AlertDialog.Builder(this)
                .setTitle(service ? R.string.service_back_title : R.string.service_title)
                .setMessage(service ? R.string.service_back_msg : R.string.service_msg)
                .setPositiveButton(R.string.yes, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        sp.edit().putBoolean("serviceMode", !service).apply();
                        MainActivity.setCarMode(AppsActivity.this, service);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density * Theme.scale); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        apps = pm.queryIntentActivities(q, 0);
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo c) {
                return String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(c.loadLabel(pm)));
            }
        });

        GridView g = new GridView(this);
        g.setNumColumns(GridView.AUTO_FIT);
        g.setColumnWidth(dp(130));
        g.setVerticalSpacing(dp(18));
        g.setPadding(dp(32), dp(32), dp(32), dp(32));
        g.setClipToPadding(false);
        g.setBackgroundColor(0);
        g.setAdapter(new BaseAdapter() {
            public int getCount() { return apps.size(); }
            public Object getItem(int i) { return apps.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup parent) {
                LinearLayout cell = new LinearLayout(AppsActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView iv = new ImageView(AppsActivity.this);
                iv.setImageDrawable(apps.get(i).loadIcon(pm));
                cell.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
                TextView tv = new TextView(AppsActivity.this);
                tv.setText(apps.get(i).loadLabel(pm));
                tv.setTextColor(Theme.TEXT);
                tv.setTextSize(14 * Theme.scale);
                tv.setTypeface(Theme.font("sans-serif-light"));
                tv.setGravity(Gravity.CENTER);
                tv.setSingleLine(true);
                tv.setPadding(0, dp(8), 0, 0);
                cell.addView(tv);
                return cell;
            }
        });
        g.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int i, long id) {
                Intent li = pm.getLaunchIntentForPackage(apps.get(i).activityInfo.packageName);
                if (li != null) startActivity(li);
            }
        });
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Theme.page());
        TextView head = new TextView(this);
        head.setText(R.string.all_apps);
        head.setTextColor(Theme.DIM);
        head.setTextSize(15 * Theme.scale);
        head.setLetterSpacing(0.3f);
        head.setTypeface(Theme.font("sans-serif-light"));
        head.setPadding(dp(40), dp(28), dp(40), 0);
        // Service exit: hold the title to leave car (kiosk) mode.
        head.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { askExitKiosk(); return true; }
        });
        page.addView(head);
        page.addView(g, new LinearLayout.LayoutParams(-1, 0, 1));
        // Authorship (GPL-3.0 section 7(b) notice) and any credit the brand pack's assets require.
        String credit = getString(R.string.app_name) + "  ·  " + getString(R.string.author_credit);
        if (getString(R.string.model_credit).length() > 0) credit += "\n" + getString(R.string.model_credit);
        TextView foot = new TextView(this);
        foot.setText(credit);
        foot.setTextColor(Theme.FAINT);
        foot.setTextSize(11 * Theme.scale);
        foot.setTypeface(Theme.font("sans-serif-light"));
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(16), dp(6), dp(16), dp(14));
        page.addView(foot);
        setContentView(page);
    }
}
