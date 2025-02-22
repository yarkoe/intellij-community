// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.vcs.log.data.index;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Couple;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.util.Consumer;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.StorageException;
import com.intellij.util.indexing.impl.InputData;
import com.intellij.util.indexing.impl.forward.ForwardIndex;
import com.intellij.util.indexing.impl.forward.ForwardIndexAccessor;
import com.intellij.util.indexing.impl.forward.KeyCollectionForwardIndexAccessor;
import com.intellij.util.indexing.impl.forward.PersistentMapBasedForwardIndex;
import com.intellij.util.io.*;
import com.intellij.vcs.log.data.VcsLogStorage;
import com.intellij.vcs.log.history.EdgeData;
import com.intellij.vcs.log.impl.FatalErrorHandler;
import com.intellij.vcs.log.impl.VcsLogIndexer;
import com.intellij.vcs.log.util.StorageId;
import com.intellij.vcsUtil.VcsUtil;
import gnu.trove.TByteObjectHashMap;
import gnu.trove.THashMap;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.ObjIntConsumer;

public class VcsLogPathsIndex extends VcsLogFullDetailsIndex<List<VcsLogPathsIndex.ChangeKind>, VcsLogIndexer.CompressedDetails> {
  private static final Logger LOG = Logger.getInstance(VcsLogPathsIndex.class);
  public static final String PATHS = "paths";
  public static final String INDEX_PATHS_IDS = "paths-ids";
  public static final String RENAMES_MAP = "renames-map";

  @NotNull private final PathsIndexer myPathsIndexer;

  public VcsLogPathsIndex(@NotNull StorageId storageId, @NotNull VcsLogStorage storage, @NotNull FatalErrorHandler fatalErrorHandler,
                          @NotNull Disposable disposableParent) throws IOException {
    super(storageId, PATHS, new PathsIndexer(storage, createPathsEnumerator(storageId), createRenamesMap(storageId)),
          new ChangeKindListKeyDescriptor(), fatalErrorHandler, disposableParent);

    myPathsIndexer = (PathsIndexer)myIndexer;
    myPathsIndexer.setFatalErrorConsumer(e -> fatalErrorHandler.consume(this, e));
  }

  @Nullable
  @Override
  protected Pair<ForwardIndex, ForwardIndexAccessor<Integer, List<ChangeKind>>> createdForwardIndex() throws IOException {
    if (!isPathsForwardIndexRequired()) return null;
    return Pair.create(new PersistentMapBasedForwardIndex(myStorageId.getStorageFile(myName + ".idx"), false),
                       new KeyCollectionForwardIndexAccessor<Integer, List<ChangeKind>>(new IntCollectionDataExternalizer()) {
                         @Nullable
                         @Override
                         public Collection<Integer> convertToDataType(@NotNull InputData<Integer, List<ChangeKind>> data) {
                           Map<Integer, List<ChangeKind>> map = data.getKeyValues();
                           if (!map.isEmpty()) {
                             List<ChangeKind> changesToParents = ContainerUtil.getFirstItem(map.values());
                             if (changesToParents.size() > 1) return Collections.emptySet();
                           }
                           return super.convertToDataType(data);
                         }
                       });
  }

  @NotNull
  private static PersistentEnumeratorBase<LightFilePath> createPathsEnumerator(@NotNull StorageId storageId) throws IOException {
    File storageFile = storageId.getStorageFile(INDEX_PATHS_IDS);
    return new PersistentBTreeEnumerator<>(storageFile, new LightFilePathKeyDescriptor(),
                                           Page.PAGE_SIZE, null, storageId.getVersion());
  }

  @NotNull
  private static PersistentHashMap<Couple<Integer>, Collection<Couple<Integer>>> createRenamesMap(@NotNull StorageId storageId)
    throws IOException {
    File storageFile = storageId.getStorageFile(RENAMES_MAP);
    return new PersistentHashMap<>(storageFile, new CoupleKeyDescriptor(), new CollectionDataExternalizer(), Page.PAGE_SIZE,
                                   storageId.getVersion());
  }

