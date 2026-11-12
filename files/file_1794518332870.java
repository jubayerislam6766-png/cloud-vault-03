package com.example.ejitor;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class editor_3 implements EditorActivity.EditorModule {

    public static editor_3 instance;
    private EditorActivity activity;

    private final ExecutorService selectionExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger selectionTaskCounter = new AtomicInteger(0);

    // স্মার্ট অবজেক্ট প্রিভিয়াস আন-ওয়ার্পড অরিজিনাল বিটম্যাপ ক্যাশ
    public static final Map<String, Bitmap> smartOriginalBitmapsById = new HashMap<>();

    // ওর্প মেশ এবং ট্রান্সফর্ম ডেটা ক্যাশ
    public static final Map<String, float[]> smartLayerWarpMeshCache = new HashMap<>();
    public static final Map<String, Integer> smartLayerMeshColsCache = new HashMap<>();
    public static final Map<String, Integer> smartLayerMeshRowsCache = new HashMap<>();

    // স্মার্ট অবজেক্ট ফেড মাস্ক ক্যাশ
    public static final Map<String, Bitmap> smartLayerFadeMaskCache = new HashMap<>();

    // গ্রুপ ট্র্যাকিং ডেটা (GroupId -> IsExpanded)
    public static final Map<String, Boolean> groupExpandedState = new HashMap<>();
    // LayerId -> ParentGroupId
    public static final Map<String, String> layerParentGroupMap = new HashMap<>();

    // ট্র্যাক করার জন্য সর্বশেষ অ্যাক্টিভ ফোল্ডার আইডি ও পরিচিত লেয়ার
    public static String lastActiveGroupId = null;
    private final Set<String> knownLayerIds = new HashSet<>();

    // সিলেকশন ট্রান্সফর্ম সিঙ্কিং
    private static Path layerLocalSelectionPath = null;
    private static EditorActivity.LayerItem trackedSelectionLayer = null;
    private static float lastLayerX = 0f, lastLayerY = 0f;
    private static float lastLayerScaleX = 1f, lastLayerScaleY = 1f;

    @Override
    public void init(EditorActivity editorActivity) {
        this.activity = editorActivity;
        instance = this;
        hookTopSelectMenu();
        hookLayersDockForSmartObjects();
        hookOpacityControl();
        hookCanvasTouchForSelectionSync();
        hookBottomBarButtons();
    }

    @Override
    public void onCommand(String command, Object... args) {
        if ("recordWarpMesh".equalsIgnoreCase(command) && args.length >= 4) {
            try {
                String layerId = (String) args[0];
                int cols = (Integer) args[1];
                int rows = (Integer) args[2];
                float[] mesh = (float[]) args[3];
                smartLayerWarpMeshCache.put(layerId, mesh.clone());
                smartLayerMeshColsCache.put(layerId, cols);
                smartLayerMeshRowsCache.put(layerId, rows);
            } catch (Exception ignored) {}
        }
    }

    private int dp(float value) {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, dm);
    }

    private void setEditorSelectionPath(Path path) {
        if (activity == null) return;
        try {
            Field fPath = EditorActivity.class.getDeclaredField("activeSelectionPath");
            fPath.setAccessible(true);
            fPath.set(activity, path);

            Field fBounds = EditorActivity.class.getDeclaredField("selectionBounds");
            fBounds.setAccessible(true);
            if (path != null) {
                RectF r = new RectF();
                path.computeBounds(r, true);
                fBounds.set(activity, r);
            } else {
                fBounds.set(activity, null);
            }
        } catch (Exception ignored) {}
    }

    private Path getEditorSelectionPath() {
        if (activity == null) return null;
        try {
            Field fPath = EditorActivity.class.getDeclaredField("activeSelectionPath");
            fPath.setAccessible(true);
            return (Path) fPath.get(activity);
        } catch (Exception e) {
            return null;
        }
    }

    private void invokePrivateVoidMethod(String methodName) {
        if (activity == null) return;
        try {
            Method m = EditorActivity.class.getDeclaredMethod(methodName);
            m.setAccessible(true);
            m.invoke(activity);
        } catch (Exception ignored) {}
    }

    // ১. মেনুবারে Select Pixels ও আনডু-সমর্থিত Clear Option
    private void hookTopSelectMenu() {
        if (activity == null || activity.menuContainer == null) return;

        for (int i = 0; i < activity.menuContainer.getChildCount(); i++) {
            View child = activity.menuContainer.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                if ("Select".equalsIgnoreCase(tv.getText().toString().trim())) {
                    tv.setOnClickListener(this::showFullSelectMenu);
                    break;
                }
            }
        }
    }

    private void showFullSelectMenu(View anchor) {
        final PopupWindow popup = new PopupWindow(activity);
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(8), dp(6), dp(8), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1C1C1C"));
        bg.setStroke(dp(1), Color.parseColor("#333333"));
        bg.setCornerRadius(dp(4));
        l.setBackground(bg);

        String[] items = {
                "Select All",
                "Fill Selection (Color)",
                "Clear",
                "Inverse",
                "Deselect",
                "Select Pixels"
        };

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView itemTv = new TextView(activity);
            itemTv.setText(items[i]);
            itemTv.setTextColor(Color.WHITE);
            itemTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            itemTv.setPadding(dp(12), dp(8), dp(12), dp(8));

            itemTv.setOnClickListener(v -> {
                popup.dismiss();
                EditorActivity.ProjectTabSession s = activity.getCurrentSession();
                if (s == null) return;

                if (idx == 0) { 
                    float totalW = s.artboardCount * s.canvasWidth + (s.artboardCount - 1) * dp(16f);
                    Path p = new Path();
                    p.addRect(0, 0, totalW, s.canvasHeight, Path.Direction.CW);
                    setEditorSelectionPath(p);
                    trackedSelectionLayer = null;
                    layerLocalSelectionPath = null;
                    activity.recordHistory();
                    activity.canvasView.invalidate();
                } else if (idx == 1) { 
                    try {
                        Field fgF = EditorActivity.class.getDeclaredField("fgColor");
                        fgF.setAccessible(true);
                        int fg = fgF.getInt(activity);
                        activity.fillActiveSelectionWithColor(fg);
                    } catch (Exception ignored) {}
                } else if (idx == 2) { 
                    executeClearAction(s);
                } else if (idx == 3) { 
                    Path cur = getEditorSelectionPath();
                    float totalW = s.artboardCount * s.canvasWidth + (s.artboardCount - 1) * dp(16f);
                    Path full = new Path();
                    full.addRect(0, 0, totalW, s.canvasHeight, Path.Direction.CW);
                    if (cur == null) {
                        setEditorSelectionPath(full);
                    } else {
                        Path inv = new Path();
                        inv.op(full, cur, Path.Op.DIFFERENCE);
                        setEditorSelectionPath(inv);
                    }
                    trackedSelectionLayer = null;
                    layerLocalSelectionPath = null;
                    activity.recordHistory();
                    activity.canvasView.invalidate();
                } else if (idx == 4) { 
                    activity.canvasView.clearLassoSelection();
                    trackedSelectionLayer = null;
                    layerLocalSelectionPath = null;
                } else if (idx == 5) { 
                    if (s.activeLayerIndex >= 0 && s.activeLayerIndex < s.layers.size()) {
                        showSelectPixelsDialog(s, s.layers.get(s.activeLayerIndex));
                    } else {
                        Toast.makeText(activity, "Please select a layer first", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            l.addView(itemTv);
        }

        popup.setContentView(l);
        popup.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setFocusable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    private void executeClearAction(EditorActivity.ProjectTabSession s) {
        if (s == null || s.activeLayerIndex < 0 || s.activeLayerIndex >= s.layers.size()) {
            Toast.makeText(activity, "No active layer to clear!", Toast.LENGTH_SHORT).show();
            return;
        }

        EditorActivity.LayerItem activeLayer = s.layers.get(s.activeLayerIndex);
        if (activeLayer == null || activeLayer.bitmap == null || activeLayer.bitmap.isRecycled()) {
            Toast.makeText(activity, "Layer has no image data!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (activeLayer.isLocked) {
            Toast.makeText(activity, "Layer is locked!", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            activeLayer.bitmap = activeLayer.bitmap.copy(Bitmap.Config.ARGB_8888, true);

            Path selPath = getEditorSelectionPath();
            if (selPath != null) {
                Matrix layerMatrix = new Matrix();
                layerMatrix.postScale(activeLayer.scaleX, activeLayer.scaleY);
                layerMatrix.postRotate(activeLayer.rotation,
                        (activeLayer.bitmap.getWidth() * activeLayer.scaleX) / 2f,
                        (activeLayer.bitmap.getHeight() * activeLayer.scaleY) / 2f);
                layerMatrix.postTranslate(activeLayer.x, activeLayer.y);

                Matrix invMatrix = new Matrix();
                layerMatrix.invert(invMatrix);

                Path localPath = new Path();
                selPath.transform(invMatrix, localPath);

                Canvas c = new Canvas(activeLayer.bitmap);
                Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                c.drawPath(localPath, clearPaint);
            } else {
                activeLayer.bitmap.eraseColor(Color.TRANSPARENT);
            }

            activeLayer.isShape = false;
            activeLayer.isBlank = false;
            activeLayer.isDirty = true;

            activity.recordHistory();
            invokePrivateVoidMethod("updateLayersDockUI");
            if (activity.canvasView != null) activity.canvasView.invalidate();
            activity.saveProjectToDiskImmediate(s);

            Toast.makeText(activity, "Cleared", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(activity, "Clear failed: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ২. অপাসিটি কন্ট্রোল
    private TextView findOpacityTextView() {
        if (activity == null || activity.rightLayersDock == null) return null;
        if (activity.rightLayersDock.getChildCount() > 1) {
            View child = activity.rightLayersDock.getChildAt(1);
            if (child instanceof LinearLayout) {
                LinearLayout blendRow = (LinearLayout) child;
                for (int i = 0; i < blendRow.getChildCount(); i++) {
                    View v = blendRow.getChildAt(i);
                    if (v instanceof TextView) {
                        TextView tv = (TextView) v;
                        if (tv.getText().toString().contains("Op:")) return tv;
                    }
                }
            }
        }
        return null;
    }

    private void hookOpacityControl() {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            final TextView opTv = findOpacityTextView();
            if (opTv == null) return;

            opTv.setPadding(dp(6), dp(3), dp(6), dp(3));
            GradientDrawable opBg = new GradientDrawable();
            opBg.setColor(Color.parseColor("#121212"));
            opBg.setStroke(dp(1), Color.parseColor("#333333"));
            opBg.setCornerRadius(dp(3));
            opTv.setBackground(opBg);

            opTv.setOnTouchListener(new View.OnTouchListener() {
                float startX;
                float initialOp = 1f;
                boolean isDragging = false;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    EditorActivity.ProjectTabSession s = activity.getCurrentSession();
                    if (s == null || s.activeLayerIndex < 0 || s.activeLayerIndex >= s.layers.size()) return false;
                    EditorActivity.LayerItem al = s.layers.get(s.activeLayerIndex);

                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            startX = event.getRawX();
                            initialOp = al.opacity;
                            isDragging = false;
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            float dX = event.getRawX() - startX;
                            if (Math.abs(dX) > dp(3) || isDragging) {
                                isDragging = true;
                                float newOp = Math.max(0.0f, Math.min(1.0f, initialOp + (dX / dp(110f))));
                                al.opacity = newOp;
                                opTv.setText(" Op: " + Math.round(newOp * 100) + "%");
                                if (activity.canvasView != null) activity.canvasView.invalidate();
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            if (!isDragging) {
                                showOpacityInputDialog(opTv, al);
                            } else {
                                activity.recordHistory();
                            }
                            isDragging = false;
                            return true;
                    }
                    return false;
                }
            });
        });
    }

    private void showOpacityInputDialog(final TextView opTv, final EditorActivity.LayerItem layer) {
        final Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1C1C1C"));
        bg.setStroke(dp(1), Color.parseColor("#333333"));
        bg.setCornerRadius(dp(12));
        box.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText("Layer Opacity");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView lbl = new TextView(activity);
        lbl.setText("Enter Opacity (0-100%): ");
        lbl.setTextColor(Color.parseColor("#AAAAAA"));
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        row.addView(lbl);

        final EditText et = new EditText(activity);
        et.setText(String.valueOf(Math.round(layer.opacity * 100)));
        et.setTextColor(Color.WHITE);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setGravity(Gravity.CENTER);
        et.setLayoutParams(new LinearLayout.LayoutParams(dp(50), dp(28)));

        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(Color.parseColor("#121212"));
        etBg.setStroke(dp(1), Color.parseColor("#2C2C2C"));
        etBg.setCornerRadius(dp(3));
        et.setBackground(etBg);
        row.addView(et);
        box.addView(row);

        final SeekBar sb = new SeekBar(activity);
        sb.setMax(100);
        sb.setProgress(Math.round(layer.opacity * 100));
        applyCustomSeekBarStyle(sb);
        box.addView(sb);

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    et.setText(String.valueOf(progress));
                    layer.opacity = progress / 100f;
                    opTv.setText(" Op: " + progress + "%");
                    if (activity.canvasView != null) activity.canvasView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s1, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence cs, int start, int before, int count) {
                try {
                    int val = Integer.parseInt(cs.toString().trim());
                    val = Math.max(0, Math.min(100, val));
                    layer.opacity = val / 100f;
                    sb.setProgress(val);
                    opTv.setText(" Op: " + val + "%");
                    if (activity.canvasView != null) activity.canvasView.invalidate();
                } catch (Exception ignored) {}
            }
            @Override public void afterTextChanged(Editable s1) {}
        });

        Button ok = new Button(activity);
        ok.setText("APPLY");
        ok.setTextColor(Color.WHITE);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        ok.setTypeface(null, Typeface.BOLD);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(Color.parseColor("#FF1238"));
        okBg.setCornerRadius(dp(16));
        ok.setBackground(okBg);
        ok.setLayoutParams(new LinearLayout.LayoutParams(dp(80), dp(34)));
        ok.setOnClickListener(v -> {
            activity.recordHistory();
            d.dismiss();
        });
        box.addView(ok);

        d.setContentView(box);
        d.show();
    }

    // ৩. স্মার্ট অবজেক্ট ও লেয়ার ডক
    private LinearLayout findLayersListContainer() {
        if (activity == null || activity.rightLayersDock == null) return null;
        for (int i = 0; i < activity.rightLayersDock.getChildCount(); i++) {
            View child = activity.rightLayersDock.getChildAt(i);
            if (child instanceof ScrollView) {
                ScrollView sv = (ScrollView) child;
                if (sv.getChildCount() > 0 && sv.getChildAt(0) instanceof LinearLayout) {
                    return (LinearLayout) sv.getChildAt(0);
                }
            }
        }
        return null;
    }

    // নিচের বারের বাটনগুলো সঠিকভাবে হুক করা (যাতে লাফালাফি না করে ও ডিলিট ঠিকমতো হয়)
    private void hookBottomBarButtons() {
        if (activity == null || activity.rightLayersDock == null) return;
        activity.runOnUiThread(() -> {
            try {
                ViewGroup bottomBar = null;
                for (int i = activity.rightLayersDock.getChildCount() - 1; i >= 0; i--) {
                    View child = activity.rightLayersDock.getChildAt(i);
                    if (child instanceof LinearLayout && !(child instanceof ScrollView)) {
                        bottomBar = (ViewGroup) child;
                        break;
                    }
                }
                if (bottomBar == null) return;

                // নতুন ফোল্ডার তৈরি বাটন
                if (bottomBar.findViewWithTag("btn_create_folder_tag") == null) {
                    int plusIndex = 0;
                    for (int i = 0; i < bottomBar.getChildCount(); i++) {
                        View v = bottomBar.getChildAt(i);
                        if (v instanceof TextView && "+".equals(((TextView) v).getText().toString().trim())) {
                            plusIndex = i;
                            break;
                        } else if (v instanceof ImageView) {
                            plusIndex = i;
                        }
                    }

                    ImageView newFolderBtn = new ImageView(activity);
                    newFolderBtn.setTag("btn_create_folder_tag");
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
                    lp.setMargins(dp(6), 0, dp(6), 0);
                    newFolderBtn.setLayoutParams(lp);
                    newFolderBtn.setImageDrawable(createVectorFolderIcon());
                    newFolderBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    newFolderBtn.setOnClickListener(v -> {
                        EditorActivity.ProjectTabSession s = activity.getCurrentSession();
                        if (s != null) {
                            createLayerGroup(s, s.activeLayerIndex >= 0 ? s.activeLayerIndex : 0);
                        }
                    });

                    bottomBar.addView(newFolderBtn, Math.max(0, plusIndex));
                }

                // প্লাস (+) বাটন হুক করা: ফোল্ডারের ভেতর থাকা অবস্থায় নতুন লেয়ার সরাসরি ফোল্ডারেই তৈরি হবে
                ImageView plusBtn = null;
                for (int i = 0; i < bottomBar.getChildCount(); i++) {
                    View v = bottomBar.getChildAt(i);
                    if (v instanceof ImageView && !"btn_create_folder_tag".equals(v.getTag())) {
                        plusBtn = (ImageView) v;
                        break;
                    }
                }
                if (plusBtn != null && !"hooked_plus_btn".equals(plusBtn.getTag())) {
                    plusBtn.setTag("hooked_plus_btn");
                    plusBtn.setOnClickListener(v -> createNewLayerSmoothlyInsideFolder());
                }

                // ট্র্যাশ (Trash) বাটন হুক করা: ফোল্ডার ডিলিট করলে ভেতরের সমস্ত লেয়ার সহ ডিলিট হবে
                ImageView trashBtn = null;
                for (int i = bottomBar.getChildCount() - 1; i >= 0; i--) {
                    View v = bottomBar.getChildAt(i);
                    if (v instanceof ImageView && !"btn_create_folder_tag".equals(v.getTag()) && v != plusBtn) {
                        trashBtn = (ImageView) v;
                        break;
                    }
                }
                if (trashBtn != null && !"hooked_trash_btn".equals(trashBtn.getTag())) {
                    trashBtn.setTag("hooked_trash_btn");
                    trashBtn.setOnClickListener(v -> {
                        EditorActivity.ProjectTabSession s = activity.getCurrentSession();
                        if (s == null || s.activeLayerIndex < 0 || s.activeLayerIndex >= s.layers.size()) return;
                        EditorActivity.LayerItem activeL = s.layers.get(s.activeLayerIndex);
                        if (activeL.name != null && activeL.name.startsWith("[Group] ")) {
                            deleteFolderWithChildren(s, activeL);
                        } else {
                            invokePrivateVoidMethod("deleteActiveLayer");
                        }
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    // ফোল্ডারের মধ্যে কোনো প্রকার জাম্প বা লাফালাফি ছাড়া সরাসরি শান্তভাবে লেয়ার তৈরি করা
    private void createNewLayerSmoothlyInsideFolder() {
        EditorActivity.ProjectTabSession s = activity.getCurrentSession();
        if (s == null) return;

        Bitmap b = Bitmap.createBitmap(s.canvasWidth, s.canvasHeight, Bitmap.Config.ARGB_8888);
        float abOffset = s.currentActiveArtboard * (s.canvasWidth + dp(16f));
        EditorActivity.LayerItem nl = new EditorActivity.LayerItem("Layer " + (s.layers.size() + 1), b, abOffset, 0, false);
        nl.isBlank = true;
        knownLayerIds.add(nl.id);

        EditorActivity.LayerItem targetFolder = findFolderForActiveLayer(s);
        if (targetFolder != null) {
            int fIdx = s.layers.indexOf(targetFolder);
            // সরাসরি ফোল্ডারের ঠিক নিচে ইনসার্ট করা
            s.layers.add(fIdx, nl);
            s.activeLayerIndex = fIdx;
            layerParentGroupMap.put(nl.id, targetFolder.id);
            groupExpandedState.put(targetFolder.id, true);
        } else {
            s.layers.add(nl);
            s.activeLayerIndex = s.layers.size() - 1;
        }

        activity.recordHistory();
        invokePrivateVoidMethod("updateLayersDockUI");
        if (activity.canvasView != null) activity.canvasView.invalidate();
    }

    private EditorActivity.LayerItem findFolderForActiveLayer(EditorActivity.ProjectTabSession s) {
        if (s == null || s.activeLayerIndex < 0 || s.activeLayerIndex >= s.layers.size()) return null;
        EditorActivity.LayerItem activeL = s.layers.get(s.activeLayerIndex);
        if (activeL.name != null && activeL.name.startsWith("[Group] ")) {
            return activeL;
        }
        if (layerParentGroupMap.containsKey(activeL.id)) {
            String pId = layerParentGroupMap.get(activeL.id);
            return findLayerById(s, pId);
        }
        if (lastActiveGroupId != null) {
            return findLayerById(s, lastActiveGroupId);
        }
        return null;
    }

    private void hookLayersDockForSmartObjects() {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            LinearLayout container = findLayersListContainer();
            if (container != null) {
                container.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                    @Override 
                    public void onChildViewAdded(View parent, View child) { 
                        enhanceLayerRowView(child); 
                    }
                    @Override public void onChildViewRemoved(View parent, View child) {}
                });
                for (int i = 0; i < container.getChildCount(); i++) {
                    enhanceLayerRowView(container.getChildAt(i));
                }
            }
            hookBottomBarButtons();
        });
    }

    private int getGroupNestingDepth(String layerId) {
        int depth = 0;
        String curr = layerParentGroupMap.get(layerId);
        while (curr != null && depth < 10) {
            depth++;
            curr = layerParentGroupMap.get(curr);
        }
        return depth;
    }

    private boolean isLayerEffectivelyVisibleInDock(String layerId) {
        String curr = layerParentGroupMap.get(layerId);
        while (curr != null) {
            boolean exp = !groupExpandedState.containsKey(curr) || Boolean.TRUE.equals(groupExpandedState.get(curr));
            if (!exp) return false;
            curr = layerParentGroupMap.get(curr);
        }
        return true;
    }

    private boolean isDescendantOf(String layerId, String ancestorId) {
        if (layerId == null || ancestorId == null) return false;
        String parent = layerParentGroupMap.get(layerId);
        while (parent != null) {
            if (parent.equals(ancestorId)) return true;
            parent = layerParentGroupMap.get(parent);
        }
        return false;
    }

    private void toggleGroupChildrenVisibility(EditorActivity.ProjectTabSession s, String groupId, boolean visible) {
        if (s == null) return;
        for (EditorActivity.LayerItem item : s.layers) {
            if (isDescendantOf(item.id, groupId)) {
                item.isVisible = visible;
            }
        }
    }

    private EditorActivity.LayerItem findLayerById(EditorActivity.ProjectTabSession s, String id) {
        if (s == null || id == null) return null;
        for (EditorActivity.LayerItem item : s.layers) {
            if (id.equals(item.id)) return item;
        }
        return null;
    }

    // ড্রাগ করে ফোল্ডারের ওপর বা ভেতরের লেয়ারের ওপর ছাড়লে গ্রুপ হ্যান্ডেল করা
    private void handleLayerDropped(EditorActivity.ProjectTabSession s, EditorActivity.LayerItem dragged, EditorActivity.LayerItem target) {
        if (s == null || dragged == null || target == null || dragged.id.equals(target.id)) return;

        boolean targetIsGroup = target.name != null && target.name.startsWith("[Group] ");

        if (targetIsGroup) {
            if (!isDescendantOf(target.id, dragged.id)) {
                layerParentGroupMap.put(dragged.id, target.id);
                groupExpandedState.put(target.id, true);
                lastActiveGroupId = target.id;
                Toast.makeText(activity, "Added to " + target.name.replace("[Group] ", ""), Toast.LENGTH_SHORT).show();
            }
        } else if (layerParentGroupMap.containsKey(target.id)) {
            String parentGId = layerParentGroupMap.get(target.id);
            if (!isDescendantOf(parentGId, dragged.id)) {
                layerParentGroupMap.put(dragged.id, parentGId);
                lastActiveGroupId = parentGId;
            }
        } else {
            if (layerParentGroupMap.containsKey(dragged.id)) {
                layerParentGroupMap.remove(dragged.id);
                lastActiveGroupId = null;
                Toast.makeText(activity, "Removed from folder", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ফোল্ডার ডিলিট করার মেথড: ফোল্ডার সহ তার ভেতরের সমস্ত লেয়ার একসাথে ডিলিট
    public void deleteFolderWithChildren(EditorActivity.ProjectTabSession s, EditorActivity.LayerItem groupLayer) {
        if (s == null || groupLayer == null) return;

        List<EditorActivity.LayerItem> toDelete = new ArrayList<>();
        toDelete.add(groupLayer);

        for (EditorActivity.LayerItem item : s.layers) {
            if (isDescendantOf(item.id, groupLayer.id)) {
                toDelete.add(item);
            }
        }

        for (EditorActivity.LayerItem item : toDelete) {
            layerParentGroupMap.remove(item.id);
            groupExpandedState.remove(item.id);
            s.layers.remove(item);
        }

        if (groupLayer.id.equals(lastActiveGroupId)) {
            lastActiveGroupId = null;
        }

        s.activeLayerIndex = Math.max(0, Math.min(s.activeLayerIndex, s.layers.size() - 1));
        activity.recordHistory();
        invokePrivateVoidMethod("updateLayersDockUI");
        if (activity.canvasView != null) activity.canvasView.invalidate();
        activity.autoSaveCurrentProject();
        Toast.makeText(activity, "Folder and all its layers deleted", Toast.LENGTH_SHORT).show();
    }

    private void enhanceLayerRowView(View rowView) {
        if (!(rowView instanceof LinearLayout)) return;
        final LinearLayout row = (LinearLayout) rowView;
        final EditorActivity.ProjectTabSession s = activity.getCurrentSession();
        if (s == null) return;

        int foundIndex = -1;
        LinearLayout container = findLayersListContainer();
        if (container != null) {
            int rowPos = container.indexOfChild(row);
            foundIndex = (s.layers.size() - 1) - rowPos;
        }

        if (foundIndex < 0 || foundIndex >= s.layers.size()) return;
        final int layerIdx = foundIndex;
        final EditorActivity.LayerItem layer = s.layers.get(layerIdx);

        // সাধারণ শেপ বা বাইরের কোনো টুল নতুন লেয়ার বানালে তা শান্তভাবে যুক্ত করা (কোনো পোস্ট বা লাফালাফি ছাড়া)
        if (!knownLayerIds.contains(layer.id)) {
            knownLayerIds.add(layer.id);
            if (!layer.name.startsWith("[Group] ") && !layerParentGroupMap.containsKey(layer.id) && lastActiveGroupId != null) {
                layerParentGroupMap.put(layer.id, lastActiveGroupId);
            }
        }

        final boolean isGroup = layer.name.startsWith("[Group] ");
        int depth = getGroupNestingDepth(layer.id);
        boolean isRowVisible = isLayerEffectivelyVisibleInDock(layer.id);
        row.setVisibility(isRowVisible ? View.VISIBLE : View.GONE);

        int padLeft = dp(6 + (depth * 16));
        row.setPadding(padLeft, dp(4), dp(6), dp(4));

        int nameIdx = -1;
        TextView nameTv = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof TextView && child.getTag() == null) {
                nameTv = (TextView) child;
                nameIdx = i;
                break;
            }
        }

        if (isGroup) {
            View eyeView = row.getChildAt(0);
            if (eyeView != null) {
                eyeView.setOnClickListener(v -> {
                    boolean newVis = !layer.isVisible;
                    layer.isVisible = newVis;
                    toggleGroupChildrenVisibility(s, layer.id, newVis);
                    activity.recordHistory();
                    invokePrivateVoidMethod("updateLayersDockUI");
                    hookLayersDockForSmartObjects();
                    if (activity.canvasView != null) activity.canvasView.invalidate();
                });
            }

            for (int i = 1; i < (nameIdx != -1 ? nameIdx : row.getChildCount()); i++) {
                View child = row.getChildAt(i);
                Object tag = child.getTag();
                if ("group_arrow_tag".equals(tag) || "group_folder_icon_tag".equals(tag)) continue;
                if (child instanceof ImageView || child instanceof FrameLayout) {
                    child.setVisibility(View.GONE);
                }
            }

            if (nameIdx != -1) {
                for (int i = nameIdx + 1; i < row.getChildCount(); i++) {
                    View rightChild = row.getChildAt(i);
                    rightChild.setVisibility(View.VISIBLE);
                }
            }

            View oldArrow = row.findViewWithTag("group_arrow_tag");
            if (oldArrow != null) row.removeView(oldArrow);
            View oldFolder = row.findViewWithTag("group_folder_icon_tag");
            if (oldFolder != null) row.removeView(oldFolder);

            final TextView arrowBtn = new TextView(activity);
            arrowBtn.setTag("group_arrow_tag");
            boolean isExp = groupExpandedState.containsKey(layer.id) ? groupExpandedState.get(layer.id) : true;
            arrowBtn.setText(isExp ? "▼" : "▶");
            arrowBtn.setTextColor(Color.WHITE);
            arrowBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            arrowBtn.setPadding(dp(2), 0, dp(4), 0);
            arrowBtn.setOnClickListener(v -> {
                boolean curState = groupExpandedState.containsKey(layer.id) ? groupExpandedState.get(layer.id) : true;
                groupExpandedState.put(layer.id, !curState);
                invokePrivateVoidMethod("updateLayersDockUI");
                hookLayersDockForSmartObjects();
            });

            ImageView folderIcon = new ImageView(activity);
            folderIcon.setTag("group_folder_icon_tag");
            folderIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(18)));
            folderIcon.setImageDrawable(createVectorFolderIcon());
            folderIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            folderIcon.setPadding(0, 0, dp(6), 0);

            int insertPos = Math.min(1, row.getChildCount());
            row.addView(arrowBtn, insertPos);
            row.addView(folderIcon, insertPos + 1);

            if (nameTv != null) {
                String text = nameTv.getText().toString();
                if (text.startsWith("[Group] ")) {
                    nameTv.setText(text.replace("[Group] ", ""));
                }
            }
        }

        // EditorActivity-র ড্রাগ হ্যান্ডেল (:::) শান্তভাবে ট্র্যাক করা (অনাকাঙ্ক্ষিত লাফালাফি রোধে)
        if (nameIdx != -1) {
            for (int i = nameIdx + 1; i < row.getChildCount(); i++) {
                View rightView = row.getChildAt(i);
                if (rightView instanceof ImageView && rightView.getTag() == null) {
                    rightView.setTag("hooked_drag_handle");
                    final View.OnTouchListener originalTouch = getOnClickListenerTouch(rightView);
                    rightView.setOnTouchListener(new View.OnTouchListener() {
                        float startY = 0f;
                        @Override
                        public boolean onTouch(View v, MotionEvent event) {
                            switch (event.getAction()) {
                                case MotionEvent.ACTION_DOWN:
                                    startY = event.getRawY();
                                    break;
                                case MotionEvent.ACTION_UP:
                                case MotionEvent.ACTION_CANCEL:
                                    float dY = event.getRawY() - startY;
                                    float rowH = dp(36f);
                                    int steps = Math.round(-dY / rowH);
                                    int targetIdx = Math.max(0, Math.min(s.layers.size() - 1, layerIdx + steps));
                                    if (targetIdx != layerIdx && targetIdx < s.layers.size()) {
                                        EditorActivity.LayerItem targetItem = s.layers.get(targetIdx);
                                        handleLayerDropped(s, layer, targetItem);
                                    }
                                    break;
                            }
                            if (originalTouch != null) {
                                return originalTouch.onTouch(v, event);
                            }
                            return false;
                        }
                    });
                    break;
                }
            }
        }

        // সোয়াইপ জেসচার (ডানে টানলে ওপরের ফোল্ডারে প্রবেশ, বামে টানলে বের হওয়া)
        if (!isGroup) {
            row.setOnTouchListener(new View.OnTouchListener() {
                float downX = 0f, downY = 0f;
                boolean isSwiping = false;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getRawX();
                            downY = event.getRawY();
                            isSwiping = false;
                            break;
                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - downX;
                            float dy = event.getRawY() - downY;
                            if (Math.abs(dx) > dp(25) && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                                isSwiping = true;
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                            if (isSwiping) {
                                float deltaX = event.getRawX() - downX;
                                if (deltaX > dp(25)) {
                                    EditorActivity.LayerItem folderAbove = findNearestFolderAbove(s, layerIdx);
                                    if (folderAbove != null) {
                                        handleLayerDropped(s, layer, folderAbove);
                                        activity.recordHistory();
                                        invokePrivateVoidMethod("updateLayersDockUI");
                                        hookLayersDockForSmartObjects();
                                        return true;
                                    }
                                } else if (deltaX < -dp(25)) {
                                    if (layerParentGroupMap.containsKey(layer.id)) {
                                        layerParentGroupMap.remove(layer.id);
                                        lastActiveGroupId = null;
                                        activity.recordHistory();
                                        invokePrivateVoidMethod("updateLayersDockUI");
                                        hookLayersDockForSmartObjects();
                                        Toast.makeText(activity, "Removed from folder", Toast.LENGTH_SHORT).show();
                                        return true;
                                    }
                                }
                            }
                            break;
                    }
                    return false;
                }
            });
        }

        // ক্লিক লিসেনার: ফোল্ডার সিলেক্ট রাখলে পরবর্তী সব কাজ শান্তভাবে ওই ফোল্ডারে হবে
        row.setOnClickListener(v -> {
            s.activeLayerIndex = layerIdx;
            if (isGroup) {
                lastActiveGroupId = layer.id;
            } else {
                lastActiveGroupId = layerParentGroupMap.get(layer.id);
            }
            invokePrivateVoidMethod("updateLayersDockUI");
            hookLayersDockForSmartObjects();
            if (activity.canvasView != null) activity.canvasView.invalidate();
        });

        // স্মার্ট অবজেক্ট ব্যাজ
        if (layer.isSmartObject && row.findViewWithTag("smart_badge_tag") == null) {
            ImageView smartBadge = new ImageView(activity);
            smartBadge.setTag("smart_badge_tag");
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(18), dp(18));
            bp.setMargins(0, 0, dp(4), 0);
            smartBadge.setLayoutParams(bp);

            smartBadge.setImageDrawable(createPhotoshopSmartObjectBadge());
            smartBadge.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            smartBadge.setPadding(dp(2), dp(2), dp(2), dp(2));

            GradientDrawable bbg = new GradientDrawable();
            bbg.setColor(Color.parseColor("#222224"));
            bbg.setStroke(dp(1), Color.parseColor("#444448"));
            bbg.setCornerRadius(dp(3));
            smartBadge.setBackground(bbg);

            smartBadge.setOnClickListener(v -> openSmartObjectInNewTab(layer));
            row.addView(smartBadge, Math.min(2, row.getChildCount()));
        }

        row.setOnLongClickListener(v -> {
            s.activeLayerIndex = layerIdx;
            if (isGroup) {
                lastActiveGroupId = layer.id;
            } else {
                lastActiveGroupId = layerParentGroupMap.get(layer.id);
            }
            showAdvancedLayerMenu(row, layer, layerIdx);
            return true;
        });
    }

    private EditorActivity.LayerItem findNearestFolderAbove(EditorActivity.ProjectTabSession s, int layerIdx) {
        if (s == null) return null;
        for (int i = layerIdx + 1; i < s.layers.size(); i++) {
            EditorActivity.LayerItem l = s.layers.get(i);
            if (l.name != null && l.name.startsWith("[Group] ")) {
                return l;
            }
        }
        return null;
    }

    // ৪. লেয়ার অ্যাকশন মেনু (Move into Folder সম্পূর্ণরূপে বাদ দেওয়া হয়েছে)
    private void showAdvancedLayerMenu(View anchor, final EditorActivity.LayerItem layer, final int index) {
        final PopupWindow popup = new PopupWindow(activity);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1E1E1E"));
        bg.setStroke(dp(1f), Color.parseColor("#333333"));
        bg.setCornerRadius(dp(4));
        root.setBackground(bg);

        TextView head = new TextView(activity);
        head.setText("Layer: " + layer.name.replace("[Group] ", ""));
        head.setTextColor(Color.WHITE);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        head.setTypeface(null, Typeface.BOLD);
        head.setPadding(dp(4), dp(2), dp(4), dp(6));
        root.addView(head);

        ScrollView sv = new ScrollView(activity);
        sv.setVerticalScrollBarEnabled(true);
        sv.setScrollbarFadingEnabled(false);

        LinearLayout inner = new LinearLayout(activity);
        inner.setOrientation(LinearLayout.VERTICAL);

        final EditorActivity.ProjectTabSession s = activity.getCurrentSession();
        boolean isGroup = layer.name.startsWith("[Group] ");

        inner.addView(createMenuItem("Duplicate Layer", v -> { popup.dismiss(); invokePrivateVoidMethod("duplicateActiveLayer"); }));
        
        // ফোল্ডার হলে সমস্ত লেয়ার সহ শান্তভাবে ডিলিট
        inner.addView(createMenuItem("Delete Layer", v -> { 
            popup.dismiss(); 
            if (isGroup) {
                deleteFolderWithChildren(s, layer);
            } else {
                invokePrivateVoidMethod("deleteActiveLayer");
            }
        }));

        if (isGroup) {
            inner.addView(createMenuItem("New Subfolder", v -> {
                popup.dismiss();
                createLayerGroup(s, index);
            }));
            inner.addView(createMenuItem("Ungroup Layer", v -> {
                popup.dismiss();
                ungroupLayers(s, layer);
            }));
            inner.addView(createMenuItem("Rename Group", v -> {
                popup.dismiss();
                showRenameGroupDialog(layer);
            }));
        } else {
            inner.addView(createMenuItem("Group Layer", v -> {
                popup.dismiss();
                createLayerGroup(s, index);
            }));

            String parentG = layerParentGroupMap.get(layer.id);
            if (parentG != null) {
                inner.addView(createMenuItem("Remove from Group", v -> {
                    popup.dismiss();
                    layerParentGroupMap.remove(layer.id);
                    lastActiveGroupId = null;
                    activity.recordHistory();
                    invokePrivateVoidMethod("updateLayersDockUI");
                    hookLayersDockForSmartObjects();
                    Toast.makeText(activity, "Removed from group", Toast.LENGTH_SHORT).show();
                }));
            }
        }

        inner.addView(createMenuItem("Select Pixels", v -> {
            popup.dismiss();
            showSelectPixelsDialog(s, layer);
        }));

        if (index > 0) {
            inner.addView(createMenuItem("Merge Down", v -> {
                popup.dismiss();
                implementMergeDown(s, index);
            }));

            if (layer.isClippingMask) {
                inner.addView(createMenuItem("Release Clipping Mask", v -> {
                    popup.dismiss();
                    implementReleaseClippingMask(layer);
                }));
            } else {
                inner.addView(createMenuItem("Create Clipping Mask", v -> {
                    popup.dismiss();
                    implementClippingMask(layer);
                }));
            }
        } else if (layer.isClippingMask) {
            inner.addView(createMenuItem("Release Clipping Mask", v -> {
                popup.dismiss();
                implementReleaseClippingMask(layer);
            }));
        }

        if (!layer.isSmartObject) {
            inner.addView(createMenuItem("Convert to Smart Object", v -> {
                popup.dismiss();
                convertToSmartObject(layer);
            }));
        } else {
            inner.addView(createMenuItem("Edit Smart Object (New Tab)", v -> {
                popup.dismiss();
                openSmartObjectInNewTab(layer);
            }));
        }

        if (s.isSmartObjectChild && s.parentSmartLayerRef != null) {
            inner.addView(createMenuItem("Save Smart Object", v -> {
                popup.dismiss();
                saveSmartObjectChanges(s);
            }));
        }

        inner.addView(createMenuItem("Rasterize Layer", v -> {
            popup.dismiss();
            rasterizeLayer(layer);
        }));

        inner.addView(createMenuItem("Move Layer UP", v -> { popup.dismiss(); invokeMoveLayerOrder(index, true); }));
        inner.addView(createMenuItem("Move Layer DOWN", v -> { popup.dismiss(); invokeMoveLayerOrder(index, false); }));

        sv.addView(inner);
        root.addView(sv);

        popup.setContentView(root);
        int menuWidth = dp(195);
        popup.setWidth(menuWidth);

        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        int maxH = (int) (screenH * 0.60f);

        root.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int measuredH = root.getMeasuredHeight();
        popup.setHeight(Math.min(measuredH, maxH));

        popup.setFocusable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.showAsDropDown(anchor, dp(10), dp(-10));
    }

    private void createLayerGroup(EditorActivity.ProjectTabSession s, int targetIdx) {
        if (s == null) return;
        targetIdx = Math.max(0, Math.min(targetIdx, s.layers.size()));

        Bitmap dummyBmp = Bitmap.createBitmap(dp(28), dp(28), Bitmap.Config.ARGB_8888);
        EditorActivity.LayerItem groupFolder = new EditorActivity.LayerItem("[Group] Folder " + (s.layers.size() + 1), dummyBmp, 0, 0, false);
        groupFolder.isBlank = true;

        EditorActivity.LayerItem activeLayer = (s.activeLayerIndex >= 0 && s.activeLayerIndex < s.layers.size()) ? s.layers.get(s.activeLayerIndex) : null;

        if (activeLayer != null && activeLayer.name.startsWith("[Group] ")) {
            int pIdx = s.layers.indexOf(activeLayer);
            if (pIdx >= 0) {
                s.layers.add(pIdx, groupFolder);
            } else {
                s.layers.add(groupFolder);
            }
            layerParentGroupMap.put(groupFolder.id, activeLayer.id);
            groupExpandedState.put(activeLayer.id, true);
        } else if (targetIdx < s.layers.size()) {
            EditorActivity.LayerItem targetLayer = s.layers.get(targetIdx);
            s.layers.add(targetIdx + 1, groupFolder);
            layerParentGroupMap.put(targetLayer.id, groupFolder.id);
        } else {
            s.layers.add(groupFolder);
        }
        
        groupExpandedState.put(groupFolder.id, true);
        lastActiveGroupId = groupFolder.id;
        knownLayerIds.add(groupFolder.id);

        activity.recordHistory();
        invokePrivateVoidMethod("updateLayersDockUI");
        hookLayersDockForSmartObjects();
        Toast.makeText(activity, "Folder Created", Toast.LENGTH_SHORT).show();
    }

    private void ungroupLayers(EditorActivity.ProjectTabSession s, EditorActivity.LayerItem groupLayer) {
        if (s == null || groupLayer == null) return;

        String parentOfThis = layerParentGroupMap.get(groupLayer.id);
        List<String> childrenToRemove = new ArrayList<>();
        for (Map.Entry<String, String> entry : layerParentGroupMap.entrySet()) {
            if (groupLayer.id.equals(entry.getValue())) {
                childrenToRemove.add(entry.getKey());
            }
        }
        for (String childId : childrenToRemove) {
            if (parentOfThis != null) {
                layerParentGroupMap.put(childId, parentOfThis);
            } else {
                layerParentGroupMap.remove(childId);
            }
        }

        groupExpandedState.remove(groupLayer.id);
        layerParentGroupMap.remove(groupLayer.id);
        s.layers.remove(groupLayer);

        if (groupLayer.id.equals(lastActiveGroupId)) {
            lastActiveGroupId = parentOfThis;
        }

        activity.recordHistory();
        invokePrivateVoidMethod("updateLayersDockUI");
        hookLayersDockForSmartObjects();
        Toast.makeText(activity, "Group Ungrouped", Toast.LENGTH_SHORT).show();
    }

    private void showRenameGroupDialog(final EditorActivity.LayerItem groupLayer) {
        final Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1A1A1E"));
        bg.setStroke(dp(1.2f), Color.parseColor("#32323A"));
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);

        LinearLayout headRow = new LinearLayout(activity);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.setPadding(0, 0, 0, dp(14));

        ImageView folderIcon = new ImageView(activity);
        folderIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(16)));
        folderIcon.setImageDrawable(createVectorFolderIcon());
        folderIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        headRow.addView(folderIcon);

        TextView title = new TextView(activity);
        title.setText(" Rename Group");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(6), 0, 0, 0);
        headRow.addView(title);
        card.addView(headRow);

        final EditText et = new EditText(activity);
        String curName = groupLayer.name.replace("[Group] ", "");
        et.setText(curName);
        et.setTextColor(Color.WHITE);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        et.setSingleLine(true);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(Color.parseColor("#101012"));
        etBg.setStroke(dp(1), Color.parseColor("#2F2F38"));
        etBg.setCornerRadius(dp(6));
        et.setBackground(etBg);
        card.addView(et);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        btnRow.setPadding(0, dp(16), 0, 0);

        Button btnCancel = new Button(activity);
        btnCancel.setText("Cancel");
        btnCancel.setTextColor(Color.parseColor("#B0B0B0"));
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        GradientDrawable canBg = new GradientDrawable();
        canBg.setColor(Color.parseColor("#26262B"));
        canBg.setCornerRadius(dp(6));
        btnCancel.setBackground(canBg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(70), dp(32));
        clp.setMargins(0, 0, dp(8), 0);
        btnCancel.setLayoutParams(clp);
        btnCancel.setOnClickListener(v -> d.dismiss());
        btnRow.addView(btnCancel);

        Button btnSave = new Button(activity);
        btnSave.setText("Save");
        btnSave.setTextColor(Color.WHITE);
        btnSave.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        btnSave.setTypeface(null, Typeface.BOLD);
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setColor(Color.parseColor("#FF1238"));
        saveBg.setCornerRadius(dp(6));
        btnSave.setBackground(saveBg);
        btnSave.setLayoutParams(new LinearLayout.LayoutParams(dp(70), dp(32)));

        btnSave.setOnClickListener(v -> {
            String newN = et.getText().toString().trim();
            if (!newN.isEmpty()) {
                groupLayer.name = "[Group] " + newN;
                activity.recordHistory();
                invokePrivateVoidMethod("updateLayersDockUI");
                hookLayersDockForSmartObjects();
            }
            d.dismiss();
        });
        btnRow.addView(btnSave);
        card.addView(btnRow);

        d.setContentView(card);
        d.show();

        et.requestFocus();
        et.selectAll();
        et.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        }, 100);
    }

    private TextView createMenuItem(String text, View.OnClickListener listener) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setPadding(dp(8), dp(6), dp(8), dp(6));
        tv.setOnClic