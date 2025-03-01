// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs.impl;

import com.intellij.concurrency.ConcurrentCollectionFactory;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationListener;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.InvalidVirtualFileAccessException;
import com.intellij.openapi.vfs.newvfs.NewVirtualFileSystem;
import com.intellij.openapi.vfs.newvfs.persistent.FSRecords;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFSImpl;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.BitUtil;
import com.intellij.util.Functions;
import com.intellij.util.ObjectUtils;
import com.intellij.util.concurrency.AtomicFieldUpdater;
import com.intellij.util.containers.*;
import com.intellij.util.keyFMap.KeyFMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The place where all the data is stored for VFS parts loaded into a memory: name-ids, flags, user data, children.
 * <p>
 * The purpose is to avoid holding this data in separate immortal file/directory objects because that involves space overhead, significant
 * when there are hundreds of thousands of files.
 * <p>
 * The data is stored per-id in blocks of {@link #SEGMENT_SIZE}. File ids in one project tend to cluster together,
 * so the overhead for non-loaded id should not be large in most cases.
 * <p>
 * File objects are still created if needed. There might be several objects for the same file, so equals() should be used instead of ==.
 * <p>
 * The lifecycle of a file object is as follows:
 *
 * <ol>
 * <li> The file has not been instantiated yet, so {@link #getFileById} returns null. </li>
 *
 * <li> A file is explicitly requested by calling getChildren or findChild on its parent. The parent initializes all the necessary data (in a thread-safe context)
 * and creates the file instance. See {@link #initFile} </li>
 *
 * <li> After that the file is live, an object representing it can be retrieved any time from its parent. File system roots are
 * kept on hard references in {@link PersistentFS} </li>
 *
 * <li> If a file is deleted (invalidated), then its data is not needed anymore, and should be removed. But this can only happen after
 * all the listener have been notified about the file deletion and have had their chance to look at the data the last time. See {@link #killInvalidatedFiles()} </li>
 *
 * <li> The file with removed data is marked as "dead" (see {@link #deadMarker}), any access to it will throw {@link InvalidVirtualFileAccessException}
 * Dead ids won't be reused in the same session of the IDE. </li>
 * </ol>
 */
public final class VfsData {
  private static final Logger LOG = Logger.getInstance(VfsData.class);

  private static final int SEGMENT_BITS = 9;
  private static final int SEGMENT_SIZE = 1 << SEGMENT_BITS;
  private static final int OFFSET_MASK = SEGMENT_SIZE - 1;

  private final Application app;
  private final PersistentFSImpl owningPersistentFS;

  private final Object deadMarker = ObjectUtils.sentinel("dead file");

  //TODO RC: seems like the segments are only cached, but never evicted -- this could create memory problems
  //TODO RC: FSRecords was quite optimized recently, probably caching is not needed anymore.
  //         we could remove Segment.indexingFlag immediately (not used anymore), we could probably remove Segment.nameId
  //         and replace it with direct FSRecordsImpl access. Not sure about remaining (flag+modCount) field though --
  //         on the first sight they look like an additional data, independent from persistent VFS data?

  /** [segmentIndex -> Segment] */
  private final ConcurrentIntObjectMap<Segment> segments = ConcurrentCollectionFactory.createConcurrentIntObjectMap();

  /**
   * Set of deleted file ids. Never cleaned during a session (deleted fileId could be re-used,
   * but it could be done only on a session start, see {@link com.intellij.openapi.vfs.newvfs.persistent.FSRecordsImpl})
   */
  private final ConcurrentBitSet invalidatedFileIds = ConcurrentBitSet.create();

  /**
   * Records (fileIds) to be cleaned (namely: from {@link #changedParents} and {@link Segment#objectFieldsArray})
   * As soon, as apt slots are cleaned -- so does this set.
   * Guarded by {@link #deadMarker}
   */
  private IntSet queueOfFileIdsToBeCleaned = new IntOpenHashSet();

  private final IntObjectMap<VirtualDirectoryImpl> changedParents = ConcurrentCollectionFactory.createConcurrentIntObjectMap();

  public VfsData(@NotNull Application app,
                 @NotNull PersistentFSImpl pfs) {
    this.app = app;
    this.owningPersistentFS = pfs;
    app.addApplicationListener(new ApplicationListener() {
      @Override
      public void writeActionFinished(@NotNull Object action) {
        // after top-level write action is finished, all the deletion listeners should have processed the deleted files
        // and their data is considered safe to remove. From this point on accessing a removed file will result in an exception.
        if (!app.isWriteAccessAllowed()) {
          killInvalidatedFiles();
        }
      }
    }, app);
  }

  @NotNull
  PersistentFSImpl owningPersistentFS() {
    return owningPersistentFS;
  }

  private void killInvalidatedFiles() {
    synchronized (deadMarker) {
      if (!queueOfFileIdsToBeCleaned.isEmpty()) {
        for (IntIterator iterator = queueOfFileIdsToBeCleaned.iterator(); iterator.hasNext(); ) {
          int id = iterator.nextInt();
          Segment segment = Objects.requireNonNull(getSegment(id, false));
          segment.objectFieldsArray.set(objectOffsetInSegment(id), deadMarker);
          changedParents.remove(id);
        }
        queueOfFileIdsToBeCleaned = new IntOpenHashSet();
      }
    }
  }

  @Nullable
  VirtualFileSystemEntry getFileById(int id, @NotNull VirtualDirectoryImpl parent, boolean putToMemoryCache) {
    VirtualFileSystemEntry dir = owningPersistentFS.getCachedDir(id);
    if (dir != null) return dir;

    Segment segment = getSegment(id, false);
    if (segment == null) return null;

    int offset = objectOffsetInSegment(id);
    Object o = segment.objectFieldsArray.get(offset);
    if (o == null) return null;

    if (o == deadMarker) {
      throw reportDeadFileAccess(new VirtualFileImpl(id, segment, parent));
    }
    final int nameId = segment.getNameId(id);
    if (nameId <= 0) {
      int parentId = owningPersistentFS.peer().getParent(id);
      throw new AssertionError("nameId=" + nameId + "; data=" + o + ";" +
                               " parent=" + parent + "; parent.id=" + parent.getId() + "; db.parent=" + parentId);
    }

    if (o instanceof DirectoryData) {
      if (putToMemoryCache) {
        return owningPersistentFS.getOrCacheDir(new VirtualDirectoryImpl(id, segment, (DirectoryData)o, parent, parent.getFileSystem()));
      }
      VirtualFileSystemEntry entry = owningPersistentFS.getCachedDir(id);
      if (entry != null) return entry;
      return new VirtualDirectoryImpl(id, segment, (DirectoryData)o, parent, parent.getFileSystem());
    }
    return new VirtualFileImpl(id, segment, parent);
  }

  private static @NotNull InvalidVirtualFileAccessException reportDeadFileAccess(@NotNull VirtualFileSystemEntry file) {
    return new InvalidVirtualFileAccessException("Accessing dead virtual file: " + file.getUrl());
  }

  @Contract("_,true->!null")
  Segment getSegment(int id, boolean create) {
    int segmentIndex = segmentIndex(id);
    Segment segment = segments.get(segmentIndex);
    if (segment != null || !create) {
      return segment;
    }
    return segments.cacheOrGet(segmentIndex, new Segment(this));
  }

  public boolean hasLoadedFile(int id) {
    Segment segment = getSegment(id, false);
    return segment != null && segment.objectFieldsArray.get(objectOffsetInSegment(id)) != null;
  }

  public static final class FileAlreadyCreatedException extends RuntimeException {
    private FileAlreadyCreatedException(@NotNull String message) {
      super(message);
    }
  }

  //@GuardedBy("parent.DirectoryData")
  static void initFile(int id, @NotNull Segment segment, int nameId, @NotNull Object data) throws FileAlreadyCreatedException {
    int offset = objectOffsetInSegment(id);

    segment.setNameId(id, nameId);

    Object existingData = segment.objectFieldsArray.get(offset);
    if (existingData != null) {
      //TODO RC: it seems like concurrency issue, really -- check the locks acquired up the stack in all invocation traces

      //FIXME RC: move .describeAlreadyCreatedFile() from FSRecordsImpl -- here, and replace static call with
      //  vfs instance call:
      throw new FileAlreadyCreatedException(
        FSRecords.describeAlreadyCreatedFile(id, nameId)
        + " data: " + data
        + ", alreadyExistingData: " + existingData
      );
    }
    segment.objectFieldsArray.set(offset, data);
  }

  @NotNull
  CharSequence getNameByFileId(int fileId) {
    //MAYBE RC: persistentFS.peer().getName(fileId) ?
    int nameId = getNameId(fileId);
    return owningPersistentFS.getNameByNameId(nameId);
  }

  int getNameId(int id) {
    return Objects.requireNonNull(getSegment(id, false)).getNameId(id);
  }

  boolean isFileValid(int id) {
    return !invalidatedFileIds.get(id);
  }

  @Nullable
  VirtualDirectoryImpl getChangedParent(int id) {
    return changedParents.get(id);
  }

  private void changeParent(int id, @NotNull VirtualDirectoryImpl parent) {
    app.assertWriteAccessAllowed();
    changedParents.put(id, parent);
  }

  void invalidateFile(int id) {
    invalidatedFileIds.set(id);
    synchronized (deadMarker) {
      queueOfFileIdsToBeCleaned.add(id);
    }
  }

  /** @return offset of fileId's data in {@link Segment#objectFieldsArray} */
  private static int objectOffsetInSegment(int fileId) {
    if (fileId <= 0) throw new IllegalArgumentException("invalid argument id: " + fileId);
    return fileId & OFFSET_MASK;
  }

  private static int segmentIndex(int fileId) {
    return fileId >>> SEGMENT_BITS;
  }


  /** Caches info about SEGMENT_SIZE consequent files, indexed by fileId */
  static final class Segment {
    private static final int INT_FIELDS_COUNT = 2;
    private static final int NAME_ID_FIELD_NO = 0;
    private static final int FLAGS_FIELD_NO = 1;

    final @NotNull VfsData owningVfsData;


    /** user data (KeyFMap) for files, {@link DirectoryData} for folders */
    private final AtomicReferenceArray<Object> objectFieldsArray;

    /**
     * fields cortege [nameId, flags] per fileId
     * flag's lowest 3 bytes are used as modificationCounter
     */
    private final AtomicIntegerArray intFieldsArray;


    /** the reference is synchronized by read-write lock; clients outside read-action deserve to get outdated result */
    @Nullable Segment replacement;

    Segment(@NotNull VfsData owningVfsData) {
      this(owningVfsData, new AtomicReferenceArray<>(SEGMENT_SIZE), new AtomicIntegerArray(SEGMENT_SIZE * INT_FIELDS_COUNT));
    }

    private Segment(@NotNull VfsData owningVfsData,
                    @NotNull AtomicReferenceArray<Object> objectFieldsArray,
                    @NotNull AtomicIntegerArray intFieldsArray) {
      this.objectFieldsArray = objectFieldsArray;
      this.intFieldsArray = intFieldsArray;
      this.owningVfsData = owningVfsData;
    }

    int getNameId(int fileId) {
      return intFieldsArray.get(fieldOffset(fileId, NAME_ID_FIELD_NO));
    }

    void setNameId(int fileId, int nameId) {
      if (fileId <= 0 || nameId <= 0) throw new IllegalArgumentException("invalid arguments id: " + fileId + "; nameId: " + nameId);
      intFieldsArray.set(fieldOffset(fileId, NAME_ID_FIELD_NO), nameId);
    }

    void setUserMap(int fileId, @NotNull KeyFMap map) {
      objectFieldsArray.set(objectOffsetInSegment(fileId), map);
    }

    @NotNull
    KeyFMap getUserMap(@NotNull VirtualFileSystemEntry file, int id) {
      Object o = objectFieldsArray.get(objectOffsetInSegment(id));
      if (!(o instanceof KeyFMap)) {
        throw reportDeadFileAccess(file);
      }
      return (KeyFMap)o;
    }

    boolean changeUserMap(int fileId, KeyFMap oldMap, KeyFMap newMap) {
      return objectFieldsArray.compareAndSet(objectOffsetInSegment(fileId), oldMap, newMap);
    }

    boolean getFlag(int fileId, @VirtualFileSystemEntry.Flags int mask) {
      BitUtil.assertOneBitMask(mask);
      assert (mask & ~VirtualFileSystemEntry.ALL_FLAGS_MASK) == 0 : "Unexpected flag";

      int offset = fieldOffset(fileId, FLAGS_FIELD_NO);
      return (intFieldsArray.get(offset) & mask) != 0;
    }

    void setFlag(int fileId, @VirtualFileSystemEntry.Flags int mask, boolean value) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("Set flag " + Integer.toHexString(mask) + "=" + value + " for id=" + fileId);
      }
      assert (mask & ~VirtualFileSystemEntry.ALL_FLAGS_MASK) == 0 : "Unexpected flag";
      int offset = fieldOffset(fileId, FLAGS_FIELD_NO);
      intFieldsArray.updateAndGet(offset, oldInt -> BitUtil.set(oldInt, mask, value));
    }

    void setFlags(int fileId, @VirtualFileSystemEntry.Flags int combinedMask, @VirtualFileSystemEntry.Flags int combinedValue) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("Set flags " + Integer.toHexString(combinedMask) + "=" + combinedValue + " for id=" + fileId);
      }
      assert (combinedMask & ~VirtualFileSystemEntry.ALL_FLAGS_MASK) == 0 : "Unexpected flag";
      assert (~combinedMask & combinedValue) == 0 : "Value (" + Integer.toHexString(combinedValue) + ") set bits outside mask (" +
                                                    Integer.toHexString(combinedMask) + ")";
      int offset = fieldOffset(fileId, FLAGS_FIELD_NO);
      intFieldsArray.updateAndGet(offset, oldInt -> oldInt & ~combinedMask | combinedValue);
    }

    long getModificationStamp(int fileId) {
      int offset = fieldOffset(fileId, FLAGS_FIELD_NO);
      return intFieldsArray.get(offset) & ~VirtualFileSystemEntry.ALL_FLAGS_MASK;
    }

    void setModificationStamp(int fileId, long stamp) {
      int offset = fieldOffset(fileId, FLAGS_FIELD_NO);
      intFieldsArray.updateAndGet(offset, oldInt -> (oldInt & VirtualFileSystemEntry.ALL_FLAGS_MASK) |
                                                    ((int)stamp & ~VirtualFileSystemEntry.ALL_FLAGS_MASK));
    }

    void changeParent(int fileId, VirtualDirectoryImpl directory) {
      assert replacement == null;
      replacement = new Segment(owningVfsData, objectFieldsArray, intFieldsArray);
      int segmentIndex = segmentIndex(fileId);
      boolean replaced = owningVfsData.segments.replace(segmentIndex, this, replacement);
      assert replaced;
      owningVfsData.changeParent(fileId, directory);
    }

    /** @return offset of field #fieldNo of file=fileId in a {@link #intFieldsArray} */
    private static int fieldOffset(int fileId,
                                   int fieldNo) {
      if (fileId <= 0) {
        throw new IllegalArgumentException("invalid fileId: " + fileId);
      }
      assert (0 <= fieldNo && fieldNo < INT_FIELDS_COUNT) : "fieldNo(=" + fieldNo + ") must be in [0," + INT_FIELDS_COUNT + ")";
      return ((fileId & OFFSET_MASK) * INT_FIELDS_COUNT) + fieldNo;
    }
  }

  // non-final field accesses are synchronized on this instance, but this happens in VirtualDirectoryImpl
  static final class DirectoryData {
    private static final AtomicFieldUpdater<DirectoryData, KeyFMap> USER_MAP_UPDATER = AtomicFieldUpdater.forFieldOfType(DirectoryData.class, KeyFMap.class);
    volatile @NotNull KeyFMap userMap = KeyFMap.EMPTY_MAP;
    /**
     * sorted by {@link VfsData#getNameByFileId(int)}
     * assigned under lock(this) only; never modified in-place
     *
     * @see VirtualDirectoryImpl#findIndex(int[], CharSequence, boolean)
     */
    volatile int @NotNull [] childrenIds = ArrayUtilRt.EMPTY_INT_ARRAY; // guarded by this
    volatile boolean allChildrenLoaded;

    // assigned under lock(this) only; accessed/modified map contents under lock(myAdoptedNames)
    private volatile Set<CharSequence> adoptedNames;

    VirtualFileSystemEntry @NotNull [] getFileChildren(@NotNull VirtualDirectoryImpl parent, boolean putToMemoryCache) {
      int[] ids = childrenIds;
      VirtualFileSystemEntry[] children = new VirtualFileSystemEntry[ids.length];
      for (int i = 0; i < ids.length; i++) {
        int childId = ids[i];
        VirtualFileSystemEntry child = parent.getVfsData().getFileById(childId, parent, putToMemoryCache);
        if (child == null) {
          throw new AssertionError("No file for id " + childId + ", parentId = " + parent.myId);
        }
        children[i] = child;
      }
      return children;
    }

    boolean allChildrenLoaded() {
      return allChildrenLoaded;
    }

    void setAllChildrenLoaded() {
      allChildrenLoaded = true;
    }

    boolean changeUserMap(@NotNull KeyFMap oldMap, @NotNull KeyFMap newMap) {
      return USER_MAP_UPDATER.compareAndSet(this, oldMap, newMap);
    }

    boolean isAdoptedName(@NotNull CharSequence name) {
      Set<CharSequence> adopted = adoptedNames;
      if (adopted == null) {
        return false;
      }
      synchronized (adopted) {
        return adopted.contains(name);
      }
    }

    /**
     * must call removeAdoptedName() before adding new child with the same name
     * or otherwise {@link VirtualDirectoryImpl#doFindChild(String, boolean, NewVirtualFileSystem, boolean)} would risk finding already non-existing child
     * <p>
     * Must be called in synchronized(VfsData)
     */
    void removeAdoptedName(@NotNull CharSequence name) {
      Set<CharSequence> adopted = adoptedNames;
      if (adopted == null) {
        return;
      }
      synchronized (adopted) {
        boolean removed = adopted.remove(name);
        if (removed && adopted.isEmpty()) {
          adoptedNames = null;
        }
      }
    }

    /**
     * Must be called in synchronized(VfsData)
     */
    void addAdoptedName(@NotNull String name, boolean caseSensitive) {
      Set<CharSequence> adopted = getOrCreateAdoptedNames(caseSensitive);
      synchronized (adopted) {
        adopted.add(name);
      }
    }

    /**
     * Optimization: faster than call {@link #addAdoptedName(String, boolean)} one by one
     * Must be called in synchronized(VfsData)
     */
    void addAdoptedNames(@NotNull Collection<? extends CharSequence> names, boolean caseSensitive) {
      Set<CharSequence> adopted = getOrCreateAdoptedNames(caseSensitive);
      synchronized (adopted) {
        adopted.addAll(names);
      }
    }

    /**
     * Must be called in synchronized(VfsData)
     */
    private @NotNull Set<CharSequence> getOrCreateAdoptedNames(boolean caseSensitive) {
      Set<CharSequence> adopted = adoptedNames;
      if (adopted == null) {
        adopted = CollectionFactory.createCharSequenceSet(caseSensitive);
        adoptedNames = adopted;
      }
      return adopted;
    }

    @NotNull
    List<String> getAdoptedNames() {
      Set<CharSequence> adopted = adoptedNames;
      if (adopted == null) return Collections.emptyList();
      synchronized (adopted) {
        return ContainerUtil.map(adopted, Functions.TO_STRING());
      }
    }

    /**
     * Must be called in synchronized(VfsData)
     */
    void clearAdoptedNames() {
      adoptedNames = null;
    }

    @Override
    public @NonNls String toString() {
      return "DirectoryData{" +
             "myUserMap=" + userMap +
             ", myChildrenIds=" + Arrays.toString(childrenIds) +
             ", myAdoptedNames=" + adoptedNames +
             '}';
    }
  }
}