  @Nullable
  public FilePath getPath(int pathId) {
    try {
      return toFilePath(myPathsIndexer.getPathsEnumerator().valueOf(pathId));
    }
    catch (IOException e) {
      myPathsIndexer.myFatalErrorConsumer.consume(e);
    }
    return null;
  }

  @Override
  public void flush() throws StorageException {
    super.flush();
    myPathsIndexer.myRenamesMap.force();
    myPathsIndexer.getPathsEnumerator().force();
  }

  @Nullable
  public EdgeData<FilePath> findRename(int parent, int child, @NotNull FilePath path, boolean isChildPath) throws IOException {
    Collection<Couple<Integer>> renames = myPathsIndexer.myRenamesMap.get(Couple.of(parent, child));
    if (renames == null) return null;
    int pathId = myPathsIndexer.myPathsEnumerator.enumerate(new LightFilePath(path));
    for (Couple<Integer> rename : renames) {
      if ((isChildPath && rename.second == pathId) ||
          (!isChildPath && rename.first == pathId)) {
        FilePath path1 = getPath(rename.first);
        FilePath path2 = getPath(rename.second);
        return new EdgeData<>(path1, path2);
      }
    }
    return null;
  }

  @NotNull
  public Set<FilePath> getPathsChangedInCommit(int commit) throws IOException {
    Collection<Integer> keysForCommit = getKeysForCommit(commit);
    if (keysForCommit == null) return Collections.emptySet();

    Set<FilePath> paths = new HashSet<>();
    for (Integer pathId : keysForCommit) {
      LightFilePath lightFilePath = myPathsIndexer.getPathsEnumerator().valueOf(pathId);
      if (lightFilePath.isDirectory()) continue;
      paths.add(toFilePath(lightFilePath));
    }

    return paths;
  }

  public void iterateCommits(@NotNull FilePath path,
                             @NotNull ObjIntConsumer<? super List<ChangeKind>> consumer)
    throws IOException, StorageException {
    int pathId = myPathsIndexer.myPathsEnumerator.enumerate(new LightFilePath(path));
    iterateCommitIdsAndValues(pathId, consumer);
  }

  @NotNull
  VcsLogIndexer.PathsEncoder getPathsEncoder() {
    return new VcsLogIndexer.PathsEncoder() {
      @Override
      public int encode(@NotNull String path, boolean isDirectory) {
        try {
          return myPathsIndexer.myPathsEnumerator.enumerate(new LightFilePath(path, isDirectory));
        }
        catch (IOException e) {
          myPathsIndexer.myFatalErrorConsumer.consume(e);
          return 0;
        }
      }
    };
  }

  @Override
  public void dispose() {
    super.dispose();
    try {
      myPathsIndexer.myRenamesMap.close();
      myPathsIndexer.getPathsEnumerator().close();
    }
    catch (IOException e) {
      LOG.warn(e);
    }
  }

  @Contract("null -> null; !null -> !null")
  @Nullable
  private static FilePath toFilePath(@Nullable LightFilePath lightFilePath) {
    if (lightFilePath == null) return null;
    return VcsUtil.getFilePath(lightFilePath.getPath(), lightFilePath.isDirectory());
  }

  public static boolean isPathsForwardIndexRequired() {
    return Registry.is("vcs.log.index.paths.forward.index.on");
  }

  private static class PathsIndexer implements DataIndexer<Integer, List<ChangeKind>, VcsLogIndexer.CompressedDetails> {
    @NotNull private final VcsLogStorage myStorage;
    @NotNull private final PersistentEnumeratorBase<LightFilePath> myPathsEnumerator;
    @NotNull private final PersistentHashMap<Couple<Integer>, Collection<Couple<Integer>>> myRenamesMap;
    @NotNull private Consumer<? super Exception> myFatalErrorConsumer = LOG::error;

