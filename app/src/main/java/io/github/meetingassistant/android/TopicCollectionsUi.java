package io.github.meetingassistant.android;

import android.app.*;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import io.github.meetingassistant.R;
import io.github.meetingassistant.core.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;

/** Collection tree. Open-drawer order is a snapshot, independent of streaming token callbacks. */
final class TopicCollectionsUi {
  private final MainActivity screen;
  private final MeetingApp app;
  private final Consumer<String> topic, actions;
  private final BiConsumer<String, Boolean> collection;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final List<String> order = new ArrayList<>();
  private final Map<String, List<String>> topics = new HashMap<>();
  private final Map<String, String> sections = new HashMap<>();
  private String signature = "";
  private AlertDialog modal;
  private long openedAt;

  TopicCollectionsUi(
      MainActivity screen,
      MeetingApp app,
      Consumer<String> topic,
      Consumer<String> actions,
      BiConsumer<String, Boolean> collection) {
    this.screen = screen;
    this.app = app;
    this.topic = topic;
    this.actions = actions;
    this.collection = collection;
  }

  private String str(int id) {
    return screen.getString(id);
  }

  private int dp(int n) {
    return UiControls.dp(screen, n);
  }

  private void tell(String value) {
    screen.imageNotice(value);
  }

  private TextView text(String value, int size) {
    TextView v = new TextView(screen);
    v.setText(value);
    v.setTextSize(size);
    AppTheme.text(v, AppTheme.MUTED);
    v.setPadding(dp(12), dp(12), dp(8), dp(8));
    return v;
  }

  private LinearLayout row() {
    LinearLayout row = new LinearLayout(screen);
    row.setGravity(Gravity.CENTER_VERTICAL);
    return row;
  }

  void opened() {
    order.clear();
    topics.clear();
    sections.clear();
    signature = "";
    openedAt = System.currentTimeMillis();
  }

  String label(TopicCatalog.Collection c) {
    return c.name.isEmpty()
        ? str(R.string.default_collection)
        : c.name + (TopicCatalog.DEFAULT.equals(c.id) ? " · " + str(R.string.default_badge) : "");
  }

