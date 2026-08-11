package net.tref.xraytunnel;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AppSelectionActivity extends Activity {
    private final List<AppEntry> entries = new ArrayList<>();
    private final List<AppEntry> filteredEntries = new ArrayList<>();
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadApplications();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), 0);

        TextView title = new TextView(this);
        title.setText(R.string.select_apps_title);
        title.setTextSize(22);
        title.setTextColor(Color.rgb(23, 26, 31));
        root.addView(title, fullWidthWrapContent());

        TextView hint = new TextView(this);
        hint.setText(R.string.select_apps_hint);
        hint.setTextSize(14);
        hint.setTextColor(Color.rgb(111, 118, 130));
        hint.setPadding(0, dp(6), 0, dp(10));
        root.addView(hint, fullWidthWrapContent());

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setHint(R.string.select_apps_search_hint);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        search.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        searchParams.setMargins(0, 0, 0, dp(8));
        root.addView(search, searchParams);

        ListView list = new ListView(this);
        adapter = new AppAdapter(this);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(0, dp(10), 0, dp(12));

        Button done = new Button(this);
        done.setText(R.string.select_apps_save);
        done.setAllCaps(false);
        done.setOnClickListener(v -> saveAndFinish());
        footer.addView(done, fullWidthWrapContent());
        root.addView(footer, fullWidthWrapContent());

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                // No-op.
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                filterEntries(text);
            }

            @Override
            public void afterTextChanged(Editable text) {
                // No-op.
            }
        });

        setContentView(root);
        applyBottomInset(footer, dp(12));
        filterEntries("");
    }

    private void loadApplications() {
        Set<String> selected = TunnelSettings.allowedApplications(this);
        PackageManager packageManager = getPackageManager();
        for (ApplicationInfo info : packageManager.getInstalledApplications(
                PackageManager.GET_META_DATA)) {
            if (getPackageName().equals(info.packageName)
                    || packageManager.checkPermission(
                            Manifest.permission.INTERNET,
                            info.packageName) != PackageManager.PERMISSION_GRANTED) {
                continue;
            }
            String label = info.loadLabel(packageManager).toString();
            Drawable icon = info.loadIcon(packageManager);
            entries.add(new AppEntry(info.packageName, label, icon, selected.contains(info.packageName)));
        }
        Collections.sort(entries, Comparator
                .comparing((AppEntry entry) -> !entry.selected)
                .thenComparing(entry -> entry.label.toLowerCase(Locale.ROOT)));
    }

    private void filterEntries(CharSequence query) {
        String normalized = query == null
                ? ""
                : query.toString().trim().toLowerCase(Locale.ROOT);
        filteredEntries.clear();
        for (AppEntry entry : entries) {
            if (normalized.isEmpty()
                    || entry.label.toLowerCase(Locale.ROOT).contains(normalized)) {
                filteredEntries.add(entry);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void saveAndFinish() {
        Set<String> selected = new HashSet<>();
        for (AppEntry entry : entries) {
            if (entry.selected) {
                selected.add(entry.packageName);
            }
        }
        TunnelSettings.saveAllowedApplications(this, selected);
        finish();
    }

    private LinearLayout.LayoutParams fullWidthWrapContent() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void applyBottomInset(View footer, int baseBottomPadding) {
        footer.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottomInset;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                bottomInset = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime()).bottom;
            } else {
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(
                    view.getPaddingLeft(),
                    view.getPaddingTop(),
                    view.getPaddingRight(),
                    baseBottomPadding + bottomInset);
            return insets;
        });
        footer.post(footer::requestApplyInsets);
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;
        boolean selected;

        AppEntry(String packageName, String label, Drawable icon, boolean selected) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.selected = selected;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        private final Context context;

        AppAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getCount() {
            return filteredEntries.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return filteredEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppEntry entry = getItem(position);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));

            ImageView icon = new ImageView(context);
            icon.setImageDrawable(entry.icon);
            row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

            TextView label = new TextView(context);
            label.setText(entry.label);
            label.setTextSize(16);
            label.setTextColor(Color.rgb(23, 26, 31));
            label.setPadding(dp(12), 0, dp(8), 0);
            row.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1));

            CheckBox checkbox = new CheckBox(context);
            checkbox.setChecked(entry.selected);
            checkbox.setOnCheckedChangeListener((button, checked) -> entry.selected = checked);
            row.addView(checkbox, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
            row.setOnClickListener(v -> checkbox.setChecked(!checkbox.isChecked()));
            return row;
        }
    }
}
