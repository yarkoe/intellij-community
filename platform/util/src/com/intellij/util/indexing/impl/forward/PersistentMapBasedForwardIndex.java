// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.indexing.impl.forward;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.ByteArraySequence;
import com.intellij.util.io.ByteSequenceDataExternalizer;
import com.intellij.util.io.EnumeratorIntegerDescriptor;
import com.intellij.util.io.PersistentHashMap;
import com.intellij.util.io.PersistentHashMapValueStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;

public class PersistentMapBasedForwardIndex implements ForwardIndex {
  private static final Logger LOG = Logger.getInstance(PersistentMapBasedForwardIndex.class);
  @NotNull
  private volatile PersistentHashMap<Integer, ByteArraySequence> myPersistentMap;
  @NotNull
  private final File myMapFile;
  private final boolean myUseChunks;
  private final boolean myReadOnly;

  public PersistentMapBasedForwardIndex(@NotNull File mapFile, boolean isReadOnly) throws IOException {
    this(mapFile, true, isReadOnly);
  }

  public PersistentMapBasedForwardIndex(@NotNull File mapFile, boolean useChunks, boolean isReadOnly) throws IOException {
    myPersistentMap = createMap(mapFile);
    myMapFile = mapFile;
    myUseChunks = useChunks;
    myReadOnly = isReadOnly;
  }

  @NotNull
  protected PersistentHashMap<Integer, ByteArraySequence> createMap(File file) throws IOException {
    Boolean oldHasNoChunksValue = PersistentHashMapValueStorage.CreationTimeOptions.HAS_NO_CHUNKS.get();
    PersistentHashMapValueStorage.CreationTimeOptions.HAS_NO_CHUNKS.set(!myUseChunks);
    Boolean previousReadOnly = PersistentHashMapValueStorage.CreationTimeOptions.READONLY.get();
    PersistentHashMapValueStorage.CreationTimeOptions.READONLY.set(myReadOnly);
    try {
      return new PersistentHashMap<>(file, EnumeratorIntegerDescriptor.INSTANCE, ByteSequenceDataExternalizer.INSTANCE);
    }
    finally {
      PersistentHashMapValueStorage.CreationTimeOptions.HAS_NO_CHUNKS.set(oldHasNoChunksValue);
      PersistentHashMapValueStorage.CreationTimeOptions.READONLY.set(previousReadOnly);
    }
  }

  @Nullable
  @Override
  public ByteArraySequence get(@NotNull Integer key) throws IOException {
    return myPersistentMap.get(key);
  }

  @Override
  public void put(@NotNull Integer key, @Nullable ByteArraySequence value) throws IOException {
    if (value == null) {
      myPersistentMap.remove(key);
    }
    else {
      myPersistentMap.put(key, value);
    }
  }

  @Override
  public void force() {
    myPersistentMap.force();
  }

  @Override
  public void clear() throws IOException {
    File baseFile = myPersistentMap.getBaseFile();
    try {
      myPersistentMap.close();
    }
    catch (IOException e) {
      LOG.info(e);
    }
    PersistentHashMap.deleteFilesStartingWith(baseFile);
    myPersistentMap = createMap(myMapFile);
  }

  @Override
  public void close() throws IOException {
    myPersistentMap.close();
  }

  public boolean containsMapping(int key) throws IOException {
    return myPersistentMap.containsMapping(key);
  }
}