    private PathsIndexer(@NotNull VcsLogStorage storage, @NotNull PersistentEnumeratorBase<LightFilePath> enumerator,
                         @NotNull PersistentHashMap<Couple<Integer>, Collection<Couple<Integer>>> renamesMap) {
      myStorage = storage;
      myPathsEnumerator = enumerator;
      myRenamesMap = renamesMap;
    }

    public void setFatalErrorConsumer(@NotNull Consumer<? super Exception> fatalErrorConsumer) {
      myFatalErrorConsumer = fatalErrorConsumer;
    }

    @NotNull
    @Override
    public Map<Integer, List<ChangeKind>> map(@NotNull VcsLogIndexer.CompressedDetails inputData) {
      Map<Integer, List<ChangeKind>> result = new THashMap<>();

      // its not exactly parents count since it is very convenient to assume that initial commit has one parent
      int parentsCount = inputData.getParents().isEmpty() ? 1 : inputData.getParents().size();
      for (int parentIndex = 0; parentIndex < parentsCount; parentIndex++) {
        try {
          int finalParentIndex = parentIndex;
          Collection<Couple<Integer>> renames = new SmartList<>();
          inputData.getRenamedPaths(parentIndex).forEachEntry((beforeId, afterId) -> {
            renames.add(Couple.of(beforeId, afterId));
            getOrCreateChangeKindList(result, beforeId, parentsCount).set(finalParentIndex, ChangeKind.REMOVED);
            getOrCreateChangeKindList(result, afterId, parentsCount).set(finalParentIndex, ChangeKind.ADDED);
            return true;
          });

          if (renames.size() > 0) {
            int commit = myStorage.getCommitIndex(inputData.getId(), inputData.getRoot());
            int parent = myStorage.getCommitIndex(inputData.getParents().get(parentIndex), inputData.getRoot());
            myRenamesMap.put(Couple.of(parent, commit), renames);
          }

          inputData.getModifiedPaths(parentIndex).forEachEntry((pathId, changeType) -> {
            getOrCreateChangeKindList(result, pathId, parentsCount).set(finalParentIndex, createChangeData(changeType));
            return true;
          });
        }
        catch (IOException e) {
          myFatalErrorConsumer.consume(e);
        }
      }

      return result;
    }

    @NotNull
    private static List<ChangeKind> getOrCreateChangeKindList(@NotNull Map<Integer, List<ChangeKind>> pathIdToChangeDataListsMap,
                                                              int pathId, int parentsCount) {
      List<ChangeKind> changeDataList = pathIdToChangeDataListsMap.get(pathId);
      if (changeDataList == null) {
        changeDataList = new SmartList<>();
        for (int i = 0; i < parentsCount; i++) {
          changeDataList.add(ChangeKind.NOT_CHANGED);
        }
        pathIdToChangeDataListsMap.put(pathId, changeDataList);
      }
      return changeDataList;
    }

    @NotNull
    private static ChangeKind createChangeData(@NotNull Change.Type type) {
      switch (type) {
        case NEW:
          return ChangeKind.ADDED;
        case DELETED:
          return ChangeKind.REMOVED;
        default:
          return ChangeKind.MODIFIED;
      }
    }

    @NotNull
    public PersistentEnumeratorBase<LightFilePath> getPathsEnumerator() {
      return myPathsEnumerator;
    }
  }

  private static class ChangeKindListKeyDescriptor implements DataExternalizer<List<ChangeKind>> {
    @Override
    public void save(@NotNull DataOutput out, List<ChangeKind> value) throws IOException {
      DataInputOutputUtil.writeINT(out, value.size());
      for (ChangeKind data : value) {
        out.writeByte(data.id);
      }
    }

