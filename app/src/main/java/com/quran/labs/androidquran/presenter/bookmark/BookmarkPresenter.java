package com.quran.labs.androidquran.presenter.bookmark;

import android.content.Context;
import android.preference.PreferenceManager;
import android.support.annotation.VisibleForTesting;
import android.support.design.widget.Snackbar;

import com.crashlytics.android.answers.Answers;
import com.crashlytics.android.answers.CustomEvent;
import com.f2prateek.rx.preferences.Preference;
import com.f2prateek.rx.preferences.RxSharedPreferences;
import com.quran.labs.androidquran.dao.Bookmark;
import com.quran.labs.androidquran.dao.BookmarkData;
import com.quran.labs.androidquran.dao.Tag;
import com.quran.labs.androidquran.data.Constants;
import com.quran.labs.androidquran.model.bookmark.BookmarkModel;
import com.quran.labs.androidquran.model.bookmark.BookmarkResult;
import com.quran.labs.androidquran.model.translation.ArabicDatabaseUtils;
import com.quran.labs.androidquran.presenter.Presenter;
import com.quran.labs.androidquran.ui.fragment.BookmarksFragment;
import com.quran.labs.androidquran.ui.helpers.QuranRow;
import com.quran.labs.androidquran.ui.helpers.QuranRowFactory;
import com.quran.labs.androidquran.util.QuranSettings;
import com.quran.labs.androidquran.util.QuranUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;
import timber.log.Timber;

@Singleton
public class BookmarkPresenter implements Presenter<BookmarksFragment> {
  @Snackbar.Duration public static final int DELAY_DELETION_DURATION_IN_MS = 4 * 1000; // 4 seconds
  private static final long BOOKMARKS_WITHOUT_TAGS_ID = -1;

  private final Context appContext;
  private final BookmarkModel bookmarkModel;
  private final QuranSettings quranSettings;

  private int sortOrder;
  private boolean groupByTags;
  private BookmarkResult cachedData;
  private BookmarksFragment fragment;
  private ArabicDatabaseUtils arabicDatabaseUtils;

  private boolean isRtl;
  private Subscription pendingRemoval;
  private List<QuranRow> itemsToRemove;

  @Inject
  BookmarkPresenter(Context appContext, BookmarkModel bookmarkModel) {
    this.appContext = appContext;
    quranSettings = QuranSettings.getInstance(appContext);
    this.bookmarkModel = bookmarkModel;
    sortOrder = quranSettings.getBookmarksSortOrder();
    groupByTags = quranSettings.getBookmarksGroupedByTags();
    try {
      arabicDatabaseUtils = ArabicDatabaseUtils.getInstance(appContext);
    } catch (Exception e) {
      arabicDatabaseUtils = null;
    }
    subscribeToChanges();
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  BookmarkPresenter(Context context, QuranSettings settings,
      BookmarkModel bookmarkModel, boolean subscribeToChanges) {
    appContext = context.getApplicationContext();
    quranSettings = settings;
    this.bookmarkModel = bookmarkModel;
    sortOrder = quranSettings.getBookmarksSortOrder();
    groupByTags = quranSettings.getBookmarksGroupedByTags();
    if (subscribeToChanges) {
      subscribeToChanges();
    }
  }

  private void subscribeToChanges() {
    RxSharedPreferences prefs = RxSharedPreferences.create(
        PreferenceManager.getDefaultSharedPreferences(appContext));
    Preference<Integer> lastPage = prefs.getInteger(Constants.PREF_LAST_PAGE);
    Observable.merge(bookmarkModel.tagsObservable(),
        bookmarkModel.bookmarksObservable(), lastPage.asObservable())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new Action1<Object>() {
          @Override
          public void call(Object o) {
            if (fragment != null) {
              requestData(false);
            } else {
              cachedData = null;
            }
          }
        });
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
    quranSettings.setBookmarksSortOrder(this.sortOrder);
    requestData(false);
  }

