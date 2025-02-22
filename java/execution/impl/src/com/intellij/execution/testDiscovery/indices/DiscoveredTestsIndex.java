// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.testDiscovery.indices;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.IndexExtension;
import com.intellij.util.indexing.IndexId;
import com.intellij.util.indexing.impl.MapIndexStorage;
import com.intellij.util.indexing.impl.MapReduceIndex;
import com.intellij.util.indexing.impl.forward.KeyCollectionForwardIndexAccessor;
import com.intellij.util.indexing.impl.forward.PersistentMapBasedForwardIndex;
import com.intellij.util.io.*;
import gnu.trove.TIntArrayList;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

public class DiscoveredTestsIndex extends MapReduceIndex<Integer, TIntArrayList, UsedSources> {
  protected DiscoveredTestsIndex(@NotNull File file) throws IOException {
    super(INDEX_EXTENSION,
          new MyIndexStorage(file),
          new PersistentMapBasedForwardIndex(new File(file, "forward.idx"), false),
          new KeyCollectionForwardIndexAccessor<>(new IntCollectionDataExternalizer()));
  }

  @Override
  public void checkCanceled() {
    ProgressManager.checkCanceled();
  }

  @Override
  protected void requestRebuild(@NotNull Throwable e) {
    //TODO index corrupted
  }

  public boolean containsDataFrom(int testId) throws IOException {
    return getForwardIndex().get(testId) != null;
  }

  private static class MyIndexStorage extends MapIndexStorage<Integer, TIntArrayList> {
    protected MyIndexStorage(@NotNull File storageFile) throws IOException {
      super(storageFile, EnumeratorIntegerDescriptor.INSTANCE, IntArrayExternalizer.INSTANCE, 4 * 1024, false);
    }

    @Override
    protected void checkCanceled() {
      ProgressManager.checkCanceled();
    }
  }

  private static final IndexExtension<Integer, TIntArrayList, UsedSources> INDEX_EXTENSION = new IndexExtension<Integer, TIntArrayList, UsedSources>() {
    @NotNull
    @Override
    public IndexId<Integer, TIntArrayList> getName() {
      return IndexId.create("jvm.discovered.tests");
    }

    @NotNull
    @Override
    public DataIndexer<Integer, TIntArrayList, UsedSources> getIndexer() {return inputData -> inputData.myUsedMethods;}

    @NotNull
    @Override
    public KeyDescriptor<Integer> getKeyDescriptor() {
      return EnumeratorIntegerDescriptor.INSTANCE;
    }

    @NotNull
    @Override
    public DataExternalizer<TIntArrayList> getValueExternalizer() {
      return IntArrayExternalizer.INSTANCE;
    }

    @Override
    public int getVersion() {
      return DiscoveredTestDataHolder.VERSION;
    }
  };
}