    @Override
    public List<ChangeKind> read(@NotNull DataInput in) throws IOException {
      List<ChangeKind> value = new SmartList<>();

      int size = DataInputOutputUtil.readINT(in);
      for (int i = 0; i < size; i++) {
        value.add(ChangeKind.getChangeKindById(in.readByte()));
      }

      return value;
    }
  }

  public enum ChangeKind {
    MODIFIED((byte)0),
    NOT_CHANGED((byte)1), // we do not want to have nulls in lists
    ADDED((byte)2),
    REMOVED((byte)3);

    public final byte id;

    ChangeKind(byte id) {
      this.id = id;
    }

    private static final TByteObjectHashMap<ChangeKind> KINDS = new TByteObjectHashMap<>();

    static {
      for (ChangeKind kind : ChangeKind.values()) {
        KINDS.put(kind.id, kind);
      }
    }

    @NotNull
    public static ChangeKind getChangeKindById(byte id) throws IOException {
      ChangeKind kind = KINDS.get(id);
      if (kind == null) throw new IOException("Change kind by id " + id + " not found.");
      return kind;
    }
  }

  private static class LightFilePath {
    @NotNull private final String myPath;
    private final boolean myIsDirectory;

    private LightFilePath(@NotNull String path, boolean directory) {
      myPath = path;
      myIsDirectory = directory;
    }

    private LightFilePath(@NotNull FilePath filePath) {
      this(filePath.getPath(), filePath.isDirectory());
    }

    @NotNull
    public String getPath() {
      return myPath;
    }

    public boolean isDirectory() {
      return myIsDirectory;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      LightFilePath path = (LightFilePath)o;
      return myIsDirectory == path.myIsDirectory && myPath.equals(path.myPath);
    }

    @Override
    public int hashCode() {
      int result = myPath.hashCode();
      result = 31 * result + (myIsDirectory ? 1 : 0);
      return result;
    }
  }

  private static class LightFilePathKeyDescriptor implements KeyDescriptor<LightFilePath> {
    @Override
    public int getHashCode(LightFilePath path) {
      return path.hashCode();
    }

    @Override
    public boolean isEqual(LightFilePath path1, LightFilePath path2) {
      return path1.equals(path2);
    }

    @Override
    public void save(@NotNull DataOutput out, LightFilePath value) throws IOException {
      IOUtil.writeUTF(out, value.getPath());
      out.writeBoolean(value.myIsDirectory);
    }

    @Override
    public LightFilePath read(@NotNull DataInput in) throws IOException {
      String path = IOUtil.readUTF(in);
      boolean isDirectory = in.readBoolean();
      return new LightFilePath(path, isDirectory);
    }
  }

  private static class CoupleKeyDescriptor implements KeyDescriptor<Couple<Integer>> {
    @Override
    public int getHashCode(Couple<Integer> value) {
      return value.hashCode();
    }

    @Override
    public boolean isEqual(Couple<Integer> val1, Couple<Integer> val2) {
      return val1.equals(val2);
    }

    @Override
    public void save(@NotNull DataOutput out, Couple<Integer> value) throws IOException {
      out.writeInt(value.first);
      out.writeInt(value.second);
    }

    @Override
    public Couple<Integer> read(@NotNull DataInput in) throws IOException {
      return Couple.of(in.readInt(), in.readInt());
    }
  }

  private static class CollectionDataExternalizer implements DataExternalizer<Collection<Couple<Integer>>> {
    @Override
    public void save(@NotNull DataOutput out, Collection<Couple<Integer>> value) throws IOException {
      out.writeInt(value.size());
      for (Couple<Integer> v : value) {
        out.writeInt(v.first);
        out.writeInt(v.second);
      }
    }

    @Override
    public Collection<Couple<Integer>> read(@NotNull DataInput in) throws IOException {
      List<Couple<Integer>> result = new SmartList<>();
      int size = in.readInt();
      for (int i = 0; i < size; i++) {
        result.add(Couple.of(in.readInt(), in.readInt()));
      }
      return result;
    }
  }
}