  public void toggleGroupByTags() {
    groupByTags = !groupByTags;
    quranSettings.setBookmarksGroupedByTags(groupByTags);
    requestData(false);
    Answers.getInstance().logCustom(
        new CustomEvent(groupByTags ? "groupByTags" : "doNotGroupByTags"));
  }

  public boolean shouldShowInlineTags() {
    return !groupByTags;
  }

  public boolean isGroupedByTags() {
    return groupByTags;
  }

  public boolean[] getContextualOperationsForItems(List<QuranRow> rows) {
    boolean[] result = new boolean[3];

    int headers = 0;
    int bookmarks = 0;
    for (int i = 0, rowsSize = rows.size(); i < rowsSize; i++) {
      QuranRow row = rows.get(i);
      if (row.isBookmarkHeader()) {
        headers++;
      } else if (row.isBookmark()) {
        bookmarks++;
      }
    }

    result[0] = headers == 1 && bookmarks == 0;
    result[1] = (headers + bookmarks) > 0;
    result[2] = headers == 0 && bookmarks > 0;
    return result;
  }

  public void requestData(boolean canCache) {
    if (canCache && cachedData != null) {
      if (fragment != null) {
        Timber.d("sending cached bookmark data");
        fragment.onNewData(cachedData);
      }
    } else {
      Timber.d("requesting bookmark data from the database");
      getBookmarks(sortOrder, groupByTags);
    }
  }

  public BookmarkResult predictQuranListAfterDeletion(List<QuranRow> remove) {
    if (cachedData != null) {
      List<QuranRow> placeholder = new ArrayList<>(cachedData.rows.size() - remove.size());
      List<QuranRow> rows = cachedData.rows;
      List<Long> removedTags = new ArrayList<>();
      for (int i = 0, rowsSize = rows.size(); i < rowsSize; i++) {
        QuranRow row = rows.get(i);
        if (!remove.contains(row)) {
          placeholder.add(row);
        }
      }

      for (int i = 0, removedSize = remove.size(); i < removedSize; i++) {
        QuranRow row = remove.get(i);
        if (row.isHeader() && row.tagId > 0){
          removedTags.add(row.tagId);
        }
      }

      Map<Long, Tag> tagMap;
      if (removedTags.isEmpty()) {
        tagMap = cachedData.tagMap;
      } else {
        tagMap = new HashMap<>(cachedData.tagMap);
        for (int i = 0, removedTagsSize = removedTags.size(); i < removedTagsSize; i++) {
          Long tagId = removedTags.get(i);
          tagMap.remove(tagId);
        }
      }
      return new BookmarkResult(placeholder, tagMap);
    }
    return null;
  }