  void render(LinearLayout host) {
    TopicCatalog catalog = app.meetings.catalog();
    List<MeetingManager.Summary> records = app.meetings.summaries();
    String next =
        catalog.selectedCollection
            + catalog.expanded
            + app.meetings.currentId()
            + app.meetings.error()
            + app.meetings.navigating()
            + app.meetings.structureBusy();
    for (TopicCatalog.Collection c : catalog.collections) next += c.id + c.name;
    for (MeetingManager.Summary r : records) next += r.id + r.collectionId + r.title + r.state;
    if (signature.equals(next)) return;
    signature = next;
    order.removeIf(id -> catalog.find(id) == null);
    for (TopicCatalog.Collection c : catalog.ordered())
      if (!order.contains(c.id)) {
        if (TopicCatalog.DEFAULT.equals(c.id)) order.add(c.id);
        else {
          int defaultAt = order.indexOf(TopicCatalog.DEFAULT);
          // Preserve initial sorted order; new folders reconcile ahead of existing rows.
          if (sections.isEmpty() || openedAt == 0)
            order.add(defaultAt < 0 ? order.size() : defaultAt, c.id);
          else order.add(0, c.id);
        }
      }
    order.remove(TopicCatalog.DEFAULT);
    order.add(TopicCatalog.DEFAULT);
    for (String id : order) {
      TopicCatalog.Collection c = catalog.find(id);
      sections.putIfAbsent(
          id, CollectionDates.section(c.updatedAt, openedAt, ZoneId.systemDefault()));
      List<String> ids = topics.computeIfAbsent(id, unused -> new ArrayList<>());
      ids.removeIf(
          key -> records.stream().noneMatch(r -> r.id.equals(key) && r.collectionId.equals(id)));
      List<String> fresh =
          records.stream()
              .filter(r -> r.collectionId.equals(id) && !ids.contains(r.id))
              .map(r -> r.id)
              .collect(java.util.stream.Collectors.toList());
      ids.addAll(0, fresh);
    }
    host.removeAllViews();
    if (!app.meetings.error().isEmpty()) {
      host.addView(text(new LocalizedMessages(screen).notice(app.meetings.error()), 13));
      host.addView(UiControls.button(screen, str(R.string.retry_save), false, app.meetings::flush));
    }
    if (app.meetings.navigating())
      host.addView(
          UiControls.button(
              screen, str(R.string.cancel_switch), false, app.meetings::cancelNavigation));
    String last = "";
    for (String id : order) {
      TopicCatalog.Collection c = catalog.find(id);
      boolean builtin = TopicCatalog.DEFAULT.equals(id);
      String section = sections.get(id);
      if (builtin) {
        View line = new View(screen);
        AppTheme.background(line, AppTheme.OUTLINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(dp(12), dp(16), dp(12), dp(8));
        host.addView(line, lp);
      } else if (!section.equals(last)) {
        host.addView(text(sectionLabel(section), 13));
        last = section;
      }
      boolean selected = id.equals(catalog.selectedCollection);
      boolean expanded = catalog.expanded.contains(id);
      LinearLayout folder = row();
      AppTheme.bind(
          folder,
          p ->
              folder.setBackground(
                  UiControls.ripple(
                      screen,
                      UiControls.shape(screen, selected ? AppTheme.SELECTED : AppTheme.SURFACE, 16),
                      16)));
      folder.setClipToOutline(true);
      ImageButton arrow =
          UiControls.icon(
              screen,
              R.drawable.ic_chevron,
              screen.getString(
                  expanded ? R.string.collapse_collection : R.string.expand_collection, label(c)),
              () -> {
                app.meetings.setExpanded(id, !app.meetings.catalog().expanded.contains(id));
                render(host);
              });
      arrow.setRotation(expanded ? 90 : 0);
      arrow.setTag("expand:" + id);
      folder.addView(arrow);
      ImageView icon = new ImageView(screen);
      icon.setImageResource(R.drawable.ic_folder);
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      folder.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
      Button name = UiControls.button(screen, label(c), false, () -> collection.accept(id, false));
      name.setTag("collection:" + id);
      name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      AppTheme.bind(
          name,
          p -> {
            name.setBackgroundTintList(null);
            name.setBackground(UiControls.ripple(screen, null, 16));
          });
      AppTheme.text(name, selected ? AppTheme.ON_SELECTED : AppTheme.TEXT);
      name.setMaxLines(2);
      folder.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      if (selected) {
        ImageButton more =
            UiControls.icon(
                screen,
                R.drawable.ic_more,
                screen.getString(R.string.collection_actions, label(c)),
                () -> collectionActions(id));
        more.setTag("collection-actions:" + id);
        folder.addView(more);
      }
      host.addView(folder, new LinearLayout.LayoutParams(-1, -2));
      if (!expanded) continue;
      if (topics.get(id).isEmpty()) host.addView(text(str(R.string.no_meetings), 13));
      for (String key : topics.get(id)) {
        MeetingManager.Summary r =
            records.stream().filter(s -> s.id.equals(key)).findFirst().orElse(null);
        if (r != null) addTopic(host, r);
      }
    }
  }

  private String sectionLabel(String section) {
    return switch (section) {
      case "today" -> str(R.string.today);
      case "yesterday" -> str(R.string.yesterday);
      case "week" -> str(R.string.previous_seven_days);
      default ->
          YearMonth.parse(section)
              .format(
                  DateTimeFormatter.ofPattern(
                      screen
                              .getResources()
                              .getConfiguration()
                              .getLocales()
                              .get(0)
                              .getLanguage()
                              .equals("zh")
                          ? "yyyy 年 M 月"
                          : "MMMM yyyy",
                      screen.getResources().getConfiguration().getLocales().get(0)));
    };
  }

  private void addTopic(LinearLayout host, MeetingManager.Summary r) {
    boolean selected = r.id.equals(app.meetings.currentId());
    LinearLayout item = row();
    AppTheme.bind(
        item,
        p ->
            item.setBackground(
                UiControls.shape(screen, selected ? AppTheme.SELECTED : AppTheme.PANEL, 16)));
    item.setClipToOutline(true);
    String title =
        r.defaultTitle
            ? screen.getString(
                R.string.new_meeting_title,
                new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
                    .format(new Date(r.createdAt)))
            : r.title;
    String state =
        r.speechActive()
            ? str(R.string.meeting_listening)
            : r.state == SessionEngine.State.ANALYZING
                ? str(R.string.meeting_analyzing)
                : r.state == SessionEngine.State.ERROR ? str(R.string.meeting_interrupted) : "";
    Button select =
        UiControls.button(
            screen, (selected ? "● " : "") + title + state, false, () -> topic.accept(r.id));
    select.setTag(r.id);
    select.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    AppTheme.bind(
        select,
        p -> {
          select.setTextColor(p.color(selected ? AppTheme.ON_SELECTED : AppTheme.TEXT));
          select.setBackgroundTintList(null);
          select.setBackground(UiControls.ripple(screen, null, 16));
        });
    select.setStateListAnimator(null);
    select.setElevation(0);
    select.setOnLongClickListener(
        v -> {
          if (selected) actions.accept(r.id);
          return true;
        });
    item.addView(select, new LinearLayout.LayoutParams(0, -2, 1));
    if (selected)
      item.addView(
          UiControls.icon(
              screen,
              R.drawable.ic_more,
              screen.getString(R.string.meeting_actions, title),
              () -> actions.accept(r.id)));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(dp(24), dp(6), 0, 0);
    host.addView(item, lp);
  }

  private void show(AlertDialog dialog) {
    modal = dialog;
    dialog.setOnKeyListener(
        (d, code, event) -> {
          if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) return false;
          return !(dialog.getCurrentFocus() instanceof EditText)
              && app.settings.actionFor(code, event.getRepeatCount(), false, false) != null;
        });
    dialog.show();
  }

  void create() {
    edit(null);
  }

  private void edit(String id) {
    TopicCatalog.Collection c = id == null ? null : app.meetings.catalog().find(id);
    if (id != null && c == null) return;
    EditText name = new EditText(screen);
    name.setSingleLine(true);
    name.setText(c == null ? "" : c.name.isEmpty() ? str(R.string.default_collection) : c.name);
    AlertDialog dialog =
        AppTheme.alert(screen)
            .setTitle(str(id == null ? R.string.new_collection : R.string.rename_collection))
            .setView(name)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create();
    show(dialog);
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              try {
                if (id == null)
                  app.meetings.createCollection(
                      name.getText().toString(),
                      created ->
                          post(
                              () -> {
                                if (created == null)
                                  tell(str(R.string.collection_operation_failed));
                                else {
                                  dialog.dismiss();
                                  collection.accept(created, false);
                                }
                              }));
                else
                  app.meetings.renameCollection(
                      id,
                      name.getText().toString(),
                      success ->
                          post(
                              () -> {
                                if (success) dialog.dismiss();
                                else tell(str(R.string.collection_operation_failed));
                              }));
              } catch (IllegalArgumentException | IllegalStateException e) {
                tell(new LocalizedMessages(screen).notice(e.getMessage()));
              }
            });
  }

  private void collectionActions(String id) {
    TopicCatalog.Collection c = app.meetings.catalog().find(id);
    if (c == null) return;
    boolean builtin = TopicCatalog.DEFAULT.equals(id);
    show(
        AppTheme.alert(screen)
            .setTitle(label(c))
            .setItems(
                new String[] {
                  str(R.string.rename_collection),
                  str(builtin ? R.string.clear_collection : R.string.delete_collection)
                },
                (d, which) -> {
                  if (which == 0) edit(id);
                  else confirmDelete(id, builtin);
                })
            .create());
  }

  private void confirmDelete(String id, boolean clear) {
    TopicCatalog.Collection c = app.meetings.catalog().find(id);
    if (c == null) return;
    int count = app.meetings.collectionTopics(id).size();
    AlertDialog.Builder builder =
        AppTheme.danger(screen)
            .setTitle(str(clear ? R.string.clear_collection : R.string.delete_collection))
            .setMessage(
                screen
                    .getResources()
                    .getQuantityString(R.plurals.collection_delete_detail, count, label(c), count))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(
                R.string.delete,
                (d, w) -> {
                  try {
                    app.meetings.deleteCollection(
                        id,
                        clear,
                        success ->
                            post(
                                () ->
                                    tell(
                                        str(
                                            success
                                                ? R.string.collection_deleted
                                                : R.string.collection_operation_failed))));
                  } catch (IllegalStateException | IllegalArgumentException e) {
                    tell(new LocalizedMessages(screen).notice(e.getMessage()));
                  }
                });
    if (!clear && count > 0)
      builder.setNeutralButton(R.string.move_then_delete, (d, w) -> chooseDestination(id, true));
    show(builder.create());
  }

  void moveTopic(String id) {
    chooseDestination(id, false);
  }

  private void chooseDestination(String id, boolean merge) {
    MeetingRecord record = merge ? null : app.meetings.get(id);
    if (!merge && record == null) return;
    String source = merge ? id : record.collectionId;
    List<TopicCatalog.Collection> choices =
        app.meetings.catalog().ordered().stream()
            .filter(c -> !c.id.equals(source))
            .collect(java.util.stream.Collectors.toList());
    if (choices.isEmpty()) {
      tell(str(R.string.no_collection_destination));
      return;
    }
    show(
        AppTheme.alert(screen)
            .setTitle(str(R.string.move_to_collection))
            .setItems(
                choices.stream().map(this::label).toArray(String[]::new),
                (d, which) -> {
                  Consumer<Boolean> done =
                      success ->
                          post(
                              () ->
                                  tell(
                                      str(
                                          success
                                              ? R.string.topic_moved
                                              : R.string.collection_operation_failed)));
                  try {
                    if (merge) app.meetings.mergeCollection(source, choices.get(which).id, done);
                    else app.meetings.moveTopic(id, choices.get(which).id, done);
                  } catch (IllegalStateException | IllegalArgumentException e) {
                    tell(new LocalizedMessages(screen).notice(e.getMessage()));
                  }
                })
            .create());
  }

  private void post(Runnable action) {
    main.post(
        () -> {
          if (!screen.isDestroyed() && !screen.isFinishing()) action.run();
        });
  }

  void destroy() {
    if (modal != null) modal.dismiss();
    main.removeCallbacksAndMessages(null);
  }
}