  public void deleteAfterSomeTime(List<QuranRow> remove) {
    if (pendingRemoval != null) {
      // handle a new delete request when one is already happening by adding those items to delete
      // now and un-subscribing from the old request.
      if (itemsToRemove != null) {
        remove.addAll(itemsToRemove);
      }
      cancelDeletion();
    }

    itemsToRemove = remove;
    pendingRemoval = Observable.timer(DELAY_DELETION_DURATION_IN_MS, TimeUnit.MILLISECONDS)
        .flatMap(new Func1<Long, Observable<BookmarkResult>>() {
          @Override
          public Observable<BookmarkResult> call(Long aLong) {
            return removeItemsObservable();
          }
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new Action1<BookmarkResult>() {
          @Override
          public void call(BookmarkResult result) {
            pendingRemoval = null;
            cachedData = result;
            if (fragment != null) {
              fragment.onNewData(result);
            }
          }
        });
  }

  private Observable<BookmarkResult> removeItemsObservable() {
    return bookmarkModel.removeItemsObservable(new ArrayList<>(itemsToRemove))
        .flatMap(new Func1<Void, Observable<BookmarkResult>>() {
          @Override
          public Observable<BookmarkResult> call(Void aVoid) {
            return getBookmarksListObservable(sortOrder, groupByTags);
          }
        });
  }

  public void cancelDeletion() {
    if (pendingRemoval != null) {
      pendingRemoval.unsubscribe();
      pendingRemoval = null;
      itemsToRemove = null;
    }
  }

  private Observable<BookmarkData> getBookmarksWithAyatObservable(int sortOrder) {
    return bookmarkModel.getBookmarkDataObservable(sortOrder)
        .map(new Func1<BookmarkData, BookmarkData>() {
          @Override
          public BookmarkData call(BookmarkData bookmarkData) {
            try {
              return new BookmarkData(bookmarkData.getTags(),
                  arabicDatabaseUtils.hydrateAyahText(bookmarkData.getBookmarks()));
            } catch (Exception e) {
              return bookmarkData;
            }
          }
        });
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  Observable<BookmarkResult> getBookmarksListObservable(
      int sortOrder, final boolean groupByTags) {
    return getBookmarksWithAyatObservable(sortOrder)
        .map(new Func1<BookmarkData, BookmarkResult>() {
          @Override
          public BookmarkResult call(BookmarkData bookmarkData) {
            List<QuranRow> rows = getBookmarkRows(bookmarkData, groupByTags);
            Map<Long, Tag> tagMap = generateTagMap(bookmarkData.getTags());
            return new BookmarkResult(rows, tagMap);
          }
        })
        .subscribeOn(Schedulers.io());
  }

  private void getBookmarks(final int sortOrder, final boolean groupByTags) {
    getBookmarksListObservable(sortOrder, groupByTags)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new Action1<BookmarkResult>() {
          @Override
          public void call(BookmarkResult result) {
            // notify the ui if we're attached
            cachedData = result;
            if (fragment != null) {
              if (pendingRemoval != null && itemsToRemove != null) {
                fragment.onNewData(predictQuranListAfterDeletion(itemsToRemove));
              } else {
                fragment.onNewData(result);
              }
            }
          }
        });
  }

  private List<QuranRow> getBookmarkRows(BookmarkData data, boolean groupByTags) {
    List<QuranRow> rows;

    List<Tag> tags = data.getTags();
    List<Bookmark> bookmarks = data.getBookmarks();

    if (groupByTags) {
      rows = getRowsSortedByTags(tags, bookmarks);
    } else {
      rows = getSortedRows(bookmarks);
    }

    int lastPage = quranSettings.getLastPage();
    boolean showLastPage = lastPage != Constants.NO_PAGE_SAVED;
    if (showLastPage && (lastPage > Constants.PAGES_LAST || lastPage < Constants.PAGES_FIRST)) {
      showLastPage = false;
      Timber.w("Got invalid last saved page as %d", lastPage);
    }

    if (showLastPage) {
      rows.add(0, QuranRowFactory.fromCurrentPageHeader(appContext));
      rows.add(1, QuranRowFactory.fromCurrentPage(appContext, lastPage));
    }

    return rows;
  }

  private List<QuranRow> getRowsSortedByTags(List<Tag> tags, List<Bookmark> bookmarks) {
    List<QuranRow> rows = new ArrayList<>();
    // sort by tags, alphabetical
    Map<Long, List<Bookmark>> tagsMapping = generateTagsMapping(tags, bookmarks);
    for (int i = 0, tagsSize = tags.size(); i < tagsSize; i++) {
      Tag tag = tags.get(i);
      rows.add(QuranRowFactory.fromTag(tag));
      List<Bookmark> tagBookmarks = tagsMapping.get(tag.id);
      for (int j = 0, tagBookmarksSize = tagBookmarks.size(); j < tagBookmarksSize; j++) {
        rows.add(QuranRowFactory.fromBookmark(appContext, tagBookmarks.get(j), tag.id));
      }
    }

    // add untagged bookmarks
    List<Bookmark> untagged = tagsMapping.get(BOOKMARKS_WITHOUT_TAGS_ID);
    if (untagged.size() > 0) {
      rows.add(QuranRowFactory.fromNotTaggedHeader(appContext));
      for (int i = 0, untaggedSize = untagged.size(); i < untaggedSize; i++) {
        rows.add(QuranRowFactory.fromBookmark(appContext, untagged.get(i)));
      }
    }
    return rows;
  }

  private List<QuranRow> getSortedRows(List<Bookmark> bookmarks) {
    List<QuranRow> rows = new ArrayList<>(bookmarks.size());
    List<Bookmark> ayahBookmarks = new ArrayList<>();

    // add the page bookmarks directly, save ayah bookmarks for later
    for (int i = 0, bookmarksSize = bookmarks.size(); i < bookmarksSize; i++) {
      Bookmark bookmark = bookmarks.get(i);
      if (bookmark.isPageBookmark()) {
        rows.add(QuranRowFactory.fromBookmark(appContext, bookmark));
      } else {
        ayahBookmarks.add(bookmark);
      }
    }

    // add page bookmarks header if needed
    if (rows.size() > 0) {
      rows.add(0, QuranRowFactory.fromPageBookmarksHeader(appContext));
    }

    // add ayah bookmarks if any
    if (ayahBookmarks.size() > 0) {
      rows.add(QuranRowFactory.fromAyahBookmarksHeader(appContext));
      for (int i = 0, ayahBookmarksSize = ayahBookmarks.size(); i < ayahBookmarksSize; i++) {
        rows.add(QuranRowFactory.fromBookmark(appContext, ayahBookmarks.get(i)));
      }
    }

    return rows;
  }

  private Map<Long, List<Bookmark>> generateTagsMapping(
      List<Tag> tags, List<Bookmark> bookmarks) {
    Set<Long> seenBookmarks = new HashSet<>();
    Map<Long, List<Bookmark>> tagMappings = new HashMap<>();
    for (int i = 0, tagSize = tags.size(); i < tagSize; i++) {
      long id = tags.get(i).id;
      List<Bookmark> matchingBookmarks = new ArrayList<>();
      for (int j = 0, bookmarkSize = bookmarks.size(); j < bookmarkSize; j++) {
        Bookmark bookmark = bookmarks.get(j);
        if (bookmark.tags.contains(id)) {
          matchingBookmarks.add(bookmark);
          seenBookmarks.add(bookmark.id);
        }
      }
      tagMappings.put(id, matchingBookmarks);
    }

    List<Bookmark> untaggedBookmarks = new ArrayList<>();
    for (int i = 0, bookmarksSize = bookmarks.size(); i < bookmarksSize; i++) {
      Bookmark bookmark = bookmarks.get(i);
      if (!seenBookmarks.contains(bookmark.id)) {
        untaggedBookmarks.add(bookmark);
      }
    }
    tagMappings.put(BOOKMARKS_WITHOUT_TAGS_ID, untaggedBookmarks);

    return tagMappings;
  }

  private Map<Long, Tag> generateTagMap(List<Tag> tags) {
    Map<Long, Tag> tagMap = new HashMap<>(tags.size());
    for (int i = 0, size = tags.size(); i < size; i++) {
      Tag tag = tags.get(i);
      tagMap.put(tag.id, tag);
    }
    return tagMap;
  }


  @Override
  public void bind(BookmarksFragment fragment) {
    this.fragment = fragment;
    boolean isRtl = quranSettings.isArabicNames() || QuranUtils.isRtl();
    if (isRtl == this.isRtl) {
      requestData(true);
    } else {
      // don't use the cache if rtl changed
      this.isRtl = isRtl;
      requestData(false);
    }
  }

  @Override
  public void unbind(BookmarksFragment fragment) {
    if (fragment == this.fragment) {
      this.fragment = null;
    }
  }
}
